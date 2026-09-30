package com.zzx.docrag.ingest;

import com.zzx.docrag.es.ChunkGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Async ingestion orchestration: accept fast, process in the background, always tell the truth.
 *
 * <p>Why a queue instead of a thread pool: a pool loses in-flight work on process restart,
 * cannot be scaled across instances, and gives no visibility into backlog. A durable queue
 * with a DLQ survives restarts, scales by adding consumers, and the lag is observable.
 *
 * <p>Idempotency: docId is deterministic (sha256 of name+title) and the ES write path
 * deletes the document's chunks before re-indexing, so a redelivery or a re-upload replaces
 * rather than duplicates. The consumer also skips records already DONE, which makes blind
 * redeliveries no-ops.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final IngestionService ingestionService;
    private final ChunkGateway chunkGateway;

    public DocumentService(DocumentRepository repository,
                           RabbitTemplate rabbitTemplate,
                           IngestionService ingestionService,
                           ChunkGateway chunkGateway) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.ingestionService = ingestionService;
        this.chunkGateway = chunkGateway;
    }

    /** Accepts inline text. {@code sync=true} processes in the request thread (scripts/dev). */
    public Map<String, Object> submitText(String title, String content, boolean sync) {
        String resolved = (title == null || title.isBlank()) ? "untitled" : title;
        String docId = IngestionService.buildDocId("inline", resolved);
        DocumentRecord record = upsertPending(docId, resolved, "inline", content, null);
        if (sync) {
            IngestResult result = ingestionService.ingestText(resolved, content);
            markDone(record, result.chunkCount());
            return resultBody(docId, DocumentRecord.DONE, result.chunkCount(), result.totalMillis());
        }
        publish(docId);
        return resultBody(docId, DocumentRecord.PENDING, 0, 0);
    }

    /** Accepts a file already staged on disk. {@code sync=true} processes in the request thread. */
    public Map<String, Object> submitFile(Path storedFile, String originalName, String title, boolean sync) throws IOException {
        String resolved = (title == null || title.isBlank()) ? stripExtension(originalName) : title;
        String docId = IngestionService.buildDocId(originalName, resolved);
        DocumentRecord record = upsertPending(docId, resolved, originalName, null, storedFile.toString());
        if (sync) {
            IngestResult result = ingestionService.ingest(storedFile, resolved);
            markDone(record, result.chunkCount());
            return resultBody(docId, DocumentRecord.DONE, result.chunkCount(), result.totalMillis());
        }
        publish(docId);
        return resultBody(docId, DocumentRecord.PENDING, 0, 0);
    }

    /**
     * Consumer entry point. Throws on failure ON PURPOSE: the listener retry template
     * re-invokes this method (attempts climbs each time), and after max-attempts the
     * message is rejected into the DLQ while the record stays FAILED with its last error.
     */
    public void processJob(String docId) {
        DocumentRecord record = repository.findById(docId).orElse(null);
        if (record == null) {
            log.warn("Ingest job for unknown docId={} - dropping (record deleted before delivery?)", docId);
            return;
        }
        if (DocumentRecord.DONE.equals(record.getStatus())) {
            log.info("docId={} already DONE - skipping redelivery", docId);
            return;
        }
        record.setStatus(DocumentRecord.PROCESSING);
        record.setAttempts(record.getAttempts() + 1);
        record.setUpdatedAt(Instant.now());
        repository.save(record);

        try {
            IngestResult result = record.getInlineContent() != null
                    ? ingestionService.ingestText(record.getTitle(), record.getInlineContent())
                    : ingestionService.ingest(Path.of(record.getFilePath()), record.getTitle());
            markDone(record, result.chunkCount());
            log.info("Async ingestion DONE docId={} chunks={} attempt={}", docId, result.chunkCount(), record.getAttempts());
        } catch (IOException | RuntimeException e) {
            record.setStatus(DocumentRecord.FAILED);
            record.setError(truncate(e.toString(), 1900));
            record.setUpdatedAt(Instant.now());
            repository.save(record);
            throw new IllegalStateException("Ingestion failed for " + docId
                    + " (attempt " + record.getAttempts() + ")", e);
        }
    }

    public List<DocumentRecord> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Map<String, Object> delete(String docId) {
        int deleted = chunkGateway.deleteByDocId(docId);
        repository.deleteById(docId);
        return Map.of("docId", docId, "deletedChunks", deleted);
    }

    // ------------------------------------------------------------------ internals

    private DocumentRecord upsertPending(String docId, String title, String source,
                                         String inlineContent, String filePath) {
        DocumentRecord record = repository.findById(docId).orElseGet(() -> {
            DocumentRecord fresh = new DocumentRecord();
            fresh.setDocId(docId);
            fresh.setCreatedAt(Instant.now());
            return fresh;
        });
        record.setTitle(title);
        record.setSource(source);
        record.setInlineContent(inlineContent);
        record.setFilePath(filePath);
        record.setStatus(DocumentRecord.PENDING);
        record.setAttempts(0);
        record.setChunkCount(0);
        record.setError(null);
        record.setUpdatedAt(Instant.now());
        return repository.save(record);
    }

    private void publish(String docId) {
        rabbitTemplate.convertAndSend(RabbitConfig.INGEST_EXCHANGE, RabbitConfig.INGEST_ROUTING_KEY,
                new IngestJob(docId));
    }

    private void markDone(DocumentRecord record, int chunkCount) {
        record.setStatus(DocumentRecord.DONE);
        record.setChunkCount(chunkCount);
        record.setError(null);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
    }

    private static Map<String, Object> resultBody(String docId, String status, int chunkCount, long totalMillis) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", docId);
        body.put("status", status);
        if (chunkCount > 0) {
            body.put("chunkCount", chunkCount);
        }
        if (totalMillis > 0) {
            body.put("totalMillis", totalMillis);
        }
        return body;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

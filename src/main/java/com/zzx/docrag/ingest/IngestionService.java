package com.zzx.docrag.ingest;

import com.zzx.docrag.es.Chunk;
import com.zzx.docrag.es.ChunkGateway;
import com.zzx.docrag.llm.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Ingestion pipeline: parse -> chunk -> embed -> index.
 *
 * <p>Re-ingesting the same document deletes the previous chunks first, which makes the
 * pipeline idempotent and keeps the corpus consistent while iterating on chunk settings.
 *
 * <p>Next iteration (documented, not yet implemented): move embedding to a message consumer
 * so uploads return immediately and a burst of documents is absorbed by a queue instead of
 * occupying request threads.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final List<DocumentParser> parsers;
    private final TextChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final ChunkGateway chunkGateway;

    public IngestionService(List<DocumentParser> parsers,
                            TextChunker chunker,
                            EmbeddingClient embeddingClient,
                            ChunkGateway chunkGateway) {
        this.parsers = parsers;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.chunkGateway = chunkGateway;
    }

    /** Ingests a file that the caller has already written to disk. */
    public IngestResult ingest(Path file, String title) throws IOException {
        long started = System.currentTimeMillis();
        String fileName = file.getFileName().toString();
        String resolvedTitle = (title == null || title.isBlank()) ? stripExtension(fileName) : title;
        String docId = buildDocId(fileName, resolvedTitle);

        long t0 = System.currentTimeMillis();
        DocumentParser parser = selectParser(fileName);
        ParsedDocument parsed = parser.parse(file, resolvedTitle);
        long parseMillis = System.currentTimeMillis() - t0;

        if (parsed.text() == null || parsed.text().isBlank()) {
            throw new IllegalArgumentException("No extractable text in " + fileName
                    + " (a scanned PDF without a text layer needs OCR first)");
        }
        return process(parsed, docId, parseMillis, started);
    }

    /** Ingests text submitted directly through the API. */
    public IngestResult ingestText(String title, String content) {
        long started = System.currentTimeMillis();
        String resolvedTitle = (title == null || title.isBlank()) ? "untitled" : title;
        String docId = buildDocId("inline", resolvedTitle);
        ParsedDocument parsed = new ParsedDocument(resolvedTitle, content);
        return process(parsed, docId, 0L, started);
    }

    private IngestResult process(ParsedDocument parsed, String docId, long parseMillis, long started) {
        long t1 = System.currentTimeMillis();
        List<Chunk> chunkSkeletons = chunker.chunk(parsed, docId);
        long chunkMillis = System.currentTimeMillis() - t1;

        if (chunkSkeletons.isEmpty()) {
            throw new IllegalArgumentException("Chunking produced no chunks for " + parsed.title());
        }

        long t2 = System.currentTimeMillis();
        List<Chunk> chunks = new ArrayList<>(chunkSkeletons.size());
        for (Chunk skeleton : chunkSkeletons) {
            float[] vector = embeddingClient.embed(skeleton.content());
            chunks.add(new Chunk(
                    skeleton.chunkId(),
                    skeleton.docId(),
                    skeleton.title(),
                    skeleton.source(),
                    skeleton.ordinal(),
                    skeleton.content(),
                    vector));
        }
        long embedMillis = System.currentTimeMillis() - t2;

        long t3 = System.currentTimeMillis();
        chunkGateway.ensureIndex();
        int removed = chunkGateway.deleteByDocId(docId);
        chunkGateway.index(chunks);
        long indexMillis = System.currentTimeMillis() - t3;

        IngestResult result = new IngestResult(
                docId,
                parsed.title(),
                chunks.size(),
                parseMillis,
                chunkMillis,
                embedMillis,
                indexMillis,
                System.currentTimeMillis() - started,
                preview(chunks.get(0).content()));

        log.info("Ingested docId={} title='{}' chunks={} (replaced {}) parse={}ms chunk={}ms embed={}ms index={}ms total={}ms",
                docId, parsed.title(), chunks.size(), removed,
                parseMillis, chunkMillis, embedMillis, indexMillis, result.totalMillis());
        return result;
    }

    private DocumentParser selectParser(String fileName) {
        Optional<DocumentParser> parser = parsers.stream()
                .filter(candidate -> candidate.supports(fileName))
                .findFirst();
        return parser.orElseThrow(() -> new IllegalArgumentException(
                "Unsupported file type: " + fileName + ". Supported: .pdf .docx .txt .md"));
    }

    /**
     * Document id is derived from the file name, so uploading a corrected version of the same
     * file replaces its content instead of duplicating it.
     */
    private String buildDocId(String fileName, String title) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((fileName + "|" + title).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String preview(String content) {
        return content.length() <= 200 ? content : content.substring(0, 200) + "...";
    }

    /** Exposed for tests and for the streaming upload path. */
    public IngestResult ingestStream(InputStream in, String fileName, String title) throws IOException {
        DocumentParser parser = selectParser(fileName);
        String resolvedTitle = (title == null || title.isBlank()) ? stripExtension(fileName) : title;
        long started = System.currentTimeMillis();
        long t0 = System.currentTimeMillis();
        ParsedDocument parsed = parser.parse(in, resolvedTitle);
        long parseMillis = System.currentTimeMillis() - t0;
        return process(parsed, buildDocId(fileName, resolvedTitle), parseMillis, started);
    }
}

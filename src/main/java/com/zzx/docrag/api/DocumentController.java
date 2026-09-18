package com.zzx.docrag.api;

import com.zzx.docrag.es.ChunkGateway;
import com.zzx.docrag.es.DocInfo;
import com.zzx.docrag.ingest.IngestResult;
import com.zzx.docrag.ingest.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Document ingestion and management.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestionService ingestionService;
    private final ChunkGateway chunkGateway;

    public DocumentController(IngestionService ingestionService, ChunkGateway chunkGateway) {
        this.ingestionService = ingestionService;
        this.chunkGateway = chunkGateway;
    }

    /**
     * Uploads a file. The multipart payload is streamed to a temporary file rather than held
     * in memory, so a 40 MB manual does not become a 40 MB heap allocation per concurrent
     * upload.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResult upload(@RequestPart("file") MultipartFile file,
                               @RequestParam(value = "title", required = false) String title) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String fileName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        Path temp = Files.createTempFile("docrag-", "-" + fileName.replaceAll("[^A-Za-z0-9._-]", "_"));
        try {
            file.transferTo(temp);
            return ingestionService.ingest(temp, title);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Ingests pasted text. */
    @PostMapping("/text")
    public IngestResult ingestText(@RequestBody @Valid IngestTextRequest request) {
        return ingestionService.ingestText(request.title(), request.content());
    }

    /** Lists ingested documents with chunk counts. */
    @GetMapping
    public List<DocInfo> list(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return chunkGateway.listDocuments(limit);
    }

    /** Removes a document and all of its chunks. */
    @DeleteMapping("/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) {
        int deleted = chunkGateway.deleteByDocId(docId);
        return Map.of("docId", docId, "deletedChunks", deleted);
    }
}

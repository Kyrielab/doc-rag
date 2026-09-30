package com.zzx.docrag.api;

import com.zzx.docrag.ingest.DocumentRecord;
import com.zzx.docrag.ingest.DocumentService;
import com.zzx.docrag.ingest.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 *
 * <p>Async by default: an upload stages the file, writes the PENDING record and publishes
 * the job, then returns 202 immediately - a 40 MB manual no longer occupies a request
 * thread for the whole embed-and-index run. {@code ?sync=true} keeps the old blocking
 * behaviour for scripts and development.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** Staging directory for uploaded files; the consumer reads from here asynchronously. */
    private static final Path UPLOAD_DIR = Path.of("data", "uploads");

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "sync", defaultValue = "false") boolean sync) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String fileName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        String resolvedTitle = (title == null || title.isBlank()) ? stripExtension(fileName) : title;
        // Stage under a deterministic docId-prefixed name: re-uploads replace the staged file,
        // and CJK file names survive (only path-hostile characters are normalized).
        String docId = IngestionService.buildDocId(fileName, resolvedTitle);
        String safeName = fileName.replaceAll("[^A-Za-z0-9._\\u4e00-\\u9fff-]", "_");
        Files.createDirectories(UPLOAD_DIR);
        Path staged = UPLOAD_DIR.resolve(docId + "-" + safeName).toAbsolutePath();
        file.transferTo(staged);

        Map<String, Object> body = documentService.submitFile(staged, fileName, title, sync);
        return sync ? ResponseEntity.ok(body) : ResponseEntity.accepted().body(body);
    }

    /** Ingests pasted text; async (202) by default, {@code ?sync=true} blocks and reports. */
    @PostMapping("/text")
    public ResponseEntity<Map<String, Object>> ingestText(
            @RequestBody @Valid IngestTextRequest request,
            @RequestParam(value = "sync", defaultValue = "false") boolean sync) {
        Map<String, Object> body = documentService.submitText(request.title(), request.content(), sync);
        return sync ? ResponseEntity.ok(body) : ResponseEntity.accepted().body(body);
    }

    /** Lists documents with their state-machine status (PENDING/PROCESSING/DONE/FAILED). */
    @GetMapping
    public List<DocumentRecord> list() {
        return documentService.list();
    }

    /** Removes a document: Elasticsearch chunks and the status record. */
    @DeleteMapping("/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) {
        return documentService.delete(docId);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}

package com.zzx.docrag.ingest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Ingestion state machine record - the reason PostgreSQL exists in this project.
 *
 * <p>Lifecycle: PENDING (accepted, queued) -> PROCESSING (consumer working) ->
 * DONE (chunkCount set) | FAILED (error set, attempts recorded). A message rejected
 * after listener retries land in the DLQ while the record stays FAILED, so the API
 * tells the truth about every document even when processing died mid-flight.
 *
 * <p>The payload travels with the record (inline text or a staged file path) so a
 * redelivery or a manual requeue can reprocess without asking the client again.
 */
@Entity
@Table(name = "documents")
public class DocumentRecord {

    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @Column(length = 32)
    private String docId;

    @Column(length = 512)
    private String title;

    @Column(length = 1024)
    private String source;

    @Column(length = 16)
    private String status;

    private int chunkCount;

    @Column(length = 2000)
    private String error;

    private int attempts;

    /** Raw submitted text; never serialized to API responses. */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String inlineContent;

    @Column(length = 1024)
    private String filePath;

    private Instant createdAt;

    private Instant updatedAt;

    public DocumentRecord() {
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getInlineContent() {
        return inlineContent;
    }

    public void setInlineContent(String inlineContent) {
        this.inlineContent = inlineContent;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

package com.zzx.docrag.ingest;

/**
 * Queue payload. Carries only the docId - the state (payload, title, attempt count) lives
 * in PostgreSQL, so a redelivery can never resurrect stale content and the message stays
 * tiny no matter how large the document is.
 */
public record IngestJob(String docId) {
}

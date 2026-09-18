package com.zzx.docrag.es;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated view of one ingested document, for the document list API.
 */
public record DocInfo(
        String docId,
        String title,
        String source,
        int chunkCount,
        Instant updatedAt
) {
    public static DocInfo of(String docId, String title, String source, int chunkCount, Instant updatedAt) {
        return new DocInfo(docId, title, source, chunkCount, updatedAt);
    }

    public static List<DocInfo> empty() {
        return List.of();
    }
}

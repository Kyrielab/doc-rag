package com.zzx.docrag.es;

/**
 * One indexed chunk. {@code chunkId} is deterministic:
 * {@code docId + "#" + ordinal}, which makes re-ingestion idempotent.
 *
 * @param chunkId    stable id for this chunk
 * @param docId      id of the parent document
 * @param title      document title, duplicated here so search hits are self-describing
 * @param source     original file path or URL, used to render citations
 * @param ordinal    position of this chunk inside the document
 * @param content    chunk text
 * @param vector     dense embedding of {@code content}
 */
public record Chunk(
        String chunkId,
        String docId,
        String title,
        String source,
        int ordinal,
        String content,
        float[] vector
) {
    public static String buildChunkId(String docId, int ordinal) {
        return docId + "#" + ordinal;
    }
}

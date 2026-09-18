package com.zzx.docrag.rag;

import java.util.List;

/**
 * A source excerpt referenced by the answer.
 *
 * @param chunkId chunk id
 * @param title   document title
 * @param source  original source path or URL
 * @param excerpt excerpt of the cited chunk
 * @param rank    position of the chunk in the final ranking, 0-based
 */
public record Citation(
        String chunkId,
        String title,
        String source,
        String excerpt,
        int rank
) {
    public static List<Citation> empty() {
        return List.of();
    }
}

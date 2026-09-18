package com.zzx.docrag.es;

/**
 * A single retrieval hit, tagged with the retriever that produced it.
 * Keeping the rank per retriever is what makes fusion and offline analysis possible.
 *
 * @param chunkId    chunk id
 * @param docId      parent document id
 * @param title      document title
 * @param source     original source (file path or URL)
 * @param content    chunk text
 * @param score      raw score from the producing retriever (BM25 score or cosine similarity)
 * @param rank       0-based rank inside its own retriever's result list
 * @param retriever  which retriever produced this hit: "lexical" or "vector"
 */
public record Retrieved(
        String chunkId,
        String docId,
        String title,
        String source,
        String content,
        double score,
        int rank,
        String retriever
) {
}

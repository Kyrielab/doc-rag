package com.zzx.docrag.retrieve;

import java.util.List;

/**
 * Per-stage evidence for one query. This record is the reason the project is defensible:
 * every latency number and every retriever contribution is captured, so a claim such as
 * "reranking lifted Recall@5 from 0.62 to 0.84" can be reproduced.
 *
 * @param question          the question as asked
 * @param rewrittenQuery    query actually sent to retrieval (equals question when rewriting is off)
 * @param rewriteMillis     query-rewriting latency, 0 when rewriting did not run
 * @param lexicalHits       raw hit count from BM25
 * @param vectorHits        raw hit count from dense retrieval
 * @param fusedCount        candidates after RRF
 * @param lexicalMillis     BM25 latency
 * @param vectorMillis      dense retrieval latency
 * @param fusionMillis      fusion latency
 * @param rerankMillis      rerank latency, 0 when disabled
 * @param generateMillis    LLM latency
 * @param promptContextChars characters of retrieved content embedded in the prompt;
 *                          the cost metric for context compression
 * @param totalMillis       end-to-end latency
 * @param topChunks         final chunks handed to the LLM
 * @param retrievedChunkIds ids of those chunks, used by the evaluation harness
 * @param cacheHit          whether the answer came from cache
 * @param rerankSkipped     why reranking was skipped, empty when it ran
 */
public record RetrievalTrace(
        String question,
        String rewrittenQuery,
        long rewriteMillis,
        int lexicalHits,
        int vectorHits,
        int fusedCount,
        long lexicalMillis,
        long vectorMillis,
        long fusionMillis,
        long rerankMillis,
        long generateMillis,
        int promptContextChars,
        long totalMillis,
        List<Merged> topChunks,
        List<String> retrievedChunkIds,
        boolean cacheHit,
        String rerankSkipped
) {
}

package com.zzx.docrag.retrieve;

import java.util.List;

/**
 * Result of the retrieval stage, with per-stage latency already separated.
 * Keeping timings here rather than measuring them in the orchestrator means a retriever
 * change can be benchmarked without touching the service that calls it.
 *
 * @param chunks          final ranked chunks
 * @param lexicalHits     raw BM25 hit count
 * @param vectorHits      raw dense hit count
 * @param fusedCount      candidate count after fusion
 * @param lexicalMillis   BM25 latency
 * @param vectorMillis    dense retrieval latency
 * @param fusionMillis    fusion latency
 * @param rerankMillis    rerank latency, 0 when it did not run
 * @param rerankSkipped   reason reranking was skipped, empty when it ran
 */
public record RetrievalResult(
        List<Merged> chunks,
        int lexicalHits,
        int vectorHits,
        int fusedCount,
        long lexicalMillis,
        long vectorMillis,
        long fusionMillis,
        long rerankMillis,
        String rerankSkipped
) {
}

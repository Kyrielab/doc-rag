package com.zzx.docrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval tuning knobs. Every value here is an experiment dimension:
 * changing them should be justified by numbers from the evaluation harness,
 * not by intuition. Keep them in config so A/B runs need no recompile.
 *
 * @param chunkSize        target chunk length in characters
 * @param chunkOverlap     overlap between adjacent chunks, keeps sentence context across boundaries
 * @param defaultTopK      how many chunks are fed to the LLM when the caller does not say
 * @param candidateK       how many candidates each retriever returns before fusion / rerank
 * @param minVectorScore   cosine similarity floor for vector hits
 * @param rrfK             the k constant of Reciprocal Rank Fusion
 * @param enableVector     turn the dense retriever on/off (A/B dimension)
 * @param enableLexical    turn the BM25 retriever on/off (A/B dimension)
 * @param enableRerank     turn the cross-encoder rerank stage on/off (A/B dimension)
 * @param cacheTtlSeconds  TTL of the answer cache
 * @param lexWeight        RRF weight of the lexical retriever (A/B dimension)
 * @param vecWeight        RRF weight of the vector retriever (A/B dimension)
 * @param rerankMinCandidates skip reranking when fusion produced at most this many
 *                         candidates; 0 disables the skip. Rationale: reranking pays a
 *                         per-query latency tax, and when few candidates survive fusion the
 *                         fused order is already all there is to show - a knob for trading
 *                         precision against latency, measurable through the eval harness.
 * @param enableRewrite    rewrite the user query into search-friendly terms before retrieval
 *                         (A/B dimension; costs one auxiliary LLM call per query, fails open
 *                         to the original query on any error)
 * @param enableCompression sentence-level context compression before prompt building
 *                         (A/B dimension; trades prompt cost against answer completeness)
 * @param compressionChunkChars per-chunk character budget when compression is on
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        int chunkSize,
        int chunkOverlap,
        int defaultTopK,
        int candidateK,
        double minVectorScore,
        int rrfK,
        boolean enableVector,
        boolean enableLexical,
        boolean enableRerank,
        long cacheTtlSeconds,
        double lexWeight,
        double vecWeight,
        int rerankMinCandidates,
        boolean enableRewrite,
        boolean enableCompression,
        int compressionChunkChars
) {
    public RagProperties {
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            chunkOverlap = Math.min(80, chunkSize / 4);
        }
        if (defaultTopK <= 0) {
            defaultTopK = 8;
        }
        if (candidateK <= 0) {
            candidateK = 30;
        }
        if (rrfK <= 0) {
            rrfK = 60;
        }
        if (lexWeight <= 0) {
            lexWeight = 1.0;
        }
        if (vecWeight <= 0) {
            vecWeight = 1.0;
        }
        if (rerankMinCandidates < 0) {
            rerankMinCandidates = 0;
        }
        if (compressionChunkChars <= 0) {
            compressionChunkChars = 250;
        }
    }
}

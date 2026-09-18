package com.zzx.docrag.retrieve;

import com.zzx.docrag.es.Retrieved;

import java.util.List;
import java.util.Set;

/**
 * A chunk that survived fusion and possibly reranking.
 *
 * @param chunkId      chunk id
 * @param docId        parent document id
 * @param title        document title
 * @param source       original source
 * @param content      chunk text
 * @param fusedScore   Reciprocal Rank Fusion score
 * @param rerankScore  cross-encoder score, null when reranking is disabled
 * @param lexRank      rank in the lexical list, -1 when the lexical retriever missed it
 * @param vecRank      rank in the vector list, -1 when the vector retriever missed it
 * @param preview      short excerpt for display
 */
public record Merged(
        String chunkId,
        String docId,
        String title,
        String source,
        String content,
        double fusedScore,
        Double rerankScore,
        int lexRank,
        int vecRank,
        String preview
) {
    public static final int MISS = -1;

    public static Merged fromRetrieved(Retrieved hit, double fusedScore, int lexRank, int vecRank) {
        return new Merged(
                hit.chunkId(),
                hit.docId(),
                hit.title(),
                hit.source(),
                hit.content(),
                fusedScore,
                null,
                lexRank,
                vecRank,
                previewOf(hit.content()));
    }

    public Merged withRerankScore(double score) {
        return new Merged(chunkId, docId, title, source, content, fusedScore, score, lexRank, vecRank, preview);
    }

    /** Which retrievers found this chunk; this is the raw material for failure analysis. */
    public Set<String> foundBy() {
        Set<String> retrievers = new java.util.LinkedHashSet<>();
        if (lexRank != MISS) {
            retrievers.add("lexical");
        }
        if (vecRank != MISS) {
            retrievers.add("vector");
        }
        return retrievers;
    }

    public static String previewOf(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }

    public static List<Merged> empty() {
        return List.of();
    }

    /** Extracts chunk ids in order; the evaluation harness compares these against gold ids. */
    public static List<String> idsOf(List<Merged> merged) {
        if (merged == null || merged.isEmpty()) {
            return List.of();
        }
        return merged.stream().map(Merged::chunkId).toList();
    }
}

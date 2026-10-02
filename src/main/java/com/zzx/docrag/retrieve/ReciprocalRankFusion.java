package com.zzx.docrag.retrieve;

import com.zzx.docrag.es.Retrieved;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion.
 *
 * <p>Why RRF instead of adding the raw scores: a BM25 score and a cosine similarity live on
 * incomparable scales, and any linear combination needs per-corpus normalization that breaks
 * as soon as the corpus changes. RRF only uses ranks, so it needs no tuning when documents
 * are added, and it is what production hybrid search systems use by default.
 *
 * <p>score(chunk) = sum over retrievers of weight / (k + rank), rank starting at 1.
 * The constant k damps the influence of the top few positions; 60 is the value from the
 * original TREC paper and a reasonable default.
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    /**
     * @param lexicalHits lexical results, best first
     * @param vectorHits  vector results, best first
     * @param k           RRF damping constant
     * @param lexWeight   weight of the lexical retriever
     * @param vecWeight   weight of the vector retriever
     * @return fused candidates sorted by descending fused score
     */
    public static List<Merged> fuse(List<Retrieved> lexicalHits,
                                    List<Retrieved> vectorHits,
                                    int k,
                                    double lexWeight,
                                    double vecWeight) {
        Map<String, Accumulator> accumulators = new LinkedHashMap<>();

        for (Retrieved hit : lexicalHits) {
            Accumulator acc = accumulators.computeIfAbsent(hit.chunkId(), id -> new Accumulator(hit));
            acc.lexRank = hit.rank();
            acc.score += lexWeight / (k + hit.rank() + 1.0);
        }
        for (Retrieved hit : vectorHits) {
            Accumulator acc = accumulators.computeIfAbsent(hit.chunkId(), id -> new Accumulator(hit));
            acc.vecRank = hit.rank();
            acc.score += vecWeight / (k + hit.rank() + 1.0);
        }

        List<Merged> fused = new ArrayList<>(accumulators.size());
        for (Accumulator acc : accumulators.values()) {
            fused.add(Merged.fromRetrieved(acc.source, acc.score, acc.lexRank, acc.vecRank));
        }
        fused.sort(Comparator.comparingDouble(Merged::fusedScore).reversed());
        return fused;
    }

    /** Mutable accumulator; kept private so fusion stays a pure function from the outside. */
    private static final class Accumulator {
        private final Retrieved source;
        private double score;
        private int lexRank = Merged.MISS;
        private int vecRank = Merged.MISS;

        private Accumulator(Retrieved source) {
            this.source = source;
        }
    }

    /**
     * Single-path top-N protection against RRF consensus bias (experiment 4, network-01).
     *
     * <p>RRF rewards agreement: a chunk ranked #1 by one retriever but missed entirely by the
     * other scores below chunks with mediocre ranks in BOTH lists. When the disagreeing
     * retriever is simply wrong (vocabulary mismatch on the lexical side, embedding noise on
     * the vector side), the better answer falls out of the top-K window. This guarantees each
     * retriever's top-{@code n} hits a seat in the final selection, replacing its tail
     * (weakest fused/rerank scores). Applied AFTER rerank when reranking is on, so the
     * cross-encoder still gets to order everything else.
     *
     * @param selection   final ordered selection (already cut to topK)
     * @param fused       full fused candidate list, used to look up protected chunks' content
     * @param lexicalHits lexical results, best first
     * @param vectorHits  vector results, best first
     * @param n           protected slots per retriever; {@code <= 0} disables protection
     * @return a new list with protected chunks swapped into the tail, or {@code selection}
     *         unchanged when nothing is missing
     */
    public static List<Merged> protectSelection(List<Merged> selection,
                                                List<Merged> fused,
                                                List<Retrieved> lexicalHits,
                                                List<Retrieved> vectorHits,
                                                int n) {
        if (n <= 0 || selection == null || selection.isEmpty()) {
            return selection;
        }
        Set<String> protectedIds = new LinkedHashSet<>();
        addTopIds(lexicalHits, n, protectedIds);
        addTopIds(vectorHits, n, protectedIds);

        Set<String> present = new LinkedHashSet<>();
        for (Merged m : selection) {
            present.add(m.chunkId());
        }
        List<String> missing = new ArrayList<>();
        for (String id : protectedIds) {
            if (!present.contains(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return selection;
        }

        Map<String, Merged> byId = new LinkedHashMap<>();
        for (Merged m : fused) {
            byId.putIfAbsent(m.chunkId(), m);
        }
        List<Merged> out = new ArrayList<>(selection);
        int tail = out.size() - 1;
        // Reverse iteration so protection order survives in the output: the lexical top hit
        // ends up above the vector top hit (earlier in missing = higher final slot).
        for (int i = missing.size() - 1; i >= 0; i--) {
            Merged replacement = byId.get(missing.get(i));
            if (replacement == null || tail < 0) {
                continue;
            }
            out.set(tail--, replacement);
        }
        return out;
    }

    private static void addTopIds(List<Retrieved> hits, int n, Set<String> ids) {
        if (hits == null) {
            return;
        }
        for (int i = 0; i < n && i < hits.size(); i++) {
            ids.add(hits.get(i).chunkId());
        }
    }
}

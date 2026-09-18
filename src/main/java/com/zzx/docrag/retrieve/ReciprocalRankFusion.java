package com.zzx.docrag.retrieve;

import com.zzx.docrag.es.Retrieved;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
}

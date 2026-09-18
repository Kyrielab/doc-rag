package com.zzx.docrag.retrieve;

import com.zzx.docrag.es.Retrieved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fusion is the one piece of pure logic that everything else depends on, so it is tested
 * without any infrastructure.
 */
class ReciprocalRankFusionTest {

    private static Retrieved hit(String chunkId, int rank, double score, String retriever) {
        return new Retrieved(chunkId, "doc-1", "manual", "manual.pdf", "content of " + chunkId,
                score, rank, retriever);
    }

    @Test
    @DisplayName("a chunk found by both retrievers outranks one found by only one")
    void agreementWins() {
        List<Retrieved> lexical = List.of(
                hit("a", 0, 9.5, "lexical"),
                hit("b", 1, 8.0, "lexical"),
                hit("c", 2, 7.0, "lexical"));
        List<Retrieved> vector = List.of(
                hit("d", 0, 0.91, "vector"),
                hit("b", 1, 0.88, "vector"));

        List<Merged> fused = ReciprocalRankFusion.fuse(lexical, vector, 60, 1.0, 1.0);

        assertThat(fused).isNotEmpty();
        assertThat(fused.get(0).chunkId()).isEqualTo("b");
        assertThat(fused.get(0).foundBy()).containsExactly("lexical", "vector");
    }

    @Test
    @DisplayName("RRF blends ranks, so a huge raw score does not dominate")
    void ranksNotRawScores() {
        // 'a' has an enormous BM25 score but rank 0; 'd' is rank 0 in the vector list.
        // Both are rank 0 in their own retriever, so they must receive equal fused scores.
        List<Retrieved> lexical = List.of(hit("a", 0, 9999.0, "lexical"));
        List<Retrieved> vector = List.of(hit("d", 0, 0.99, "vector"));

        List<Merged> fused = ReciprocalRankFusion.fuse(lexical, vector, 60, 1.0, 1.0);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).fusedScore()).isEqualTo(fused.get(1).fusedScore());
    }

    @Test
    @DisplayName("weights shift the ranking without changing the candidate set")
    void weightsShiftRanking() {
        List<Retrieved> lexical = List.of(hit("a", 0, 9.0, "lexical"));
        List<Retrieved> vector = List.of(hit("b", 0, 0.95, "vector"));

        List<Merged> lexicalHeavy = ReciprocalRankFusion.fuse(lexical, vector, 60, 3.0, 1.0);
        List<Merged> vectorHeavy = ReciprocalRankFusion.fuse(lexical, vector, 60, 1.0, 3.0);

        assertThat(lexicalHeavy.get(0).chunkId()).isEqualTo("a");
        assertThat(vectorHeavy.get(0).chunkId()).isEqualTo("b");
        assertThat(lexicalHeavy).hasSameSizeAs(vectorHeavy);
    }

    @Test
    @DisplayName("missing ranks are recorded as a miss rather than zero")
    void tracksMissingRanks() {
        List<Retrieved> lexical = List.of(hit("a", 3, 5.0, "lexical"));
        List<Retrieved> vector = List.of(hit("b", 0, 0.9, "vector"));

        List<Merged> fused = ReciprocalRankFusion.fuse(lexical, vector, 60, 1.0, 1.0);

        Merged a = fused.stream().filter(m -> m.chunkId().equals("a")).findFirst().orElseThrow();
        assertThat(a.lexRank()).isEqualTo(3);
        assertThat(a.vecRank()).isEqualTo(Merged.MISS);
        assertThat(a.foundBy()).containsExactly("lexical");
    }

    @Test
    @DisplayName("empty inputs produce an empty result, not a crash")
    void handlesEmptyInputs() {
        assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(), 60, 1.0, 1.0)).isEmpty();
        assertThat(ReciprocalRankFusion.fuse(List.of(hit("a", 0, 1.0, "lexical")), List.of(), 60, 1.0, 1.0))
                .hasSize(1);
    }
}

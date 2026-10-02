package com.zzx.docrag.retrieve;

import com.zzx.docrag.es.Retrieved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Single-path top-N protection: the targeted fix for RRF consensus bias
 * (experiment 4, network-01 - a vector-path top hit crowded out by chunks
 * with mediocre agreement in both paths).
 */
class FusionProtectionTest {

    private static Retrieved hit(String id, String retriever, int rank) {
        return new Retrieved(id, "doc-1", "t", "s", "content-" + id, 1.0 - rank * 0.01, rank, retriever);
    }

    private static Merged merged(String id, double fusedScore) {
        return new Merged(id, "doc-1", "t", "s", "content-" + id, fusedScore, null,
                Merged.MISS, Merged.MISS, "content-" + id);
    }

    @Test
    @DisplayName("n <= 0 disables protection entirely")
    void disabledIsIdentity() {
        List<Merged> selection = List.of(merged("a", 0.9), merged("b", 0.5));
        List<Retrieved> vec = List.of(hit("z", "vector", 0));

        List<Merged> out = ReciprocalRankFusion.protectSelection(selection, selection, List.of(), vec, 0);

        assertThat(out).isSameAs(selection);
    }

    @Test
    @DisplayName("protected chunk already in the selection changes nothing")
    void presentChunkIsNoOp() {
        List<Merged> selection = List.of(merged("a", 0.9), merged("b", 0.5));
        List<Retrieved> lex = List.of(hit("a", "lexical", 0));

        List<Merged> out = ReciprocalRankFusion.protectSelection(selection, selection, lex, List.of(), 1);

        assertThat(Merged.idsOf(out)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a missing single-path top hit replaces the selection tail")
    void missingTopHitReplacesTail() {
        // vector's #1 (z) lost the RRF consensus game and fell out of the top-2 window
        List<Merged> fused = new ArrayList<>(List.of(
                merged("a", 0.9), merged("b", 0.5), merged("z", 0.3)));
        List<Merged> selection = fused.subList(0, 2);
        List<Retrieved> vec = List.of(hit("z", "vector", 0), hit("a", "vector", 1));

        List<Merged> out = ReciprocalRankFusion.protectSelection(selection, fused, List.of(), vec, 1);

        assertThat(Merged.idsOf(out)).containsExactly("a", "z");
    }

    @Test
    @DisplayName("both paths protected: two tail slots are swapped, strongest first")
    void bothPathsProtected() {
        List<Merged> fused = new ArrayList<>(List.of(
                merged("a", 0.9), merged("b", 0.5), merged("c", 0.4),
                merged("x", 0.3), merged("z", 0.2)));
        List<Merged> selection = fused.subList(0, 3);
        List<Retrieved> lex = List.of(hit("x", "lexical", 0));
        List<Retrieved> vec = List.of(hit("z", "vector", 0));

        List<Merged> out = ReciprocalRankFusion.protectSelection(selection, fused, lex, vec, 1);

        // tail two slots (c, b positions from the end) go to x then z; head survives
        assertThat(Merged.idsOf(out)).containsExactly("a", "x", "z");
    }

    @Test
    @DisplayName("protection never grows or shrinks the selection")
    void sizeIsPreserved() {
        List<Merged> fused = new ArrayList<>(List.of(
                merged("a", 0.9), merged("b", 0.5), merged("z", 0.3)));
        List<Merged> selection = fused.subList(0, 2);
        List<Retrieved> vec = List.of(hit("z", "vector", 0), hit("missing-from-fused", "vector", 1));

        List<Merged> out = ReciprocalRankFusion.protectSelection(selection, fused, List.of(), vec, 2);

        assertThat(out).hasSize(2);
        assertThat(Merged.idsOf(out)).containsExactly("a", "z");
    }
}

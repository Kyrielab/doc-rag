package com.zzx.docrag.rag;

import com.zzx.docrag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compression must never surprise: disabled or short content passes through untouched,
 * relevance decides what survives, and document order is preserved.
 */
class ContextCompressorTest {

    private static ContextCompressor compressor(boolean enabled, int budget) {
        RagProperties props = new RagProperties(500, 80, 8, 30, 0.35, 60, true, true, false, 600,
                1.0, 1.0, 0, false, enabled, budget, 0);
        return new ContextCompressor(props);
    }

    @Test
    @DisplayName("disabled compression returns content unchanged")
    void disabledPassesThrough() {
        String content = "a".repeat(500);
        assertThat(compressor(false, 100).compress("q", content)).isEqualTo(content);
    }

    @Test
    @DisplayName("content within budget is returned unchanged")
    void shortContentPassesThrough() {
        assertThat(compressor(true, 100).compress("q", "short content")).isEqualTo("short content");
    }

    @Test
    @DisplayName("keeps query-relevant sentences and drops filler")
    void keepsRelevantSentences() {
        String relevant = "MVCC relies on the undo log version chain.";
        String filler = "Unrelated filler sentence about weather and traffic.";
        String content = relevant + " " + filler.repeat(6);

        String out = compressor(true, 120).compress("How does MVCC use undo?", content);

        assertThat(out).isEqualTo(relevant);
    }

    @Test
    @DisplayName("CJK bigram scoring keeps the on-topic sentence")
    void cjkBigramScoring() {
        String relevant = "MVCC 通过 undo 日志构建版本链实现可重复读。";
        String filler = "今天天气不错适合出门散步遛狗。";
        String content = relevant + filler.repeat(8);

        String out = compressor(true, 60).compress("MVCC undo 版本链", content);

        assertThat(out).contains("undo").contains("版本链");
        assertThat(out).doesNotContain("天气");
    }

    @Test
    @DisplayName("kept sentences preserve original document order")
    void preservesOrder() {
        String first = "The redo log ensures crash safety.";
        String middle = "Nothing related here at all whatsoever.";
        String last = "Crash recovery replays the redo log.";
        String content = first + " " + middle.repeat(5) + " " + last;

        String out = compressor(true, 150).compress("redo log crash recovery", content);

        assertThat(out.indexOf("ensures")).isLessThan(out.indexOf("replays"));
    }

    @Test
    @DisplayName("no relevant sentence falls back to the content prefix")
    void fallsBackToPrefix() {
        String content = "x".repeat(400);

        String out = compressor(true, 100).compress("completely different topic", content);

        assertThat(out).hasSize(100);
    }

    @Test
    @DisplayName("token extraction covers CJK bigrams and latin words")
    void tokenizesMixedScript() {
        Set<String> tokens = ContextCompressor.tokens("MySQL 索引 uses B+ tree");

        assertThat(tokens).contains("mysql", "索引", "uses", "tree");
    }
}

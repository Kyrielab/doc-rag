package com.zzx.docrag.rag;

import com.zzx.docrag.config.LlmProperties;
import com.zzx.docrag.retrieve.Merged;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Citation parsing is what separates a grounded answer from a plausible one, so it is tested
 * against the messy cases a real model produces. Several cases here are pinned regressions
 * from actual qwen-plus output, not hypotheticals.
 */
class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder(
            new LlmProperties("http://localhost:11434/v1", "", "bge-m3", 1024, "qwen2.5", 0.1, 6000));

    private static Merged chunk(String id, String content) {
        return new Merged(id, "doc-1", "manual", "manual.pdf", content, 0.02, null,
                Merged.MISS, Merged.MISS, content);
    }

    @Test
    @DisplayName("letter labels resolve to the chunk that was lettered in the prompt")
    void resolvesLabels() {
        List<Merged> chunks = List.of(chunk("c0", "first"), chunk("c1", "second"));

        List<CitationRef> refs = builder.parseCitations("Torque is 120 Nm [B]. Check the seal [A].", chunks);

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).chunkId()).isEqualTo("c1");
        assertThat(refs.get(0).valid()).isTrue();
        assertThat(refs.get(1).chunkId()).isEqualTo("c0");
    }

    @Test
    @DisplayName("a bare bracketed number is an invalid citation attempt, not a citation")
    void bareNumberIsInvalidAttempt() {
        List<Merged> chunks = List.of(chunk("c0", "三、制动盘检查标准：跳动量超过0.05毫米需处理"));

        // Pinned regression: with numeric labels the model cited the document's own section
        // number ("[3]") and a strict parser scored that as zero attempts = 100% accuracy.
        List<CitationRef> refs = builder.parseCitations("跳动量超过0.05毫米需处理 [3]。", chunks);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).valid()).isFalse();
    }

    @Test
    @DisplayName("an S-prefixed numeric label is an invalid attempt - S reads as Section")
    void sPrefixedNumberIsInvalidAttempt() {
        List<Merged> chunks = List.of(chunk("c0", "content"));

        // Pinned regression: real qwen-plus output "[ S3 ]" for a single-excerpt prompt.
        List<CitationRef> refs = builder.parseCitations("需要处理[ S3 ]。", chunks);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).valid()).isFalse();
    }

    @Test
    @DisplayName("a well-formed label with stray spaces still resolves")
    void toleratesSpacedLabel() {
        List<Merged> chunks = List.of(chunk("c0", "content"));

        List<CitationRef> refs = builder.parseCitations("需要处理[ A ]。", chunks);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).valid()).isTrue();
        assertThat(refs.get(0).chunkId()).isEqualTo("c0");
    }

    @Test
    @DisplayName("an out-of-range letter is recorded as invalid instead of throwing")
    void flagsHallucinatedLabel() {
        List<Merged> chunks = List.of(chunk("c0", "first"));

        List<CitationRef> refs = builder.parseCitations("See the service schedule [G].", chunks);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).valid()).isFalse();
        assertThat(refs.get(0).chunkId()).isEmpty();
    }

    @Test
    @DisplayName("repeated citations collapse to one distinct source")
    void deduplicatesCitations() {
        List<Merged> chunks = List.of(chunk("c0", "first"), chunk("c1", "second"));

        List<CitationRef> refs = builder.parseCitations("A [A]. B [A]. C [B]. D [A].", chunks);
        List<Citation> citations = builder.distinctCitations(refs, chunks);

        assertThat(refs).hasSize(4);
        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).chunkId()).isEqualTo("c0");
        assertThat(citations.get(1).chunkId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("citation accuracy counts invalid attempts against the score")
    void computesCitationAccuracy() {
        List<Merged> chunks = List.of(chunk("c0", "first"));

        String answer = "A [A]. B [9].";
        QaAnswer qa = QaAnswer.of(answer, List.of(), builder.parseCitations(answer, chunks), false, null);

        assertThat(qa.citationAccuracy()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the refusal sentinel is detected")
    void detectsRefusal() {
        assertThat(builder.isRefusal("INSUFFICIENT_CONTEXT")).isTrue();
        assertThat(builder.isRefusal("The torque is 120 Nm [A].")).isFalse();
    }

    @Test
    @DisplayName("an answer that APPENDS the sentinel as a caveat is not a refusal")
    void appendedSentinelIsNotRefusal() {
        // Pinned regression from the 48-case eval run: the model answered fully, then
        // appended a grounding caveat ending in the sentinel. contains()-based detection
        // mislabeled 3 such cases as refusals.
        assertThat(builder.isRefusal(
                "OSI 分为七层 [A]。但 excerpts 未覆盖各层细节。INSUFFICIENT_CONTEXT")).isFalse();
    }

    @Test
    @DisplayName("a sentinel-first reply is a refusal even with trailing text")
    void sentinelFirstIsRefusal() {
        assertThat(builder.isRefusal("INSUFFICIENT_CONTEXT（语料未覆盖该主题）")).isTrue();
    }

    @Test
    @DisplayName("prompt lettering is contiguous and the valid-label whitelist is appended")
    void lettersExcerpts() {
        List<Merged> chunks = List.of(chunk("c0", "alpha"), chunk("c1", "beta"));

        String prompt = builder.userPrompt("what is alpha?", chunks);

        assertThat(prompt).contains("[A]").contains("[B]").contains("alpha").contains("beta");
        assertThat(prompt).contains("what is alpha?");
        assertThat(prompt).contains("Valid citation labels for this question: [A] [B]");
        // The whitelist must come after the excerpts (instruction recency)
        assertThat(prompt.indexOf("Valid citation labels")).isGreaterThan(prompt.indexOf("beta"));
    }

    @Test
    @DisplayName("label and index conversions round-trip past Z")
    void labelRoundTrip() {
        assertThat(PromptBuilder.label(0)).isEqualTo("A");
        assertThat(PromptBuilder.label(25)).isEqualTo("Z");
        assertThat(PromptBuilder.label(26)).isEqualTo("AA");
        for (int i = 0; i < 100; i++) {
            assertThat(PromptBuilder.letterIndex(PromptBuilder.label(i))).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("chunks beyond the context budget are dropped, not truncated mid-sentence")
    void respectsContextBudget() {
        PromptBuilder tight = new PromptBuilder(
                new LlmProperties("http://localhost:11434/v1", "", "bge-m3", 1024, "qwen2.5", 0.1, 60));
        List<Merged> chunks = List.of(chunk("c0", "a".repeat(200)), chunk("c1", "b".repeat(200)));

        String prompt = tight.userPrompt("q", chunks);

        assertThat(prompt).doesNotContain("b".repeat(200));
    }
}

package com.zzx.docrag.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frontmatter stripping is pinned by tests because its failure mode is silent corpus
 * pollution: keyword-stuffed metadata chunks that match every query (experiment 9 found
 * one sitting in a top-3 result).
 */
class IngestionFrontmatterTest {

    @Test
    @DisplayName("a well-formed YAML frontmatter block is stripped")
    void stripsWellFormedBlock() {
        String input = "---\ntitle: MySQL\ndescription: interview questions\n---\n\n## body\nreal content here.";

        String out = IngestionService.stripYamlFrontmatter(input);

        assertThat(out).doesNotContain("description");
        assertThat(out).contains("## body").contains("real content here.");
    }

    @Test
    @DisplayName("text without frontmatter is returned unchanged")
    void keepsTextWithoutFrontmatter() {
        String input = "# heading\nbody text";

        assertThat(IngestionService.stripYamlFrontmatter(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("an unterminated fence is treated as content, not swallowed")
    void keepsTextWithUnterminatedFence() {
        String input = "---\ntitle: x\nno closing fence anywhere";

        assertThat(IngestionService.stripYamlFrontmatter(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("CRLF frontmatter is stripped too")
    void handlesCrlfLineEndings() {
        String input = "---\r\ntitle: x\r\n---\r\n\r\nbody text";

        String out = IngestionService.stripYamlFrontmatter(input);

        assertThat(out).contains("body text").doesNotContain("title");
    }
}

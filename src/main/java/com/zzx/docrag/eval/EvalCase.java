package com.zzx.docrag.eval;

import java.util.List;

/**
 * One evaluation sample.
 *
 * @param id              stable case id, used in failure reports
 * @param question        question as a user would type it
 * @param expectedSources substrings that should appear in the title or source of a retrieved
 *                        chunk (document-level gold)
 * @param expectedContent distinctive substrings of the gold chunk's CONTENT (chunk-level gold).
 *                        Source-level recall alone is nearly meaningless on a small corpus:
 *                        with few documents, "some chunk of the right doc made top-K" is
 *                        almost always true - measured 1.0 for every retrieval config, even on
 *                        paraphrased questions. Content gold asks the real question: was the
 *                        specific chunk carrying the answer retrieved? Empty means unlabeled.
 * @param keyPoints       facts the answer must contain; matching is case-insensitive substring
 * @param shouldRefuse    true when the corpus genuinely does not contain the answer
 */
public record EvalCase(
        String id,
        String question,
        List<String> expectedSources,
        List<String> expectedContent,
        List<String> keyPoints,
        boolean shouldRefuse
) {
    public EvalCase {
        expectedSources = expectedSources == null ? List.of() : List.copyOf(expectedSources);
        expectedContent = expectedContent == null ? List.of() : List.copyOf(expectedContent);
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
    }
}

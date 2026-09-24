package com.zzx.docrag.eval;

import java.util.List;

/**
 * Per-case evaluation result.
 *
 * @param id             case id
 * @param recallAtK      fraction of expected sources that appeared in the top-K chunks
 *                       (document level)
 * @param hitAtK         1.0 when every expected source was retrieved, else 0.0
 * @param contentRecall  fraction of gold content phrases found in any retrieved chunk's
 *                       content (chunk level); -1 when the case has no content gold
 * @param contentHit     1.0 when ALL gold content phrases were retrieved, 0.0 when any was
 *                       missed; -1 when unlabeled
 * @param citationAccuracy fraction of citation markers that resolved to a real chunk
 * @param keyPointCoverage fraction of required facts present in the answer
 * @param refused        whether the model refused
 * @param refusalCorrect true when refusal matched {@code shouldRefuse}
 * @param totalMillis    end-to-end latency for this case
 * @param answerPreview  first part of the answer, for eyeballing failures
 * @param retrievedIds   retrieved chunk ids, for failure analysis
 */
public record EvalCaseResult(
        String id,
        double recallAtK,
        double hitAtK,
        double contentRecall,
        double contentHit,
        double citationAccuracy,
        double keyPointCoverage,
        boolean refused,
        boolean refusalCorrect,
        long totalMillis,
        String answerPreview,
        List<String> retrievedIds
) {
    /** Sentinel for "case carries no chunk-level gold labels". */
    public static final double UNLABELED = -1.0;
}

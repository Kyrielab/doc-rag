package com.zzx.docrag.eval;

import java.util.List;
import java.util.Map;

/**
 * Aggregate evaluation report for one configuration.
 *
 * <p>This record is the artifact that makes the project defensible. Running the same case set
 * under different flags produces several reports, and the delta between them is the evidence
 * behind statements like "hybrid retrieval lifted chunk-level Recall@8 from 0.72 to 0.93".
 *
 * @param label                 human label for this run, e.g. "lexical-only"
 * @param caseCount             number of cases evaluated
 * @param meanRecallAtK         mean fraction of expected sources retrieved (document level;
 *                              saturates on small corpora - see EvalCase.expectedContent)
 * @param hitRateAtK            fraction of cases where every expected source was retrieved
 * @param meanContentRecall     mean fraction of gold content phrases retrieved (chunk level),
 *                              averaged over labeled cases only
 * @param contentHitRate        fraction of labeled cases where ALL gold phrases were retrieved
 * @param meanCitationAccuracy  mean fraction of valid citation markers
 * @param meanKeyPointCoverage  mean fraction of required facts present
 * @param refusalAccuracy       fraction of cases whose refusal behaviour was correct
 * @param meanTotalMillis       mean end-to-end latency
 * @param p95TotalMillis        95th percentile end-to-end latency
 * @param settings              the retrieval configuration this run used
 * @param cases                 per-case results
 */
public record EvalReport(
        String label,
        int caseCount,
        double meanRecallAtK,
        double hitRateAtK,
        double meanContentRecall,
        double contentHitRate,
        double meanCitationAccuracy,
        double meanKeyPointCoverage,
        double refusalAccuracy,
        double meanTotalMillis,
        long p95TotalMillis,
        Map<String, Object> settings,
        List<EvalCaseResult> cases
) {
    /** Formats a report as the markdown table rows used in the README. */
    public String toMarkdownRow() {
        return "| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.0f | %d |".formatted(
                label,
                meanRecallAtK,
                meanContentRecall,
                contentHitRate,
                meanCitationAccuracy,
                meanKeyPointCoverage,
                refusalAccuracy,
                meanTotalMillis,
                p95TotalMillis);
    }

    public static String markdownHeader() {
        return """
                | config | SrcRecall@K | ContentRecall@K | ContentHit@K | Citation acc. | KeyPoint cov. | Refusal acc. | mean ms | p95 ms |
                |---|---|---|---|---|---|---|---|---|""";
    }
}

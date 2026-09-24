package com.zzx.docrag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.docrag.config.RagProperties;
import com.zzx.docrag.rag.QaAnswer;
import com.zzx.docrag.rag.RagService;
import com.zzx.docrag.retrieve.Merged;
import com.zzx.docrag.retrieve.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline evaluation harness.
 *
 * <p>Build the harness before optimizing the pipeline. Without it, every "improvement" is an
 * opinion; with it, each change is a measured delta.
 *
 * <p>Metrics
 * <ul>
 *   <li><b>Recall@K / Hit@K</b> measure retrieval. They are the most actionable numbers,
 *       because a correct answer that was never retrieved can never be generated.</li>
 *   <li><b>Citation accuracy</b> measures whether the model pointed at excerpts that exist.
 *       A fabricated citation is the clearest hallucination signal.</li>
 *   <li><b>Key point coverage</b> is a cheap, deterministic proxy for answer correctness.
 *       It is weak compared with an LLM judge, and being explicit about that weakness is
 *       better than pretending it is a correctness score.</li>
 *   <li><b>Refusal accuracy</b> checks the other failure mode: confidently answering a
 *       question the corpus cannot support.</li>
 * </ul>
 *
 * <p>The harness calls the LLM, so it costs money and time. Set {@code skipGeneration=true}
 * to evaluate retrieval only, which is free and is what you will run most often.
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final RagService ragService;
    private final RagProperties ragProperties;
    private final ObjectMapper mapper;

    public EvalService(RagService ragService, RagProperties ragProperties, ObjectMapper mapper) {
        this.ragService = ragService;
        this.ragProperties = ragProperties;
        this.mapper = mapper;
    }

    /** Loads cases from a JSONL file, one JSON object per line. */
    public List<EvalCase> loadCases(Path file) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            try {
                cases.add(mapper.readValue(trimmed, EvalCase.class));
            } catch (Exception e) {
                throw new IllegalArgumentException("Malformed eval case on line " + lineNumber + ": " + e.getMessage(), e);
            }
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("No evaluation cases found in " + file);
        }
        return cases;
    }

    /**
     * Runs the whole case set once.
     *
     * @param label          label recorded in the report
     * @param cases          cases to run
     * @param topK           retrieval depth; also the K in Recall@K
     * @param skipGeneration when true, no LLM call is made and answer-level metrics are skipped
     * @return aggregate report
     */
    public EvalReport evaluate(String label, List<EvalCase> cases, int topK, boolean skipGeneration) {
        List<EvalCaseResult> results = new ArrayList<>(cases.size());
        long startedAll = System.currentTimeMillis();

        for (EvalCase evalCase : cases) {
            long started = System.currentTimeMillis();
            try {
                results.add(evaluateCase(evalCase, topK, skipGeneration));
            } catch (RuntimeException e) {
                // One bad case must not abort a 200-case run; record it as a total miss.
                log.error("Case {} failed: {}", evalCase.id(), e.toString());
                results.add(new EvalCaseResult(
                        evalCase.id(), 0.0, 0.0,
                        EvalCaseResult.UNLABELED, EvalCaseResult.UNLABELED,
                        0.0, 0.0, false, false,
                        System.currentTimeMillis() - started, "ERROR: " + e.getMessage(), List.of()));
            }
        }

        EvalReport report = aggregate(label, results, topK, skipGeneration);
        log.info("Evaluation '{}' finished in {}ms: recall@{}={} hit@{}={} citationAcc={} keyPoints={} refusalAcc={}",
                label, System.currentTimeMillis() - startedAll, topK,
                String.format("%.3f", report.meanRecallAtK()), topK,
                String.format("%.3f", report.hitRateAtK()),
                String.format("%.3f", report.meanCitationAccuracy()),
                String.format("%.3f", report.meanKeyPointCoverage()),
                String.format("%.3f", report.refusalAccuracy()));
        return report;
    }

    private EvalCaseResult evaluateCase(EvalCase evalCase, int topK, boolean skipGeneration) {
        long started = System.currentTimeMillis();

        if (skipGeneration) {
            // Retrieval-only: zero LLM cost (embeddings aside), no answer cache. Answer-level
            // metrics are not measurable in this mode and are reported as zero; the report's
            // settings block records skipGeneration=true so the zeros are never misread.
            RetrievalResult retrieval = ragService.retrieveOnly(evalCase.question(), topK);
            List<Merged> chunks = retrieval.chunks();
            double contentRecall = contentRecallAtK(chunks, evalCase.expectedContent());
            return new EvalCaseResult(
                    evalCase.id(),
                    recallAtK(chunks, evalCase.expectedSources()),
                    hitAtK(chunks, evalCase.expectedSources()),
                    contentRecall,
                    contentHitOf(contentRecall),
                    0.0,
                    0.0,
                    false,
                    false,
                    System.currentTimeMillis() - started,
                    "(retrieval-only)",
                    Merged.idsOf(chunks));
        }

        // allowCache=false: evaluation must always exercise the live pipeline, otherwise a
        // second run within the cache TTL measures Redis instead of retrieval + generation.
        QaAnswer answer = ragService.answer(evalCase.question(), topK, false);
        List<Merged> chunks = answer.trace().topChunks();

        double recall = recallAtK(chunks, evalCase.expectedSources());
        double hit = hitAtK(chunks, evalCase.expectedSources());
        double contentRecall = contentRecallAtK(chunks, evalCase.expectedContent());
        double coverage = keyPointCoverage(answer.answer(), evalCase.keyPoints());
        boolean refused = answer.refused();
        boolean refusalCorrect = refused == evalCase.shouldRefuse();

        return new EvalCaseResult(
                evalCase.id(),
                recall,
                hit,
                contentRecall,
                contentHitOf(contentRecall),
                answer.citationAccuracy(),
                coverage,
                refused,
                refusalCorrect,
                System.currentTimeMillis() - started,
                preview(answer.answer()),
                answer.trace().retrievedChunkIds());
    }

    /**
     * Fraction of expected sources found in the retrieved chunks. Matching is a case-insensitive
     * substring test against title and source, which tolerates file-path changes and does not
     * require hand-labelling chunk ids (chunk ids shift whenever chunking settings change).
     */
    double recallAtK(List<Merged> chunks, List<String> expectedSources) {
        if (expectedSources.isEmpty()) {
            return 1.0;
        }
        long found = expectedSources.stream().filter(expected -> retrievedAny(chunks, expected)).count();
        return (double) found / expectedSources.size();
    }

    double hitAtK(List<Merged> chunks, List<String> expectedSources) {
        if (expectedSources.isEmpty()) {
            return 1.0;
        }
        return expectedSources.stream().allMatch(expected -> retrievedAny(chunks, expected)) ? 1.0 : 0.0;
    }

    private boolean retrievedAny(List<Merged> chunks, String expected) {
        String needle = expected.toLowerCase(Locale.ROOT);
        return chunks.stream().anyMatch(chunk ->
                chunk.title().toLowerCase(Locale.ROOT).contains(needle)
                        || chunk.source().toLowerCase(Locale.ROOT).contains(needle));
    }

    /**
     * Chunk-level content recall: fraction of gold phrases found inside the CONTENT of any
     * retrieved chunk. Returns {@link EvalCaseResult#UNLABELED} when the case carries no
     * content gold, so aggregates can skip it instead of diluting the mean with fake 1.0s.
     *
     * <p>Why this exists on top of source-level recall: with a handful of documents, "some
     * chunk of the right document made top-K" is nearly always true - measured 1.0 source
     * recall for lexical, vector AND hybrid on 48 cases including colloquial paraphrases.
     * Source recall saturates; content recall discriminates.
     */
    double contentRecallAtK(List<Merged> chunks, List<String> expectedContent) {
        if (expectedContent.isEmpty()) {
            return EvalCaseResult.UNLABELED;
        }
        String haystack = chunks.stream()
                .map(Merged::content)
                .reduce("", (a, b) -> a + '\n' + b)
                .toLowerCase(Locale.ROOT);
        long found = expectedContent.stream()
                .filter(phrase -> haystack.contains(phrase.toLowerCase(Locale.ROOT)))
                .count();
        return (double) found / expectedContent.size();
    }

    private double contentHitOf(double contentRecall) {
        if (contentRecall < 0) {
            return EvalCaseResult.UNLABELED;
        }
        return contentRecall >= 1.0 ? 1.0 : 0.0;
    }

    double keyPointCoverage(String answer, List<String> keyPoints) {
        if (keyPoints.isEmpty() || answer == null) {
            return 1.0;
        }
        String haystack = answer.toLowerCase(Locale.ROOT);
        long present = keyPoints.stream()
                .filter(point -> haystack.contains(point.toLowerCase(Locale.ROOT)))
                .count();
        return (double) present / keyPoints.size();
    }

    private EvalReport aggregate(String label, List<EvalCaseResult> results, int topK, boolean skipGeneration) {
        int count = results.size();
        double meanRecall = mean(results, EvalCaseResult::recallAtK);
        double hitRate = mean(results, EvalCaseResult::hitAtK);

        // Content metrics average over labeled cases only; unlabeled cases carry -1 sentinels
        // and would otherwise drag the mean down with meaningless values.
        List<EvalCaseResult> labeled = results.stream()
                .filter(r -> r.contentRecall() >= 0)
                .toList();
        double meanContentRecall = mean(labeled, EvalCaseResult::contentRecall);
        double contentHitRate = mean(labeled, EvalCaseResult::contentHit);

        double citationAccuracy = mean(results, EvalCaseResult::citationAccuracy);
        double coverage = skipGeneration ? 0.0 : mean(results, EvalCaseResult::keyPointCoverage);
        double refusalAccuracy = results.stream().filter(EvalCaseResult::refusalCorrect).count() / (double) count;
        double meanMillis = mean(results, r -> (double) r.totalMillis());

        List<Long> latencies = results.stream()
                .map(EvalCaseResult::totalMillis)
                .sorted(Comparator.naturalOrder())
                .toList();
        long p95 = latencies.isEmpty() ? 0L : latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(0.95 * latencies.size()) - 1));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("topK", topK);
        settings.put("chunkSize", ragProperties.chunkSize());
        settings.put("chunkOverlap", ragProperties.chunkOverlap());
        settings.put("candidateK", ragProperties.candidateK());
        settings.put("enableLexical", ragProperties.enableLexical());
        settings.put("enableVector", ragProperties.enableVector());
        settings.put("enableRerank", ragProperties.enableRerank());
        settings.put("minVectorScore", ragProperties.minVectorScore());
        settings.put("rrfK", ragProperties.rrfK());
        settings.put("skipGeneration", skipGeneration);
        settings.put("labeledContentCases", labeled.size());

        return new EvalReport(label, count, meanRecall, hitRate, meanContentRecall, contentHitRate,
                citationAccuracy, coverage, refusalAccuracy, meanMillis, p95, settings, results);
    }

    private double mean(List<EvalCaseResult> results, java.util.function.ToDoubleFunction<EvalCaseResult> extractor) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream().mapToDouble(extractor).average().orElse(0.0);
    }

    private String preview(String answer) {
        if (answer == null) {
            return "";
        }
        String flat = answer.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }
}

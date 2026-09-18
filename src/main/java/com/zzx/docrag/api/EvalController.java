package com.zzx.docrag.api;

import com.zzx.docrag.eval.EvalCase;
import com.zzx.docrag.eval.EvalReport;
import com.zzx.docrag.eval.EvalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Evaluation endpoints.
 *
 * <p>{@code skipGeneration=true} runs retrieval-only evaluation: no LLM calls, so it is free,
 * fast, and the right loop to use while tuning chunking and fusion weights.
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;
    private final String defaultCaseFile;

    public EvalController(EvalService evalService,
                          @Value("${eval.cases-file:eval/sample-eval.jsonl}") String defaultCaseFile) {
        this.evalService = evalService;
        this.defaultCaseFile = defaultCaseFile;
    }

    /**
     * @param label           label for this run, e.g. "baseline" or "rrf+rerank"
     * @param topK            retrieval depth, also the K in Recall@K
     * @param skipGeneration  true to evaluate retrieval only
     * @param casesFile       optional path to a case file, defaults to the bundled sample
     */
    @PostMapping("/run")
    public EvalReport run(@RequestParam(value = "label", defaultValue = "run") String label,
                          @RequestParam(value = "topK", defaultValue = "8") int topK,
                          @RequestParam(value = "skipGeneration", defaultValue = "true") boolean skipGeneration,
                          @RequestParam(value = "casesFile", required = false) String casesFile) throws IOException {
        Path path = Path.of(casesFile == null || casesFile.isBlank() ? defaultCaseFile : casesFile);
        List<EvalCase> cases = evalService.loadCases(path);
        return evalService.evaluate(label, cases, topK, skipGeneration);
    }

    /** Renders one report as the markdown table rows used in the README. */
    @PostMapping("/markdown")
    public String markdown(@RequestParam(value = "label", defaultValue = "run") String label,
                           @RequestParam(value = "topK", defaultValue = "8") int topK,
                           @RequestParam(value = "skipGeneration", defaultValue = "true") boolean skipGeneration,
                           @RequestParam(value = "casesFile", required = false) String casesFile) throws IOException {
        Path path = Path.of(casesFile == null || casesFile.isBlank() ? defaultCaseFile : casesFile);
        List<EvalCase> cases = evalService.loadCases(path);
        EvalReport report = evalService.evaluate(label, cases, topK, skipGeneration);
        return EvalReport.markdownHeader() + "\n" + report.toMarkdownRow();
    }
}

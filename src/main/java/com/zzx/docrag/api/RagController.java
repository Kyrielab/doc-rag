package com.zzx.docrag.api;

import com.zzx.docrag.rag.QaAnswer;
import com.zzx.docrag.rag.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Question answering.
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Answers a question from the ingested corpus.
     * The response carries the retrieval trace, so latency and retriever contribution are
     * visible without a separate debugging endpoint.
     */
    @PostMapping("/answer")
    public QaAnswer answer(@RequestBody @Valid AnswerRequest request) {
        return ragService.answer(request.question(), request.topK());
    }

    /** Convenience endpoint for browser checks and load testing. */
    @PostMapping("/answer/quick")
    public QaAnswer quick(@RequestParam("q") String question,
                          @RequestParam(value = "topK", required = false) Integer topK) {
        return ragService.answer(question, topK);
    }
}

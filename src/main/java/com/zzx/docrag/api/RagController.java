package com.zzx.docrag.api;

import com.zzx.docrag.rag.QaAnswer;
import com.zzx.docrag.rag.RagService;
import com.zzx.docrag.retrieve.RetrievalResult;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Question answering: blocking JSON and streaming SSE.
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

    /**
     * Retrieval-only search: no generation, no cache, zero LLM cost. This is the QPS
     * benchmark target (it isolates the retrieval pipeline from provider latency) and a
     * debugging window into exactly what the retriever sees before the LLM does.
     */
    @GetMapping("/search")
    public RetrievalResult search(@RequestParam("q") String question,
                                  @RequestParam(value = "topK", required = false) Integer topK) {
        return ragService.retrieveOnly(question, topK);
    }

    /**
     * Streaming answer over Server-Sent Events. GET (not POST) on purpose: the browser
     * EventSource API only speaks GET, and the demo UI relies on it.
     *
     * <p>Event sequence: {@code meta} (retrieval stats) -> {@code token}* (incremental text)
     * -> {@code done} (full QaAnswer JSON incl. citations, trace with ttftMillis), or
     * {@code error}. 5-minute ceiling guards against abandoned emitters.
     */
    @GetMapping(value = "/answer/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("q") String question,
                             @RequestParam(value = "topK", required = false) Integer topK) {
        SseEmitter emitter = new SseEmitter(300_000L);
        ragService.answerStreaming(question, topK, emitter);
        return emitter;
    }
}

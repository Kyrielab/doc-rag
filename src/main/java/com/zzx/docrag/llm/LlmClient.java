package com.zzx.docrag.llm;

import java.util.List;

/**
 * Chat completion port.
 */
public interface LlmClient {

    /**
     * Non-streaming completion.
     *
     * @param systemPrompt control instructions
     * @param userPrompt   the grounded question plus retrieved context
     * @return generated answer text
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Streaming completion. Tokens are pushed to {@code onToken} as they arrive,
     * which is what makes time-to-first-token a measurable, optimizable metric.
     *
     * @return the full concatenated answer
     */
    String stream(String systemPrompt, String userPrompt, List<String> stopSequences, TokenConsumer onToken);

    /** Receives incremental output. */
    @FunctionalInterface
    interface TokenConsumer {
        void accept(String token);
    }
}

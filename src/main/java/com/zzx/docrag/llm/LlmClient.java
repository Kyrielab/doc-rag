package com.zzx.docrag.llm;

import java.util.List;

/**
 * Chat completion port.
 */
public interface LlmClient {

    /**
     * Non-streaming completion against the configured chat model.
     *
     * @param systemPrompt control instructions
     * @param userPrompt   the grounded question plus retrieved context
     * @return generated text with provider-reported token usage
     */
    Completion complete(String systemPrompt, String userPrompt);

    /**
     * Non-streaming completion against an explicit model. Auxiliary tasks such as query
     * rewriting often run better on a cheaper, faster model than the main generation model,
     * so the model id must not be hard-wired to {@code chatModel}.
     *
     * @param model        model id to call
     * @param systemPrompt control instructions
     * @param userPrompt   task input
     * @return generated text with provider-reported token usage
     */
    Completion complete(String model, String systemPrompt, String userPrompt);

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

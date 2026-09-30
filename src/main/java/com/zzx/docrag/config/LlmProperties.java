package com.zzx.docrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Both the embedding endpoint and the chat endpoint are expected to speak the
 * OpenAI-compatible protocol, so providers stay swappable through config only.
 *
 * @param baseUrl            e.g. https://api.deepseek.com/v1 or http://localhost:11434/v1
 * @param apiKey             bearer token; empty string means the provider needs none
 * @param embeddingModel     model id used for document and query vectors
 * @param embeddingDimension must match the dense_vector mapping in Elasticsearch
 * @param chatModel          model id used for answer generation
 * @param temperature        low values keep answers grounded in the retrieved context
 * @param maxContextChars    hard cap on context injected into the prompt, controls cost
 * @param rewriteModel       model id for auxiliary query rewriting; blank falls back to
 *                           chatModel. Rewriting is a short, formulaic task, so a cheaper
 *                           and faster model (e.g. qwen-turbo) is usually the better buy.
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String embeddingModel,
        int embeddingDimension,
        String chatModel,
        double temperature,
        int maxContextChars,
        String rewriteModel
) {
    public LlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com/v1";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (rewriteModel == null) {
            rewriteModel = "";
        }
        if (embeddingDimension <= 0) {
            embeddingDimension = 1536;
        }
        if (temperature < 0) {
            temperature = 0.1;
        }
        if (maxContextChars <= 0) {
            maxContextChars = 6000;
        }
    }
}

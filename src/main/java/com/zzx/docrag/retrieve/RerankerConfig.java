package com.zzx.docrag.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.docrag.config.LlmProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link Reranker} implementation by {@code rerank.provider}, and only when
 * {@code rag.enable-rerank=true}.
 *
 * <p>A factory bean instead of two conditionally-annotated components: with two providers a
 * compound condition (enabled AND provider) would need SpEL on every implementation, and a
 * mis-set combination could register both beans and break {@code ObjectProvider.getIfAvailable()}
 * with a NoUniqueBeanDefinitionException. One factory makes "exactly one reranker or none"
 * a structural guarantee.
 */
@Configuration
public class RerankerConfig {

    @Bean
    @ConditionalOnProperty(name = "rag.enable-rerank", havingValue = "true")
    public Reranker reranker(@Value("${rerank.provider:jina}") String provider,
                             @Value("${rerank.base-url:}") String baseUrl,
                             @Value("${rerank.model:}") String model,
                             @Value("${rerank.api-key:}") String apiKey,
                             LlmProperties llmProperties,
                             ObjectMapper mapper) {
        if ("dashscope".equalsIgnoreCase(provider)) {
            String effectiveModel = model.isBlank() ? "gte-rerank-v2" : model;
            // Same Bailian account key as chat/embeddings unless a dedicated one is configured.
            String effectiveKey = apiKey.isBlank() ? llmProperties.apiKey() : apiKey;
            return new DashScopeReranker(effectiveKey, effectiveModel, baseUrl, mapper);
        }
        String effectiveUrl = baseUrl.isBlank() ? "http://localhost:8081" : baseUrl;
        String effectiveModel = model.isBlank() ? "BAAI/bge-reranker-v2-m3" : model;
        return new HttpReranker(effectiveUrl, effectiveModel, apiKey, mapper);
    }
}

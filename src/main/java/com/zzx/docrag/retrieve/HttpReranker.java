package com.zzx.docrag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reranking against a Jina / TEI compatible {@code /rerank} endpoint
 * (for example text-embeddings-inference serving BAAI/bge-reranker-v2-m3).
 *
 * <p>Instantiated by {@link RerankerConfig} when {@code rag.enable-rerank=true} and
 * {@code rerank.provider} is not {@code dashscope}, so the pipeline can run without
 * a rerank server and the A/B comparison is a config flip rather than a code change.
 *
 * <p>Failure policy is fail-open: if the rerank server is down we keep the fused order and
 * log it. A degraded answer beats a 500, and the trace records that reranking was skipped.
 */
public class HttpReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(HttpReranker.class);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String model;

    public HttpReranker(String baseUrl, String model, String apiKey, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.client = builder.build();
    }

    @Override
    public List<Merged> rerank(String query, List<Merged> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return Merged.empty();
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        body.put("top_n", Math.min(topN, candidates.size()));
        ArrayNode documents = body.putArray("documents");
        candidates.forEach(candidate -> documents.add(candidate.content()));

        try {
            String raw = client.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode results = mapper.readTree(raw).path("results");
            List<Merged> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }
                reranked.add(candidates.get(index).withRerankScore(result.path("relevance_score").asDouble()));
            }
            reranked.sort(Comparator.comparingDouble(
                    (Merged m) -> m.rerankScore() == null ? Double.NEGATIVE_INFINITY : m.rerankScore()).reversed());
            if (reranked.size() > topN) {
                return reranked.subList(0, topN);
            }
            return reranked;
        } catch (Exception e) {
            log.warn("Rerank call failed, falling back to fused order: {}", e.toString());
            return candidates.size() > topN ? candidates.subList(0, topN) : candidates;
        }
    }

    @Override
    public String modelName() {
        return model;
    }
}

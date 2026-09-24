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
 * Reranking against Aliyun Bailian's (DashScope) native text-rerank API, model
 * {@code gte-rerank-v2} by default.
 *
 * <p>Why a dedicated adapter instead of reusing {@link HttpReranker}: DashScope's rerank
 * endpoint is NOT part of its OpenAI-compatible surface. The request nests documents under
 * {@code input} and the response nests scored results under {@code output.results} with
 * {@code relevance_score} - a different wire contract from the Jina-style {@code /rerank}
 * API. One class per wire contract keeps both honest and independently testable.
 *
 * <p>Verified wire format (2026-09-24, live call):
 * <pre>
 * POST /api/v1/services/rerank/text-rerank/text-rerank
 * {"model":"gte-rerank-v2",
 *  "input":{"query":"...","documents":["...","..."]},
 *  "parameters":{"return_documents":false,"top_n":2}}
 * -> {"output":{"results":[{"index":0,"relevance_score":0.95},...]}, "usage":{...}}
 * </pre>
 *
 * <p>Same fail-open policy as {@link HttpReranker}: rerank outage degrades to the fused
 * order with a warning, never a 500.
 */
public class DashScopeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(DashScopeReranker.class);
    private static final String DEFAULT_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final RestClient client;
    private final String url;
    private final ObjectMapper mapper;
    private final String model;

    public DashScopeReranker(String apiKey, String model, String url, ObjectMapper mapper) {
        this.model = model;
        this.url = (url == null || url.isBlank()) ? DEFAULT_URL : url;
        this.mapper = mapper;
        RestClient.Builder builder = RestClient.builder();
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
        int requested = Math.min(topN, candidates.size());

        ObjectNode input = mapper.createObjectNode();
        input.put("query", query);
        ArrayNode documents = input.putArray("documents");
        candidates.forEach(candidate -> documents.add(candidate.content()));

        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("return_documents", false);
        parameters.put("top_n", requested);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("input", input);
        body.set("parameters", parameters);

        try {
            String raw = client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode results = mapper.readTree(raw).path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new IllegalStateException("rerank response carried no results");
            }
            List<Merged> reranked = new ArrayList<>(results.size());
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }
                reranked.add(candidates.get(index).withRerankScore(result.path("relevance_score").asDouble()));
            }
            reranked.sort(Comparator.comparingDouble(
                    (Merged m) -> m.rerankScore() == null ? Double.NEGATIVE_INFINITY : m.rerankScore()).reversed());
            return reranked.size() > requested ? reranked.subList(0, requested) : reranked;
        } catch (Exception e) {
            // Fail-open: keep the fused order, record the reason in the log.
            log.warn("DashScope rerank failed, falling back to fused order: {}", e.toString());
            return candidates.size() > requested ? candidates.subList(0, requested) : candidates;
        }
    }

    @Override
    public String modelName() {
        return model;
    }
}

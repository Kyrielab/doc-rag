package com.zzx.docrag.api;

import com.zzx.docrag.config.LlmProperties;
import com.zzx.docrag.config.RagProperties;
import com.zzx.docrag.llm.EmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostics endpoint. First thing to call when something looks wrong: it separates
 * "infrastructure is down" from "the pipeline misbehaves", which are very different bugs.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RestClient restClient;
    private final StringRedisTemplate redis;
    private final RagProperties ragProperties;
    private final LlmProperties llmProperties;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper mapper;

    public AdminController(RestClient restClient,
                           StringRedisTemplate redis,
                           RagProperties ragProperties,
                           LlmProperties llmProperties,
                           EmbeddingClient embeddingClient,
                           ObjectMapper mapper) {
        this.restClient = restClient;
        this.redis = redis;
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.embeddingClient = embeddingClient;
        this.mapper = mapper;
    }

    /** Reports connectivity and effective configuration. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("elasticsearch", probeElasticsearch());
        status.put("redis", probeRedis());
        status.put("llmBaseUrl", llmProperties.baseUrl());
        status.put("llmApiKeyConfigured", !llmProperties.apiKey().isBlank());
        status.put("chatModel", llmProperties.chatModel());
        status.put("embeddingModel", embeddingClient.modelName());
        status.put("embeddingDimension", embeddingClient.dimension());

        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("defaultTopK", ragProperties.defaultTopK());
        retrieval.put("candidateK", ragProperties.candidateK());
        retrieval.put("chunkSize", ragProperties.chunkSize());
        retrieval.put("chunkOverlap", ragProperties.chunkOverlap());
        retrieval.put("rrfK", ragProperties.rrfK());
        retrieval.put("minVectorScore", ragProperties.minVectorScore());
        retrieval.put("enableLexical", ragProperties.enableLexical());
        retrieval.put("enableVector", ragProperties.enableVector());
        retrieval.put("enableRerank", ragProperties.enableRerank());
        status.put("retrieval", retrieval);
        return status;
    }

    private Map<String, Object> probeElasticsearch() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Response response = restClient.performRequest(new Request("GET", "/_cluster/health"));
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            result.put("reachable", true);
            result.put("health", mapper.readTree(body).path("status").asText("unknown"));
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    private Map<String, Object> probeRedis() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            redis.opsForValue().get("docrag:ping");
            result.put("reachable", true);
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }
}

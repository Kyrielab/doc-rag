package com.zzx.docrag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzx.docrag.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Single OpenAI-compatible client for embeddings and chat completions.
 *
 * <p>Why hand-rolled HTTP instead of a vendor SDK: the wire format is 40 lines of JSON,
 * and owning it keeps providers interchangeable through configuration, makes retry and
 * timeout policy explicit, and removes a dependency that changes shape every release.
 *
 * <p>Streaming uses {@code java.net.http.HttpClient} because SSE needs incremental body
 * reading, which {@code RestClient} does not model well.
 */
@Component
public class OpenAiCompatibleClient implements EmbeddingClient, LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final HttpClient httpClient;

    public OpenAiCompatibleClient(LlmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.restClient = buildRestClient(properties);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static RestClient buildRestClient(LlmProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
        String apiKey = properties.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private boolean hasApiKey() {
        return properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    // ------------------------------------------------------------------ embeddings

    @Override
    public float[] embed(String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", properties.embeddingModel());
        body.put("input", text);
        body.put("encoding_format", "float");

        String raw = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);

        JsonNode embedding = readTree(raw).path("data").path(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding response contained no vector: " + abbreviate(raw));
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        if (vector.length != properties.embeddingDimension()) {
            throw new IllegalStateException(
                    "Embedding dimension mismatch: model returned " + vector.length
                            + " but llm.embedding-dimension is " + properties.embeddingDimension()
                            + ". Align the config, then recreate the index because dims cannot be changed in place.");
        }
        return l2Normalize(vector);
    }

    @Override
    public String modelName() {
        return properties.embeddingModel();
    }

    @Override
    public int dimension() {
        return properties.embeddingDimension();
    }

    /**
     * L2-normalizing here means cosine similarity reduces to a dot product downstream,
     * and it keeps scores comparable across documents of different lengths.
     */
    private float[] l2Normalize(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) {
            return vector;
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }

    // ------------------------------------------------------------------ completion

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return complete(properties.chatModel(), systemPrompt, userPrompt);
    }

    @Override
    public String complete(String model, String systemPrompt, String userPrompt) {
        ObjectNode body = buildChatBody(model, systemPrompt, userPrompt, false);
        String raw = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
        JsonNode root = readTree(raw);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isEmpty()) {
            throw new IllegalStateException("Chat completion returned empty content: " + abbreviate(raw));
        }
        logUsage(root);
        return content;
    }

    @Override
    public String stream(String systemPrompt, String userPrompt, List<String> stopSequences, TokenConsumer onToken) {
        ObjectNode body = buildChatBody(properties.chatModel(), systemPrompt, userPrompt, true);
        if (stopSequences != null && !stopSequences.isEmpty()) {
            ArrayNode stops = body.putArray("stop");
            stopSequences.forEach(stops::add);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
        if (hasApiKey()) {
            requestBuilder.header("Authorization", "Bearer " + properties.apiKey());
        }
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        StringBuilder answer = new StringBuilder();
        try {
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300) {
                String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("Streaming request failed with HTTP "
                        + response.statusCode() + ": " + abbreviate(error));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JsonNode chunk = readTree(payload);
                    String token = chunk.path("choices").path(0).path("delta").path("content").asText("");
                    if (!token.isEmpty()) {
                        answer.append(token);
                        if (onToken != null) {
                            onToken.accept(token);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Streaming call interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Streaming call failed", e);
        }
        return answer.toString();
    }

    private ObjectNode buildChatBody(String model, String systemPrompt, String userPrompt, boolean stream) {
        ObjectNode systemMessage = mapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        ArrayNode messages = mapper.createArrayNode();
        messages.add(systemMessage);
        messages.add(userMessage);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", properties.temperature());
        body.put("stream", stream);
        body.set("messages", messages);
        return body;
    }

    private void logUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode()) {
            log.debug("token usage prompt={} completion={} total={}",
                    usage.path("prompt_tokens").asInt(),
                    usage.path("completion_tokens").asInt(),
                    usage.path("total_tokens").asInt());
        }
    }

    /** Extracts prompt/completion token counts; used by the cost accounting endpoint. */
    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed JSON from LLM provider: " + abbreviate(json), e);
        }
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}

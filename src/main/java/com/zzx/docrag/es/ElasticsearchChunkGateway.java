package com.zzx.docrag.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzx.docrag.config.LlmProperties;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch-backed implementation of {@link ChunkGateway}.
 *
 * <p>Design notes worth defending in an interview:
 * <ul>
 *   <li>One engine serves both retrievers. BM25 and dense vectors live in the same index,
 *       so hybrid retrieval needs no cross-store join and no dual-write consistency problem.</li>
 *   <li>The mapping is created explicitly rather than inferred, because the analyzer and the
 *       dense_vector dimension are correctness-critical: a wrong dimension only fails at
 *       query time.</li>
 *   <li>Dense retrieval uses {@code script_score} with cosine similarity rather than the
 *       {@code knn} option, because the vectors are already L2-normalized and script_score
 *       lets us apply a similarity floor directly.</li>
 * </ul>
 */
@Component
public class ElasticsearchChunkGateway implements ChunkGateway {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchChunkGateway.class);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String index;
    private final String analyzer;
    private final int dimension;

    public ElasticsearchChunkGateway(RestClient client,
                                     ObjectMapper mapper,
                                     @Value("${elasticsearch.index:doc_chunks}") String index,
                                     @Value("${elasticsearch.analyzer:standard}") String analyzer,
                                     LlmProperties llmProperties) {
        this.client = client;
        this.mapper = mapper;
        this.index = index;
        this.analyzer = analyzer;
        this.dimension = llmProperties.embeddingDimension();
    }

    @Override
    public void ensureIndex() {
        try {
            Response exists = client.performRequest(new Request("HEAD", "/" + index));
            if (exists.getStatusLine().getStatusCode() == 200) {
                log.info("Elasticsearch index [{}] already exists", index);
                return;
            }
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != 404) {
                throw new IllegalStateException("Failed to probe index " + index, e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Elasticsearch is unreachable; start it with docker compose up -d", e);
        }

        ObjectNode mappings = buildMapping();
        ObjectNode body = mapper.createObjectNode();
        body.set("mappings", mappings);
        ObjectNode settings = body.putObject("settings");
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);

        Request request = new Request("PUT", "/" + index);
        request.setJsonEntity(body.toString());
        try {
            client.performRequest(request);
            log.info("Created Elasticsearch index [{}] with analyzer [{}] and dim [{}]", index, analyzer, dimension);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create index " + index, e);
        }
    }

    private ObjectNode buildMapping() {
        ObjectNode properties = mapper.createObjectNode();

        ObjectNode content = properties.putObject("content");
        content.put("type", "text");
        content.put("analyzer", analyzer);
        content.put("search_analyzer", analyzer);

        ObjectNode title = properties.putObject("title");
        title.put("type", "text");
        title.put("analyzer", analyzer);
        title.put("search_analyzer", analyzer);
        // Keyword sub-field for aggregations: a terms agg directly on an analyzed text field
        // fails at query time (fielddata disabled). That is a runtime 500 the first time
        // listDocuments is called, not a startup error - caught by a live run, not by tests.
        ObjectNode titleFields = title.putObject("fields");
        titleFields.putObject("keyword").put("type", "keyword").put("ignore_above", 512);

        properties.putObject("source").put("type", "keyword");
        properties.putObject("docId").put("type", "keyword");
        properties.putObject("chunkId").put("type", "keyword");
        properties.putObject("ordinal").put("type", "integer");
        properties.putObject("updatedAt").put("type", "date");

        ObjectNode vector = properties.putObject("vector");
        vector.put("type", "dense_vector");
        vector.put("dims", dimension);
        vector.put("index", false); // script_score scans; set true only if you switch to the knn option

        ObjectNode mappings = mapper.createObjectNode();
        mappings.set("properties", properties);
        return mappings;
    }

    @Override
    public void index(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        StringBuilder bulk = new StringBuilder();
        String updatedAt = Instant.now().toString();
        for (Chunk chunk : chunks) {
            ObjectNode action = mapper.createObjectNode();
            ObjectNode meta = action.putObject("index");
            meta.put("_index", index);
            meta.put("_id", chunk.chunkId());
            bulk.append(action.toString()).append('\n');

            ObjectNode doc = mapper.createObjectNode();
            doc.put("chunkId", chunk.chunkId());
            doc.put("docId", chunk.docId());
            doc.put("title", chunk.title());
            doc.put("source", chunk.source());
            doc.put("ordinal", chunk.ordinal());
            doc.put("content", chunk.content());
            doc.put("updatedAt", updatedAt);
            ArrayNode vector = doc.putArray("vector");
            for (float v : chunk.vector()) {
                vector.add(v);
            }
            bulk.append(doc.toString()).append('\n');
        }
        Request request = new Request("POST", "/_bulk");
        request.addParameter("refresh", "true");
        request.setJsonEntity(bulk.toString());
        try {
            Response response = client.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(responseBody);
            if (node.path("errors").asBoolean(false)) {
                // Surface the first failure instead of a generic message: bulk responses name
                // the offending document and the reason.
                String firstError = firstBulkError(node);
                log.error("Bulk indexing reported errors: {}", abbreviate(responseBody));
                throw new IllegalStateException("Bulk indexing failed: " + firstError);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Bulk indexing request failed", e);
        }
    }

    @Override
    public int deleteByDocId(String docId) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode term = mapper.createObjectNode();
        term.put("docId", docId);
        body.set("query", mapper.createObjectNode().set("term", term));

        Request request = new Request("POST", "/" + index + "/_delete_by_query");
        request.addParameter("refresh", "true");
        request.addParameter("conflicts", "proceed");
        request.setJsonEntity(body.toString());
        try {
            Response response = client.performRequest(request);
            JsonNode node = mapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
            return node.path("deleted").asInt(0);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return 0;
            }
            throw new IllegalStateException("delete_by_query failed for doc " + docId, e);
        } catch (IOException e) {
            throw new IllegalStateException("delete_by_query failed for doc " + docId, e);
        }
    }

    @Override
    public List<Retrieved> lexicalSearch(String query, int topK) {
        ObjectNode multiMatch = mapper.createObjectNode();
        multiMatch.put("query", query);
        ArrayNode fields = multiMatch.putArray("fields");
        fields.add("title^2");
        fields.add("content");

        ObjectNode body = mapper.createObjectNode();
        body.put("size", topK);
        body.set("query", mapper.createObjectNode().set("multi_match", multiMatch));
        body.set("_source", sourceIncludes());

        Request request = new Request("POST", "/" + index + "/_search");
        request.setJsonEntity(body.toString());
        return executeSearch(request, "lexical");
    }

    @Override
    public List<Retrieved> vectorSearch(float[] queryVector, int topK, double minScore) {
        // Cosine similarity with "script_score" keeps this readable and lets us floor the score.
        // If latency ever becomes the bottleneck, switch to the "knn" option in the search body.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("queryVector", queryVector);

        ObjectNode body = mapper.createObjectNode();
        body.put("size", topK);
        body.put("min_score", minScore);

        ObjectNode script = mapper.createObjectNode();
        script.put("source", "cosineSimilarity(params.queryVector, 'vector') + 1.0");
        script.set("params", mapper.valueToTree(params));

        ObjectNode scriptScore = mapper.createObjectNode();
        scriptScore.set("script", script);

        // The inner query must be a real clause: script_score wraps a query, and an empty
        // object here is a 400 ("empty clause found"), not a match-everything.
        ObjectNode matchAllQuery = mapper.createObjectNode();
        matchAllQuery.set("match_all", mapper.createObjectNode());
        scriptScore.set("query", matchAllQuery);
        ObjectNode query = mapper.createObjectNode();
        query.set("script_score", scriptScore);

        body.set("query", query);
        body.set("_source", sourceIncludes());

        Request request = new Request("POST", "/" + index + "/_search");
        request.setJsonEntity(body.toString());
        return executeSearch(request, "vector");
    }

    private ArrayNode sourceIncludes() {
        ArrayNode includes = mapper.createArrayNode();
        includes.add("chunkId");
        includes.add("docId");
        includes.add("title");
        includes.add("source");
        includes.add("content");
        includes.add("ordinal");
        return includes;
    }

    /**
     * Shared response handling. This is the only place that knows the Elasticsearch
     * hit shape, so a mapping change touches exactly one method.
     */
    private List<Retrieved> executeSearch(Request request, String retriever) {
        List<Retrieved> results = new ArrayList<>();
        try {
            Response response = client.performRequest(request);
            JsonNode root = mapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
            JsonNode hits = root.path("hits").path("hits");
            int rank = 0;
            for (JsonNode hit : hits) {
                JsonNode src = hit.path("_source");
                results.add(new Retrieved(
                        src.path("chunkId").asText(),
                        src.path("docId").asText(),
                        src.path("title").asText(),
                        src.path("source").asText(),
                        src.path("content").asText(),
                        hit.path("_score").asDouble(),
                        rank++,
                        retriever
                ));
            }
        } catch (ResponseException e) {
            // Surface the Elasticsearch error body: it names the failing clause and reason,
            // which is what actually makes a query bug diagnosable.
            String errorBody = "";
            try {
                errorBody = EntityUtils.toString(e.getResponse().getEntity(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // best effort; the status line alone is still useful
            }
            throw new IllegalStateException("Search failed on retriever " + retriever
                    + " (HTTP " + e.getResponse().getStatusLine().getStatusCode() + "): "
                    + abbreviate(errorBody), e);
        } catch (IOException e) {
            throw new IllegalStateException("Search request failed on retriever " + retriever, e);
        }
        return results;
    }

    @Override
    public List<Chunk> findByIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        ObjectNode body = mapper.createObjectNode();
        ArrayNode ids = body.putArray("ids");
        chunkIds.forEach(ids::add);

        Request request = new Request("POST", "/" + index + "/_mget");
        request.setJsonEntity(body.toString());

        Map<String, Chunk> byId = new LinkedHashMap<>();
        try {
            Response response = client.performRequest(request);
            JsonNode docs = mapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)).path("docs");
            for (JsonNode doc : docs) {
                if (!doc.path("found").asBoolean(false)) {
                    continue;
                }
                JsonNode src = doc.path("_source");
                byId.put(src.path("chunkId").asText(), new Chunk(
                        src.path("chunkId").asText(),
                        src.path("docId").asText(),
                        src.path("title").asText(),
                        src.path("source").asText(),
                        src.path("ordinal").asInt(),
                        src.path("content").asText(),
                        null));
            }
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return List.of();
            }
            throw new IllegalStateException("mget failed", e);
        } catch (IOException e) {
            throw new IllegalStateException("mget failed", e);
        }

        // Rebuild in the caller's order; Elasticsearch does not guarantee it for ids arrays.
        List<Chunk> ordered = new ArrayList<>(byId.size());
        for (String id : chunkIds) {
            Chunk chunk = byId.get(id);
            if (chunk != null) {
                ordered.add(chunk);
            }
        }
        return ordered;
    }

    @Override
    public List<DocInfo> listDocuments(int limit) {
        ObjectNode terms = mapper.createObjectNode();
        terms.put("field", "docId");
        terms.put("size", limit);

        ObjectNode titleAgg = mapper.createObjectNode();
        titleAgg.put("field", "title.keyword");
        titleAgg.put("size", 1);

        ObjectNode sourceAgg = mapper.createObjectNode();
        sourceAgg.put("field", "source");
        sourceAgg.put("size", 1);

        ObjectNode updatedAgg = mapper.createObjectNode();
        updatedAgg.put("field", "updatedAt");
        updatedAgg.put("size", 1);

        ObjectNode aggs = mapper.createObjectNode();
        aggs.set("title", mapper.createObjectNode().set("terms", titleAgg));
        aggs.set("source", mapper.createObjectNode().set("terms", sourceAgg));
        aggs.set("updatedAt", mapper.createObjectNode().set("terms", updatedAgg));

        ObjectNode docsAgg = mapper.createObjectNode();
        docsAgg.set("terms", terms);
        docsAgg.set("aggs", aggs);

        ObjectNode body = mapper.createObjectNode();
        body.put("size", 0);
        ObjectNode aggsRoot = mapper.createObjectNode();
        aggsRoot.set("docs", docsAgg);
        body.set("aggs", aggsRoot);

        Request request = new Request("POST", "/" + index + "/_search");
        request.setJsonEntity(body.toString());

        List<DocInfo> documents = new ArrayList<>();
        try {
            Response response = client.performRequest(request);
            JsonNode root = mapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
            JsonNode buckets = root.path("aggregations").path("docs").path("buckets");
            for (JsonNode bucket : buckets) {
                documents.add(DocInfo.of(
                        bucket.path("key").asText(),
                        firstKey(bucket.path("title").path("buckets")),
                        firstKey(bucket.path("source").path("buckets")),
                        bucket.path("doc_count").asInt(),
                        parseInstant(firstKey(bucket.path("updatedAt").path("buckets")))
                ));
            }
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return DocInfo.empty();
            }
            throw new IllegalStateException("Document aggregation failed", e);
        } catch (IOException e) {
            throw new IllegalStateException("Document aggregation failed", e);
        }
        return documents;
    }

    private String firstKey(JsonNode buckets) {
        if (buckets.isArray() && !buckets.isEmpty()) {
            return buckets.get(0).path("key").asText("");
        }
        return "";
    }

    private Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Pulls the first failed item out of a bulk response so the error message is actionable. */
    private String firstBulkError(JsonNode bulkResponse) {
        for (JsonNode item : bulkResponse.path("items")) {
            JsonNode indexResult = item.path("index");
            if (indexResult.path("error").isMissingNode()) {
                continue;
            }
            return "id=" + indexResult.path("_id").asText("?")
                    + " type=" + indexResult.path("error").path("type").asText("?")
                    + " reason=" + indexResult.path("error").path("reason").asText("?");
        }
        return "unknown bulk error";
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }
}

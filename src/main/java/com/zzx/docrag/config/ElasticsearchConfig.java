package com.zzx.docrag.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * A single low-level Elasticsearch REST client shared by the index initializer and the
 * gateway. We use the plain REST client on purpose: no repository abstraction, so the
 * exact query DSL that reaches the cluster is visible in our own code.
 *
 * <p>There is deliberately NO custom ObjectMapper bean here. This class used to define
 * {@code new ObjectMapper()}, which silently REPLACED Spring Boot's auto-configured mapper
 * (the one carrying JavaTimeModule and the Boot JSON defaults). Everything worked until an
 * endpoint tried to serialize a java.time.Instant field and the bare mapper threw - a 500 on
 * GET /api/documents, found by the week-6 burst test. Lesson: never redefine a
 * framework-provided bean without a reason; injection points should consume Boot's mapper.
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(
            @Value("${elasticsearch.uris}") String uris,
            @Value("${elasticsearch.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${elasticsearch.socket-timeout-ms:30000}") int socketTimeoutMs) {
        HttpHost[] hosts = Arrays.stream(uris.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
        if (hosts.length == 0) {
            throw new IllegalStateException("elasticsearch.uris must contain at least one host");
        }
        return RestClient.builder(hosts)
                .setRequestConfigCallback(cfg -> cfg
                        .setConnectTimeout(connectTimeoutMs)
                        .setSocketTimeout(socketTimeoutMs))
                .build();
    }
}

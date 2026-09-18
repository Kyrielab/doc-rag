package com.zzx.docrag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}

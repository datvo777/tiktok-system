package com.shortvideo.search.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A plain HTTP client against OpenSearch's REST API (brief section 19,
 * Milestone 7). No dedicated OpenSearch SDK: the handful of operations this
 * module needs (create index, versioned upsert, versioned delete, a match
 * query) are simple enough as raw JSON over HTTP, and staying on
 * {@link RestClient} avoids pulling in a new client library and its own
 * transitive version-compatibility surface for a local MVP.
 */
@Configuration
class OpenSearchConfig {

    @Bean
    RestClient openSearchClient(@Value("${shortvideo.opensearch.endpoint}") String endpoint) {
        return RestClient.builder().baseUrl(endpoint).build();
    }
}

package com.shortvideo.search.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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

    /**
     * Timeouts are explicit because the JDK client has none by default: a hung OpenSearch
     * would otherwise hold a request thread (search) or a listener thread (indexing)
     * indefinitely, instead of failing into the 503 / redelivery paths built for it.
     */
    @Bean
    RestClient openSearchClient(
            @Value("${shortvideo.opensearch.endpoint}") String endpoint,
            @Value("${shortvideo.opensearch.connect-timeout:2s}") Duration connectTimeout,
            @Value("${shortvideo.opensearch.read-timeout:5s}") Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(endpoint).requestFactory(requestFactory).build();
    }
}

package com.shortvideo.search.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Talks to OpenSearch's REST API directly (brief section 19, Milestone 7).
 * Every write uses {@code version_type=external} keyed on the publication
 * aggregate's own version: OpenSearch itself rejects a write whose version is
 * not strictly greater than what is stored, which is exactly the acceptance
 * criterion "older index versions cannot overwrite newer documents" -- no
 * extra guard logic needed here beyond passing the version through.
 *
 * <p>A search-side outage or a version conflict never throws back into an
 * upload or playback code path: nothing on those paths calls this class.
 * Indexing runs only from an independent Kafka consumer, so a slow or down
 * OpenSearch delays index freshness, never upload or playback (Milestone 7
 * acceptance criterion).
 */
@Component
public class SearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);
    private static final String INDEX = "videos";

    private final RestClient client;

    public SearchIndexService(RestClient openSearchClient) {
        this.client = openSearchClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ensureIndex() {
        Map<String, Object> mapping = Map.of(
                "mappings", Map.of(
                        "properties", Map.of(
                                "videoId", Map.of("type", "keyword"),
                                "creatorId", Map.of("type", "keyword"),
                                "creatorDisplayName", Map.of("type", "text"),
                                "publishedAt", Map.of("type", "date"))));
        HttpStatusCode status = client.put()
                .uri("/{index}", INDEX)
                .body(mapping)
                .exchange((request, resp) -> resp.getStatusCode());
        if (status.is2xxSuccessful()) {
            log.info("OpenSearch index '{}' created", INDEX);
        } else if (status.value() == 400) {
            log.info("OpenSearch index '{}' already exists", INDEX);
        } else {
            log.warn("Unexpected status creating OpenSearch index '{}': {}", INDEX, status);
        }
    }

    /** Best-effort, version-guarded upsert. A 409 means a newer version already won -- not an error. */
    public void indexVideo(String videoId, String creatorId, String creatorDisplayName, String publishedAt, long version) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("videoId", videoId);
        doc.put("creatorId", creatorId);
        doc.put("creatorDisplayName", creatorDisplayName);
        doc.put("publishedAt", publishedAt);
        write("PUT", videoId, version, doc);
    }

    /** Best-effort, version-guarded removal. A 404 (already gone) or 409 (stale) is not an error. */
    public void removeVideo(String videoId, long version) {
        write("DELETE", videoId, version, null);
    }

    /** Matches indexed videos by creator display name. Never touches upload or playback. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String query, int limit) {
        Map<String, Object> body = Map.of(
                "query", Map.of("match", Map.of("creatorDisplayName", query)),
                "size", limit);
        Map<String, Object> response = client.post()
                .uri("/{index}/_search", INDEX)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            return List.of();
        }
        Map<String, Object> hitsOuter = (Map<String, Object>) response.get("hits");
        List<Map<String, Object>> hits = (List<Map<String, Object>>) hitsOuter.get("hits");
        return hits.stream().map(h -> (Map<String, Object>) h.get("_source")).toList();
    }

    private void write(String method, String videoId, long version, Map<String, Object> doc) {
        try {
            HttpStatusCode status = "DELETE".equals(method)
                    ? client.delete()
                            .uri("/{index}/_doc/{id}?version={v}&version_type=external", INDEX, videoId, version)
                            .exchange((request, resp) -> resp.getStatusCode())
                    : client.put()
                            .uri("/{index}/_doc/{id}?version={v}&version_type=external", INDEX, videoId, version)
                            .body(doc)
                            .exchange((request, resp) -> resp.getStatusCode());
            if (status.is2xxSuccessful()) {
                return;
            }
            if (status.value() == 409 || (status.value() == 404 && "DELETE".equals(method))) {
                log.debug("OpenSearch write for {} was superseded or already absent (status {})", videoId, status.value());
                return;
            }
            log.warn("Unexpected OpenSearch status {} indexing/removing {}", status, videoId);
        } catch (Exception e) {
            // Rule: search outage never blocks upload or playback -- this consumer
            // simply retries on redelivery once OpenSearch recovers (Rule 5's inbox
            // dedup makes a later successful retry safe).
            log.warn("OpenSearch request failed for {}; will retry on redelivery", videoId, e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
}

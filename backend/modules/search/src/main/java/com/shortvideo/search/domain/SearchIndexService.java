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
    /** Set once the index is known to exist, so the lazy retry stops probing. */
    private volatile boolean indexReady;

    public SearchIndexService(RestClient openSearchClient) {
        this.client = openSearchClient;
    }

    /**
     * An exception thrown from an {@code ApplicationReadyEvent} listener propagates
     * out of {@code SpringApplication.run} and terminates the process. Left
     * unguarded, a slow or absent OpenSearch at boot took upload, playback,
     * moderation and the feed down with it — the exact coupling this class's
     * contract promises does not exist. Failure is logged and retried lazily
     * instead.
     */
    @EventListener(ApplicationReadyEvent.class)
    void ensureIndexOnStartup() {
        if (!tryEnsureIndex()) {
            log.warn(
                    "OpenSearch index '{}' could not be prepared at startup; search will retry on first use. "
                            + "Indexing and search are degraded until then; nothing else is affected.",
                    INDEX);
        }
    }

    /** @return true when the index is known to exist. */
    private boolean tryEnsureIndex() {
        if (indexReady) {
            return true;
        }
        Map<String, Object> mapping = Map.of(
                "mappings", Map.of(
                        "properties", Map.of(
                                "videoId", Map.of("type", "keyword"),
                                "creatorId", Map.of("type", "keyword"),
                                "creatorDisplayName", Map.of("type", "text"),
                                "publishedAt", Map.of("type", "date"))));
        try {
            HttpStatusCode status = client.put()
                    .uri("/{index}", INDEX)
                    .body(mapping)
                    .exchange((request, resp) -> resp.getStatusCode());
            if (status.is2xxSuccessful()) {
                log.info("OpenSearch index '{}' created", INDEX);
                indexReady = true;
            } else if (status.value() == 400) {
                log.info("OpenSearch index '{}' already exists", INDEX);
                indexReady = true;
            } else {
                log.warn("Unexpected status creating OpenSearch index '{}': {}", INDEX, status);
            }
        } catch (RuntimeException e) {
            log.warn("Could not reach OpenSearch to prepare index '{}': {}", INDEX, e.getMessage());
        }
        return indexReady;
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

    /**
     * Matches indexed videos by creator display name. Never touches upload or
     * playback.
     *
     * <p>{@code query} is a bound value inside a structured {@code match} clause,
     * not concatenated into a query string, so there is no query-DSL injection here.
     *
     * <p>A search-side outage answers 503 rather than 500: it is a dependency being
     * unavailable, not this service failing, and the distinction is what tells a
     * client to retry. The response shape is also navigated defensively — an
     * unexpected body should produce no results, not a {@code NullPointerException}
     * rendered as an internal error.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String query, int limit) {
        tryEnsureIndex();
        Map<String, Object> body = Map.of(
                "query", Map.of("match", Map.of("creatorDisplayName", query)),
                "size", limit);
        Map<String, Object> response;
        try {
            response = client.post()
                    .uri("/{index}/_search", INDEX)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            log.warn("OpenSearch query failed: {}", e.getMessage());
            throw new SearchUnavailableException("Search is temporarily unavailable", e);
        }
        if (!(response instanceof Map) || !(response.get("hits") instanceof Map<?, ?> hitsOuter)) {
            return List.of();
        }
        if (!(hitsOuter.get("hits") instanceof List<?> hits)) {
            return List.of();
        }
        return hits.stream()
                .filter(Map.class::isInstance)
                .map(h -> ((Map<String, Object>) h).get("_source"))
                .filter(Map.class::isInstance)
                .map(source -> (Map<String, Object>) source)
                .toList();
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

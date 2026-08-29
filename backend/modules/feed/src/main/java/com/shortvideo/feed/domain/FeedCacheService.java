package com.shortvideo.feed.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Feed pages cached in Redis with a short TTL (brief section 15). This is a
 * derived read model, not authoritative state (Rule 2) — a cache miss or Redis
 * outage just means recomputing the page, never a wrong answer.
 */
@Component
class FeedCacheService {

    private static final Logger log = LoggerFactory.getLogger(FeedCacheService.class);
    private static final TypeReference<List<FeedItemView>> LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FeedProperties properties;

    FeedCacheService(StringRedisTemplate redis, ObjectMapper objectMapper, FeedProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    List<FeedItemView> get(String viewerId, int page) {
        try {
            String json = redis.opsForValue().get(key(viewerId, page));
            return json == null ? null : objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            log.debug("Feed cache read failed; recomputing: {}", e.getMessage());
            return null;
        }
    }

    void put(String viewerId, int page, List<FeedItemView> items) {
        try {
            redis.opsForValue().set(key(viewerId, page), objectMapper.writeValueAsString(items), properties.getCacheTtl());
        } catch (Exception e) {
            log.debug("Feed cache write failed; page will be recomputed next time: {}", e.getMessage());
        }
    }

    private String key(String viewerId, int page) {
        return "feed:" + viewerId + ":" + page;
    }
}

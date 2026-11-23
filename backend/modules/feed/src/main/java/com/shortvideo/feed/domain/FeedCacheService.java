package com.shortvideo.feed.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
    // A plain JSON array of ids, not an array of objects: an object per video repeats its field name
    // 200 times in every key. A key written in the older object format fails to parse, which reads as
    // a miss and is recomputed, so no migration is needed.
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    FeedCacheService(FeedCacheRedis cacheRedis, ObjectMapper objectMapper) {
        this.redis = cacheRedis.template();
        this.objectMapper = objectMapper;
    }

    /**
     * The whole ranking is cached under one key per viewer, not one key per page.
     * Caching page slices separately let two pages of the same feed come from two
     * different rankings, which is how a video could show up on both page 0 and
     * page 1 — or on neither.
     *
     * <p>Only {@link FeedRef}s are stored. A signed-in viewer's ranking lives for the
     * session TTL, so the memory it holds is that key size times every viewer active
     * in that window; carrying the title and description made each key tens of
     * kilobytes, up to a megabyte with full-length descriptions.
     */
    List<FeedRef> getRanking(String viewerId) {
        try {
            String json = redis.opsForValue().get(key(viewerId));
            return json == null ? null : decode(json);
        } catch (Exception e) {
            log.debug("Feed cache read failed; recomputing: {}", e.getMessage());
            return null;
        }
    }

    void putRanking(String viewerId, List<FeedRef> ranking, Duration ttl) {
        try {
            redis.opsForValue().set(key(viewerId), encode(ranking), ttl);
        } catch (Exception e) {
            log.debug("Feed cache write failed; ranking will be recomputed next time: {}", e.getMessage());
        }
    }

    String encode(List<FeedRef> ranking) throws com.fasterxml.jackson.core.JsonProcessingException {
        return objectMapper.writeValueAsString(ranking.stream().map(FeedRef::videoId).toList());
    }

    List<FeedRef> decode(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        return objectMapper.readValue(json, LIST_TYPE).stream().map(FeedRef::new).toList();
    }

    private String key(String viewerId) {
        return "feed:ranking:" + viewerId;
    }
}

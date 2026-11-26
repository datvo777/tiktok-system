package com.shortvideo.feed.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Feed pages cached in Redis with a short TTL (brief section 15). This is a
 * derived read model, not authoritative state (Rule 2) — a cache miss or Redis
 * outage just means recomputing the page, never a wrong answer.
 *
 * <p>Because a failure is swallowed, a broken cache shows only as a slower feed and a busier
 * database. Every read is therefore counted by its outcome and every failed write is counted, which
 * is what the {@code FeedCacheFailing} alert watches.
 */
@Component
class FeedCacheService {

    private static final Logger log = LoggerFactory.getLogger(FeedCacheService.class);
    // A plain JSON array of ids, not an array of objects: an object per video repeats its field name
    // 200 times in every key. A key written in the older object format fails to parse, which reads as
    // a miss and is recomputed, so no migration is needed.
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private static final long WARN_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    // Registered up front, so each series exists at zero and its first failure registers as an increase.
    private final Counter hits;
    private final Counter misses;
    private final Counter readErrors;
    private final Counter writeErrors;
    private final AtomicLong lastWarning = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

    FeedCacheService(FeedCacheRedis cacheRedis, ObjectMapper objectMapper, MeterRegistry meters) {
        this.redis = cacheRedis.template();
        this.objectMapper = objectMapper;
        this.hits = reads(meters, "hit");
        this.misses = reads(meters, "miss");
        this.readErrors = reads(meters, "error");
        this.writeErrors = Counter.builder("feed.cache.write.errors")
                .description("Rankings that could not be written to the feed cache")
                .register(meters);
    }

    private static Counter reads(MeterRegistry meters, String result) {
        return Counter.builder("feed.cache.reads")
                .description("Reads of a cached feed ranking, by outcome; error covers Redis and unreadable values")
                .tag("result", result)
                .register(meters);
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
            if (json == null) {
                misses.increment();
                return null;
            }
            List<FeedRef> ranking = decode(json);
            hits.increment();
            return ranking;
        } catch (Exception e) {
            readErrors.increment();
            warn("read", e);
            return null;
        }
    }

    void putRanking(String viewerId, List<FeedRef> ranking, Duration ttl) {
        try {
            redis.opsForValue().set(key(viewerId), encode(ranking), ttl);
        } catch (Exception e) {
            writeErrors.increment();
            warn("write", e);
        }
    }

    /**
     * At most one warning a minute: during an outage every feed request fails here, and the counters
     * already carry how often.
     */
    private void warn(String operation, Exception e) {
        long now = System.nanoTime();
        long last = lastWarning.get();
        if (now - last >= WARN_INTERVAL_NANOS && lastWarning.compareAndSet(last, now)) {
            log.warn("Feed cache {} failed; the ranking is recomputed from PostgreSQL: {}", operation, e.toString());
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

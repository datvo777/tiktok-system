package com.shortvideo.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The cache swallows its failures, so these counters are the only way an outage is visible: each
 * outcome has to land on its own series, and a failure must still come back as a miss.
 */
class FeedCacheServiceMetricsTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FeedCacheService cache = cache();

    private FeedCacheService cache() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenReturn(values);
        FeedCacheRedis redis = mock(FeedCacheRedis.class);
        when(redis.template()).thenReturn(template);
        return new FeedCacheService(redis, new ObjectMapper(), meters);
    }

    @Test
    void everySeriesExistsAtZeroBeforeAnythingHappens() {
        assertThat(reads("hit")).isZero();
        assertThat(reads("miss")).isZero();
        assertThat(reads("error")).isZero();
        assertThat(writeErrors()).isZero();
    }

    @Test
    void aStoredRankingIsAHitAndAnAbsentOneAMiss() {
        String id = UUID.randomUUID().toString();
        when(values.get("feed:ranking:stored")).thenReturn("[\"" + id + "\"]");

        assertThat(cache.getRanking("stored")).containsExactly(new FeedRef(id));
        assertThat(cache.getRanking("absent")).isNull();

        assertThat(reads("hit")).isEqualTo(1);
        assertThat(reads("miss")).isEqualTo(1);
        assertThat(reads("error")).isZero();
    }

    @Test
    void anUnreadableValueAndAnUnreachableRedisAreBothReadErrorsAndReadAsAbsent() {
        when(values.get("feed:ranking:corrupt")).thenReturn("{not json");
        when(values.get("feed:ranking:down")).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.getRanking("corrupt")).isNull();
        assertThat(cache.getRanking("down")).isNull();

        assertThat(reads("error")).isEqualTo(2);
        assertThat(reads("hit")).isZero();
        assertThat(reads("miss")).isZero();
    }

    @Test
    void aFailedWriteIsCountedAndDoesNotReachTheCaller() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));

        cache.putRanking("viewer", List.of(), Duration.ofSeconds(30));

        assertThat(writeErrors()).isEqualTo(1);
    }

    private double reads(String result) {
        return meters.get("feed.cache.reads").tag("result", result).counter().count();
    }

    private double writeErrors() {
        return meters.get("feed.cache.write.errors").counter().count();
    }
}

package com.shortvideo.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * What a cached ranking looks like in Redis. A signed-in viewer holds one key for the whole session
 * TTL, so its size times the viewers opening the feed is the memory the cache needs; the load test
 * measured about 20 KB per key when each video carried an object with two ids.
 */
class FeedCacheServiceCodecTest {

    private final FeedCacheService cache = new FeedCacheService(mock(FeedCacheRedis.class), new ObjectMapper());

    @Test
    void aRankingRoundTripsInOrder() throws Exception {
        List<FeedRef> ranking = IntStream.range(0, 200)
                .mapToObj(i -> new FeedRef(UUID.randomUUID().toString()))
                .toList();

        assertThat(cache.decode(cache.encode(ranking))).containsExactlyElementsOf(ranking);
    }

    @Test
    void aTwoHundredVideoRankingIsAPlainArrayOfIdsAboutHalfTheSizeOfTheOldFormat() throws Exception {
        List<FeedRef> ranking = IntStream.range(0, 200)
                .mapToObj(i -> new FeedRef(UUID.randomUUID().toString()))
                .toList();

        String json = cache.encode(ranking);

        assertThat(json).startsWith("[\"").doesNotContain("videoId").doesNotContain("creatorId");
        // 200 x (36-char id + quotes + comma) plus the brackets. The old format was ~20.5 KB.
        assertThat(json.length()).isLessThan(8_500);
    }

    @Test
    void aKeyWrittenInTheOlderObjectFormatIsNotParsedSoItReadsAsAMiss() {
        String old = "[{\"videoId\":\"" + UUID.randomUUID() + "\",\"creatorId\":\"" + UUID.randomUUID() + "\"}]";

        assertThatThrownBy(() -> cache.decode(old)).isInstanceOf(JsonProcessingException.class);
    }
}

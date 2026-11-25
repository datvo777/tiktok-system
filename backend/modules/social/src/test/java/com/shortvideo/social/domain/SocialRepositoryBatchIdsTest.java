package com.shortvideo.social.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

import com.shortvideo.social.api.EngagementCounts;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * The feed asks these lookups about a whole candidate pool at once, with ids read from a
 * VARCHAR(36) column. One id that is not a uuid must not take the lookup, and with it every
 * viewer's ranking, down.
 */
class SocialRepositoryBatchIdsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SocialRepository repository = new SocialRepository(jdbc);

    @Test
    void parsingLeavesOutWhatIsNotAUuidAndKeepsTheRest() {
        UUID good = UUID.randomUUID();

        UUID[] parsed = SocialRepository.parseUuids(List.of("not-a-uuid", good.toString(), "", "x".repeat(36)));

        assertThat(parsed).containsExactly(good);
    }

    @Test
    void aMalformedCandidateIdDoesNotFailTheBatchLookups() {
        String good = UUID.randomUUID().toString();
        String viewer = UUID.randomUUID().toString();

        assertThatCode(() -> repository.viewedAmong(viewer, List.of(good, "garbage"))).doesNotThrowAnyException();
        assertThatCode(() -> repository.followedAmong(viewer, List.of(good, "garbage"))).doesNotThrowAnyException();
        assertThatCode(() -> repository.engagementForAll(List.of(good, "garbage"))).doesNotThrowAnyException();
    }

    @Test
    void aMalformedIdHasZeroEngagementRatherThanNone() {
        String good = UUID.randomUUID().toString();

        Map<String, EngagementCounts> counts = repository.engagementForAll(List.of(good, "garbage"));

        assertThat(counts.get("garbage")).isEqualTo(new EngagementCounts("garbage", 0, 0));
        assertThat(counts.get(good)).isEqualTo(new EngagementCounts(good, 0, 0));
    }
}

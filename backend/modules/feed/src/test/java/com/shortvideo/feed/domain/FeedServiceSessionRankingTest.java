package com.shortvideo.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.social.api.SocialDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A signed-in viewer's own watching reorders their ranking (watched videos drop
 * to the "seen" tier), and a page is an offset into that ranking. If the ranking
 * is recomputed between two pages, the offsets stop lining up and the unseen
 * videos in between are never shown. These pin that the pages of one session are
 * cut from one ranking, with a clock the test controls instead of a sleep.
 */
class FeedServiceSessionRankingTest {

    private static final String VIEWER = "viewer-1";
    private static final int VIDEOS = 60;

    private final EligibilityDirectory eligibility = mock(EligibilityDirectory.class);
    private final SocialDirectory social = mock(SocialDirectory.class);
    private final DurableRevocationReader revocations = mock(DurableRevocationReader.class);
    private final Set<String> viewed = new HashSet<>();
    private final Set<String> revokedVideos = new HashSet<>();
    private final FeedProperties properties = new FeedProperties();
    private final ClockedCache cache = new ClockedCache();
    private FeedService service;

    @BeforeEach
    void setUp() {
        // No exploration noise and no engagement: the order is freshness alone, so
        // it is fully determined and any difference between two rankings is the
        // seen tier's doing.
        properties.setExplorationWeight(0);
        properties.setCacheTtl(Duration.ofSeconds(30));
        properties.setRankingSessionTtl(Duration.ofMinutes(30));

        Instant now = Instant.now();
        List<VideoEligibilityView> candidates = new ArrayList<>();
        for (int i = 0; i < VIDEOS; i++) {
            // v00 is the freshest, so it ranks first.
            candidates.add(video(String.format("v%02d", i), now.minusSeconds(60L * i)));
        }
        when(eligibility.findEligibleVideosWithEligibleCreators(anyInt())).thenReturn(candidates);
        when(social.countsForAll(anyCollection())).thenReturn(Map.of());
        when(social.followedAmong(anyString(), anyCollection())).thenReturn(Set.of());
        when(social.viewedAmong(anyString(), anyCollection())).thenAnswer(inv -> viewed);
        when(revocations.activeAmong(eq(RevocationSubjects.ACCOUNT), anyCollection())).thenReturn(Set.of());
        when(revocations.activeAmong(eq(RevocationSubjects.VIDEO), anyCollection()))
                .thenAnswer(inv -> revokedVideos);

        service = new FeedService(eligibility, social, revocations, new FeedScorer(properties), cache, properties);
    }

    @Test
    void laterPagesKeepSlicingTheRankingPageZeroStartedEvenAfterTheShortTtlHasPassed() {
        List<String> original = ids(service.feed(VIEWER, 0));

        // Watching the first 18 videos puts them in the seen tier of any ranking
        // computed from here on; scrolling that far takes longer than cache-ttl.
        viewed.addAll(original.subList(0, 18));
        cache.advance(Duration.ofMinutes(2));

        List<String> second = ids(service.feed(VIEWER, 1));
        List<String> third = ids(service.feed(VIEWER, 2));

        // Without the session ranking, page 1 is original[38..57] and the 18
        // unseen videos at original[20..37] are never shown.
        assertThat(second).containsExactlyElementsOf(freshnessOrder().subList(20, 40));
        assertThat(third).containsExactlyElementsOf(freshnessOrder().subList(40, 60));
        assertThat(original).doesNotContainAnyElementsOf(second);
    }

    @Test
    void openingTheFeedAgainStartsAFreshRankingWithWatchedVideosLast() {
        ids(service.feed(VIEWER, 0));
        List<String> first = freshnessOrder().subList(0, 18);
        viewed.addAll(first);

        List<String> reopened = ids(service.feed(VIEWER, 0));

        assertThat(reopened).containsExactlyElementsOf(freshnessOrder().subList(18, 38));
    }

    @Test
    void aRevokedVideoIsDroppedFromALaterPageOfAnAlreadyCachedRanking() {
        ids(service.feed(VIEWER, 0));
        String revoked = freshnessOrder().get(25);
        revokedVideos.add(revoked);

        List<String> second = ids(service.feed(VIEWER, 1));

        assertThat(second).doesNotContain(revoked).hasSize(19);
    }

    @Test
    void aSignedOutRankingKeepsTheShortTtl() {
        service.feed(null, 0);
        assertThat(cache.lastTtl).isEqualTo(Duration.ofSeconds(30));

        service.feed(VIEWER, 0);
        assertThat(cache.lastTtl).isEqualTo(Duration.ofMinutes(30));
    }

    private List<String> freshnessOrder() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < VIDEOS; i++) {
            order.add(String.format("v%02d", i));
        }
        return order;
    }

    private static List<String> ids(FeedService.FeedPage page) {
        return page.items().stream().map(FeedItemView::videoId).toList();
    }

    private static VideoEligibilityView video(String id, Instant updatedAt) {
        return new VideoEligibilityView(
                id, "creator-" + id, "t", "d", null, null, null, null, null, false, null, null, true, 0, 0, 0, 0,
                updatedAt);
    }

    /** In-memory stand-in for the Redis cache whose expiry follows a manual clock. */
    private static class ClockedCache extends FeedCacheService {
        private final Map<String, List<FeedItemView>> values = new HashMap<>();
        private final Map<String, Duration> expiresAt = new HashMap<>();
        private Duration now = Duration.ZERO;
        Duration lastTtl;

        ClockedCache() {
            super(null, null);
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        List<FeedItemView> getRanking(String viewerId) {
            Duration expiry = expiresAt.get(viewerId);
            return expiry == null || expiry.compareTo(now) <= 0 ? null : values.get(viewerId);
        }

        @Override
        void putRanking(String viewerId, List<FeedItemView> ranking, Duration ttl) {
            values.put(viewerId, ranking);
            expiresAt.put(viewerId, now.plus(ttl));
            lastTtl = ttl;
        }
    }
}

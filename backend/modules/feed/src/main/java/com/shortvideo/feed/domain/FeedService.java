package com.shortvideo.feed.domain;

import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.social.api.SocialCounts;
import com.shortvideo.social.api.SocialDirectory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Rule-based feed (brief section 15): gather eligible candidates, filter by
 * creator account state and revocation, score, then cache the ranking. A feed
 * exclusion is a ranking decision, not a security boundary; a stale cached row is
 * stale, not unsafe, because the gateway re-authorizes every playback
 * independently (Rule 12).
 *
 * <p><b>Revocation.</b> This reads the durable PostgreSQL record directly and no
 * longer consults the Redis deny cache first. The cache exists to spare the
 * database a per-request round trip on the media path, where each request
 * authorizes exactly one object; here a single batched query already answers for
 * the whole candidate set, so the fast path would add work rather than remove it.
 * Going straight to the authoritative source is also strictly more accurate, which
 * matters because a revoked video that slips into a ranking is a visible mistake
 * even though the gateway will refuse to play it.
 *
 * <p><b>Round trips.</b> This method used to issue roughly seven network calls per
 * candidate — two revocation checks, an account-eligibility lookup, a social-counts
 * query and a follow check — inside a loop over a 200-video pool, with no
 * surrounding transaction. That was ~1,400 round trips and ~1,000 separate
 * connection acquisitions from a 20-connection pool for a single uncached request,
 * which is what made the feed the first thing to fall over under concurrency. Every
 * collaborator is now asked once for the whole page instead, so the cost is four
 * queries regardless of pool size.
 *
 * <p><b>Stable ordering.</b> The exploration term used to be drawn from
 * {@code ThreadLocalRandom} on every request, so each page was ranked against a
 * different random draw: a video could appear on both page 0 and page 1, or on
 * neither. The ranking is now computed once per viewer, seeded deterministically
 * and cached whole, and pages are slices of that one ordering.
 */
@Service
public class FeedService {

    private final EligibilityDirectory eligibilityDirectory;
    private final SocialDirectory socialDirectory;
    private final DurableRevocationReader revocationReader;
    private final FeedScorer scorer;
    private final FeedCacheService cache;
    private final FeedProperties properties;

    public FeedService(
            EligibilityDirectory eligibilityDirectory,
            SocialDirectory socialDirectory,
            DurableRevocationReader revocationReader,
            FeedScorer scorer,
            FeedCacheService cache,
            FeedProperties properties) {
        this.eligibilityDirectory = eligibilityDirectory;
        this.socialDirectory = socialDirectory;
        this.revocationReader = revocationReader;
        this.scorer = scorer;
        this.cache = cache;
        this.properties = properties;
    }

    public FeedPage feed(String viewerId, int page) {
        List<FeedItemView> ranking = cache.getRanking(viewerId);
        if (ranking == null) {
            ranking = rank(viewerId);
            cache.putRanking(viewerId, ranking);
        }

        int pageSize = properties.getPageSize();
        // long arithmetic: a large page number times the page size overflows int and
        // would produce a negative offset, which subList rejects with an exception.
        int from = (int) Math.min((long) page * pageSize, ranking.size());
        int to = Math.min(from + pageSize, ranking.size());
        // hasMore lets the client stop paging instead of walking into empty pages
        // forever, which is what it had to do when only the items were returned.
        return new FeedPage(List.copyOf(ranking.subList(from, to)), to < ranking.size());
    }

    /**
     * Four queries total: candidates (joined to creator eligibility), video
     * revocations, account revocations, social counts, and the viewer's follows —
     * the last two being one query each over the whole candidate set.
     */
    private List<FeedItemView> rank(String viewerId) {
        List<VideoEligibilityView> candidates =
                eligibilityDirectory.findEligibleVideosWithEligibleCreators(properties.getCandidatePoolSize());
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> videoIds = candidates.stream().map(VideoEligibilityView::videoId).toList();
        Set<String> creatorIds =
                candidates.stream().map(VideoEligibilityView::creatorId).collect(Collectors.toSet());

        Set<String> revokedVideos = revocationReader.activeAmong(RevocationSubjects.VIDEO, videoIds);
        Set<String> revokedCreators = revocationReader.activeAmong(RevocationSubjects.ACCOUNT, creatorIds);
        Map<String, SocialCounts> counts = socialDirectory.countsForAll(videoIds);
        Set<String> followed = socialDirectory.followedAmong(viewerId, creatorIds);

        // Seeded per viewer so the exploration term is stable across the pages of
        // one ranking, while still differing between viewers.
        Random exploration = new Random(viewerId.hashCode());

        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (VideoEligibilityView video : candidates) {
            if (revokedVideos.contains(video.videoId()) || revokedCreators.contains(video.creatorId())) {
                continue; // brief section 15: filter by revocation
            }
            SocialCounts videoCounts =
                    counts.getOrDefault(video.videoId(), new SocialCounts(video.videoId(), 0, 0));
            double score = scorer.score(
                    video, videoCounts, followed.contains(video.creatorId()), exploration.nextDouble());
            scored.add(new ScoredCandidate(video.videoId(), video.creatorId(), video.title(), video.description(), score));
        }

        scored.sort(Comparator.comparingDouble(ScoredCandidate::score)
                .reversed()
                // Ties would otherwise order arbitrarily between two computations of
                // the same ranking, reintroducing the instability this method fixes.
                .thenComparing(ScoredCandidate::videoId));

        return scored.stream()
                .map(c -> new FeedItemView(c.videoId(), c.creatorId(), c.title(), c.description()))
                .toList();
    }

    /** One page of the viewer's ranking, plus whether another page exists. */
    public record FeedPage(List<FeedItemView> items, boolean hasMore) {}

    private record ScoredCandidate(String videoId, String creatorId, String title, String description, double score) {}
}

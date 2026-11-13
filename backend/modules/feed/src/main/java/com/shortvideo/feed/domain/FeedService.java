package com.shortvideo.feed.domain;

import com.shortvideo.eligibility.api.AccountEligibilityView;
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
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * collaborator is now asked once for the whole page instead, so the cost is a fixed
 * handful of queries (see {@link #rank}) regardless of pool size.
 *
 * <p><b>Stable ordering.</b> The exploration term used to be drawn from
 * {@code ThreadLocalRandom} on every request, so each page was ranked against a
 * different random draw: a video could appear on both page 0 and page 1, or on
 * neither. The ranking is now computed once per viewer, seeded deterministically
 * and cached whole, and pages are slices of that one ordering. A signed-in
 * viewer's ranking is also frozen for the length of a session (page 0 starts it),
 * because their own watching reorders it.
 */
@Service
public class FeedService {

    private final EligibilityDirectory eligibilityDirectory;
    private final SocialDirectory socialDirectory;
    private final DurableRevocationReader revocationReader;
    private final FeedScorer scorer;
    private final FeedCacheService cache;
    private final FeedProperties properties;
    private final ConcurrentMap<String, CompletableFuture<List<FeedItemView>>> inFlight = new ConcurrentHashMap<>();

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
        return feed(viewerId, page, Scope.FOR_YOU);
    }

    /**
     * @param scope FOR_YOU ranks everything; FOLLOWING keeps only creators the
     *     viewer follows. Following was implemented — you could follow, and it
     *     boosted a creator in the ranking — but there was nowhere to see the
     *     result of having followed anyone.
     */
    public FeedPage feed(String viewerId, int page, Scope scope) {
        // Every signed-out viewer shares one cached ranking. They are
        // indistinguishable by construction -- no follows, no watch history -- so
        // computing a separate one per browser would produce the same list at N
        // times the cost.
        String identity = viewerId == null ? ANONYMOUS : viewerId;
        String cacheKey = scope == Scope.FOLLOWING ? identity + ":following" : identity;

        // A signed-in viewer's ranking depends on what they have watched, and
        // watching moves videos to the "seen" tier at the end. If a later page were
        // cut from a ranking recomputed after that, the offsets would no longer line
        // up with what page 0 showed and the unseen videos in between would be
        // skipped. So page 0 -- the viewer opening the feed -- always starts a new
        // ranking, and the pages after it keep slicing that one for the session TTL
        // instead of the short cache TTL, which is shorter than a page of scrolling.
        // Signed-out viewers have no seen tier, so their shared ranking keeps the
        // short TTL.
        boolean sessionRanking = viewerId != null;
        List<FeedItemView> ranking = sessionRanking && page == 0 ? null : cache.getRanking(cacheKey);
        boolean fromCache = ranking != null;
        if (ranking == null) {
            ranking = viewerId == null
                    ? rankSharedOnce(cacheKey, scope)
                    : rankAndCache(viewerId, identity, scope, cacheKey);
        }

        int pageSize = properties.getPageSize();
        // long arithmetic: a large page number times the page size overflows int and
        // would produce a negative offset, which subList rejects with an exception.
        int from = (int) Math.min((long) page * pageSize, ranking.size());
        int to = Math.min(from + pageSize, ranking.size());
        List<FeedItemView> items = List.copyOf(ranking.subList(from, to));
        if (fromCache && sessionRanking) {
            // The ranking can now outlive a moderation decision by minutes. Dropping
            // revoked rows from the slice, rather than from the stored ranking, keeps
            // the offsets stable. The gateway still re-authorizes playback (Rule 12);
            // this only spares the viewer a video that will not play.
            items = withoutRevoked(items);
        }
        // hasMore lets the client stop paging instead of walking into empty pages
        // forever, which is what it had to do when only the items were returned.
        return new FeedPage(items, to < ranking.size());
    }

    private List<FeedItemView> rankAndCache(String viewerId, String identity, Scope scope, String cacheKey) {
        List<FeedItemView> ranking = rank(viewerId, identity, scope);
        cache.putRanking(
                cacheKey,
                ranking,
                viewerId != null ? properties.getRankingSessionTtl() : properties.getCacheTtl());
        return ranking;
    }

    /**
     * Single-flight for the ranking every signed-out viewer shares. When its short TTL
     * lapses, a burst of requests all miss at once, and without coordination each one
     * runs the whole ranking for the same answer. The first caller becomes the leader
     * and computes it; callers arriving while it runs wait on the same future instead
     * of querying again. Signed-in viewers never come through here: their page 0
     * deliberately starts a fresh ranking and their key is theirs alone.
     *
     * <p>The leader reads the cache once more before computing, so a caller that missed
     * just before another leader finished does not become a second leader and repeat
     * the work. This only coalesces callers inside one instance; each instance still
     * computes once per expiry, which is bounded and needs no distributed lock. If the
     * leader fails, the waiting callers fail with it rather than all retrying at once.
     */
    private List<FeedItemView> rankSharedOnce(String cacheKey, Scope scope) {
        CompletableFuture<List<FeedItemView>> mine = new CompletableFuture<>();
        CompletableFuture<List<FeedItemView>> leader = inFlight.putIfAbsent(cacheKey, mine);
        if (leader != null) {
            try {
                return leader.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw e;
            }
        }
        try {
            List<FeedItemView> ranking = cache.getRanking(cacheKey);
            if (ranking == null) {
                ranking = rankAndCache(null, ANONYMOUS, scope, cacheKey);
            }
            mine.complete(ranking);
            return ranking;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(cacheKey, mine);
        }
    }

    private List<FeedItemView> withoutRevoked(List<FeedItemView> items) {
        if (items.isEmpty()) {
            return items;
        }
        Set<String> revokedVideos = revocationReader.activeAmong(
                RevocationSubjects.VIDEO, items.stream().map(FeedItemView::videoId).toList());
        Set<String> revokedCreators = revocationReader.activeAmong(
                RevocationSubjects.ACCOUNT,
                items.stream().map(FeedItemView::creatorId).collect(Collectors.toSet()));
        if (revokedVideos.isEmpty() && revokedCreators.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(i -> !revokedVideos.contains(i.videoId()) && !revokedCreators.contains(i.creatorId()))
                .toList();
    }

    /**
     * One video in the shape the feed serves it, for a link that names a specific
     * video rather than a position in a ranking — a shared URL, or a notification
     * pointing at the video it is about.
     *
     * <p>Applies the same filters {@link #rank} does: the video and its creator
     * must both be eligible, and neither may be revoked. A link therefore cannot
     * surface something the feed itself would have excluded. This remains a
     * ranking-tier decision rather than an authorization one — the gateway
     * re-authorizes the actual playback independently (Rule 12), so a row that
     * goes stale between this call and the first segment request is stale, not
     * unsafe.
     */
    public Optional<FeedItemView> item(String videoId) {
        Optional<VideoEligibilityView> video =
                eligibilityDirectory.findVideoEligibility(videoId).filter(VideoEligibilityView::isVideoEligible);
        if (video.isEmpty()) {
            return Optional.empty();
        }
        VideoEligibilityView view = video.get();

        boolean creatorEligible = eligibilityDirectory
                .findAccountEligibility(view.creatorId())
                .map(AccountEligibilityView::isAccountEligible)
                .orElse(false); // a missing row is unknown state, and unknown denies (Rule 9)
        if (!creatorEligible) {
            return Optional.empty();
        }

        if (!revocationReader.activeAmong(RevocationSubjects.VIDEO, List.of(view.videoId())).isEmpty()
                || !revocationReader.activeAmong(RevocationSubjects.ACCOUNT, List.of(view.creatorId())).isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                new FeedItemView(view.videoId(), view.creatorId(), view.title(), view.description()));
    }

    /** Cache key and exploration seed for a viewer with no account. */
    private static final String ANONYMOUS = "anonymous";

    /**
     * Seven queries for a signed-out viewer, nine for a signed-in one, however
     * large the pool: candidates (joined to creator eligibility), video
     * revocations, account revocations, four for social counts (likes, comments,
     * shares, views), and for a signed-in viewer their follows and watch history.
     *
     * @param viewerId null for a signed-out viewer; the follow and watch-history
     *     queries are skipped rather than run against a fabricated id.
     * @param identity the same value, or {@link #ANONYMOUS}, for seeding the
     *     exploration draw — which must still be stable across the pages of one
     *     ranking.
     */
    private List<FeedItemView> rank(String viewerId, String identity, Scope scope) {
        // The Following scope is defined by who made a video, so it takes its
        // candidates from the followed creators directly. Filtering the newest-N
        // pool instead drops a followed creator's older videos as soon as other
        // creators have posted N newer ones.
        Set<String> followedCreators = null;
        List<VideoEligibilityView> candidates;
        if (scope == Scope.FOLLOWING) {
            if (viewerId == null) {
                return List.of(); // nobody to follow
            }
            followedCreators = socialDirectory.followedCreators(viewerId, properties.getFollowingCreatorLimit());
            candidates = followedCreators.isEmpty()
                    ? List.of()
                    : eligibilityDirectory.findEligibleVideosByCreators(
                            followedCreators, properties.getCandidatePoolSize());
        } else {
            candidates = eligibilityDirectory.findEligibleVideosWithEligibleCreators(properties.getCandidatePoolSize());
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> videoIds = candidates.stream().map(VideoEligibilityView::videoId).toList();
        Set<String> creatorIds =
                candidates.stream().map(VideoEligibilityView::creatorId).collect(Collectors.toSet());

        Set<String> revokedVideos = revocationReader.activeAmong(RevocationSubjects.VIDEO, videoIds);
        Set<String> revokedCreators = revocationReader.activeAmong(RevocationSubjects.ACCOUNT, creatorIds);
        Map<String, SocialCounts> counts = socialDirectory.countsForAll(videoIds);
        // Two queries a signed-out viewer has no answer to: they follow nobody and
        // have no watch history worth keying to a browser. Skipped rather than
        // asked with a placeholder id.
        Set<String> followed = followedCreators != null
                ? followedCreators
                : viewerId == null ? Set.of() : socialDirectory.followedAmong(viewerId, creatorIds);
        // One more batched query, for the same reason as the others: the feed had
        // no idea what this viewer had already watched, so it kept serving it.
        Set<String> seen = viewerId == null ? Set.of() : socialDirectory.viewedAmong(viewerId, videoIds);

        // Seeded per viewer so the exploration term is stable across the pages of
        // one ranking, and re-seeded on a slow rotation so it is not stable
        // forever -- viewerId.hashCode() alone never changed, which froze each
        // viewer's ordering for the life of their account.
        long bucket = System.currentTimeMillis() / Math.max(1, properties.getExplorationRotation().toMillis());
        Random exploration = new Random(identity.hashCode() * 31L + bucket);

        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (VideoEligibilityView video : candidates) {
            if (revokedVideos.contains(video.videoId()) || revokedCreators.contains(video.creatorId())) {
                continue; // brief section 15: filter by revocation
            }
            SocialCounts videoCounts =
                    counts.getOrDefault(video.videoId(), new SocialCounts(video.videoId(), 0, 0, 0, 0));
            double score =
                    scorer.score(video, videoCounts, followed.contains(video.creatorId()), exploration.nextDouble());
            scored.add(new ScoredCandidate(
                    video.videoId(),
                    video.creatorId(),
                    video.title(),
                    video.description(),
                    score,
                    seen.contains(video.videoId())));
        }

        // Two tiers, not one score. "Already seen" is a partition rather than a
        // weight: expressed as a penalty it has to be chosen larger than any
        // engagement total the scorer can produce, and a video with enough likes
        // will always eventually outrun whatever number was picked. Sorting
        // unseen ahead of seen says exactly what is meant and cannot be
        // out-scored.
        //
        // Still a demotion rather than a filter: on a small catalogue, dropping
        // seen videos empties the feed, and showing one again beats showing
        // nothing.
        scored.sort(Comparator.comparing(ScoredCandidate::alreadySeen)
                .thenComparing(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                // Ties would otherwise order arbitrarily between two computations of
                // the same ranking, reintroducing the instability this method fixes.
                .thenComparing(ScoredCandidate::videoId));

        return scored.stream()
                .map(c -> new FeedItemView(c.videoId(), c.creatorId(), c.title(), c.description()))
                .toList();
    }

    /** Which videos a feed request is asking for. */
    public enum Scope {
        FOR_YOU,
        FOLLOWING
    }

    /** One page of the viewer's ranking, plus whether another page exists. */
    public record FeedPage(List<FeedItemView> items, boolean hasMore) {}

    private record ScoredCandidate(
            String videoId, String creatorId, String title, String description, double score, boolean alreadySeen) {}
}

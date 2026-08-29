package com.shortvideo.feed.domain;

import com.shortvideo.eligibility.api.AccountEligibilityView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.social.api.SocialCounts;
import com.shortvideo.social.api.SocialDirectory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * Rule-based feed (brief section 15): gather eligible candidates, filter by
 * creator account state, revocation, and score, then cache the page. Filtering
 * mirrors the media gateway's authority order for revocation — deny-only Redis
 * fast path, then durable PostgreSQL — but a feed exclusion is a ranking
 * decision, not a security boundary; a stale cached row is stale, not unsafe,
 * because the gateway re-authorizes every playback independently (Rule 12).
 */
@Service
public class FeedService {

    private final EligibilityDirectory eligibilityDirectory;
    private final SocialDirectory socialDirectory;
    private final RevocationCache revocationCache;
    private final DurableRevocationReader revocationReader;
    private final FeedScorer scorer;
    private final FeedCacheService cache;
    private final FeedProperties properties;

    public FeedService(
            EligibilityDirectory eligibilityDirectory,
            SocialDirectory socialDirectory,
            RevocationCache revocationCache,
            DurableRevocationReader revocationReader,
            FeedScorer scorer,
            FeedCacheService cache,
            FeedProperties properties) {
        this.eligibilityDirectory = eligibilityDirectory;
        this.socialDirectory = socialDirectory;
        this.revocationCache = revocationCache;
        this.revocationReader = revocationReader;
        this.scorer = scorer;
        this.cache = cache;
        this.properties = properties;
    }

    public List<FeedItemView> feed(String viewerId, int page) {
        List<FeedItemView> cached = cache.get(viewerId, page);
        if (cached != null) {
            return cached;
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        for (VideoEligibilityView video : eligibilityDirectory.findEligibleVideos(properties.getCandidatePoolSize())) {
            if (isRevoked(RevocationSubjects.VIDEO, video.videoId()) || isRevoked(RevocationSubjects.ACCOUNT, video.creatorId())) {
                continue; // brief section 15: filter by revocation
            }
            AccountEligibilityView account = eligibilityDirectory.findAccountEligibility(video.creatorId()).orElse(null);
            if (account == null || !account.isAccountEligible()) {
                continue; // filter by creator account state (Rule 9: unknown denies)
            }

            SocialCounts counts = socialDirectory.countsFor(video.videoId());
            boolean followed = socialDirectory.isFollowing(viewerId, video.creatorId());
            double score = scorer.score(video, counts, followed, ThreadLocalRandom.current().nextDouble());
            scored.add(new ScoredCandidate(video.videoId(), video.creatorId(), score));
        }

        scored.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());

        int pageSize = properties.getPageSize();
        int from = Math.min(page * pageSize, scored.size());
        int to = Math.min(from + pageSize, scored.size());
        List<FeedItemView> items =
                scored.subList(from, to).stream().map(c -> new FeedItemView(c.videoId(), c.creatorId())).toList();

        cache.put(viewerId, page, items);
        return items;
    }

    private boolean isRevoked(String subjectType, String subjectId) {
        return revocationCache.isDenied(subjectType, subjectId) || revocationReader.isActive(subjectType, subjectId);
    }

    private record ScoredCandidate(String videoId, String creatorId, double score) {}
}

package com.shortvideo.social.domain;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.social.api.SocialCounts;
import com.shortvideo.social.api.SocialDirectory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SocialService implements SocialDirectory {

    private static final String PRODUCER = "short-video-backend";
    private static final String MODULE = "social";

    private final SocialRepository repository;
    private final EligibilityDirectory eligibilityDirectory;
    private final AccountDirectory accountDirectory;
    private final OutboxWriter outboxWriter;

    public SocialService(
            SocialRepository repository,
            EligibilityDirectory eligibilityDirectory,
            AccountDirectory accountDirectory,
            OutboxWriter outboxWriter) {
        this.repository = repository;
        this.eligibilityDirectory = eligibilityDirectory;
        this.accountDirectory = accountDirectory;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public void like(String videoId, String accountId) {
        requireEligible(videoId);
        repository.like(videoId, accountId);
    }

    @Transactional
    public void unlike(String videoId, String accountId) {
        repository.unlike(videoId, accountId);
    }

    @Transactional
    public CommentView comment(String videoId, String accountId, String body) {
        String videoOwnerId = requireEligible(videoId).creatorId();
        CommentView comment = repository.addComment(videoId, accountId, body);
        append(
                EventTypes.SOCIAL_VIDEO_COMMENTED,
                new SocialEvents.VideoCommented(videoId, accountId, videoOwnerId, comment.commentId()));
        return comment;
    }

    @Transactional(readOnly = true)
    public List<CommentView> listComments(String videoId) {
        requireEligible(videoId);
        return repository.listComments(videoId);
    }

    @Transactional
    public void follow(String followerId, String followeeId) {
        if (followerId.equals(followeeId)) {
            throw new SocialExceptions.CannotFollowSelf("Cannot follow your own account");
        }
        accountDirectory
                .find(followeeId)
                .filter(AccountView::isEligible)
                .orElseThrow(() -> new SocialExceptions.CreatorNotFound("No such creator"));
        if (repository.follow(followerId, followeeId)) {
            append(EventTypes.SOCIAL_CREATOR_FOLLOWED, new SocialEvents.CreatorFollowed(followerId, followeeId));
        }
    }

    @Transactional
    public void unfollow(String followerId, String followeeId) {
        repository.unfollow(followerId, followeeId);
    }

    /**
     * A non-active creator answers "not found" rather than returning their state.
     * Reporting {@code SUSPENDED} told any signed-in user which accounts had been
     * actioned — the one place that leaked it, while the login path, the video
     * reads and the media gateway all take care not to.
     */
    @Transactional(readOnly = true)
    public CreatorProfileView profile(String accountId) {
        AccountView account = accountDirectory
                .find(accountId)
                .filter(AccountView::isEligible)
                .orElseThrow(() -> new SocialExceptions.CreatorNotFound("No such creator"));
        return new CreatorProfileView(
                account.accountId(),
                account.displayName(),
                repository.followerCount(accountId),
                repository.followingCount(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public SocialCounts countsFor(String videoId) {
        return repository.countsFor(videoId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFollowing(String followerId, String followeeId) {
        return repository.isFollowing(followerId, followeeId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, SocialCounts> countsForAll(Collection<String> videoIds) {
        return repository.countsForAll(videoIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> followedAmong(String followerId, Collection<String> creatorIds) {
        return repository.followedAmong(followerId, creatorIds);
    }

    private VideoEligibilityView requireEligible(String videoId) {
        return eligibilityDirectory
                .findVideoEligibility(videoId)
                .filter(VideoEligibilityView::isVideoEligible)
                .orElseThrow(() -> new SocialExceptions.VideoNotEligible("Video is not available"));
    }

    private void append(String eventType, Object payload) {
        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                1,
                AggregateTypes.SOCIAL,
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));
    }
}

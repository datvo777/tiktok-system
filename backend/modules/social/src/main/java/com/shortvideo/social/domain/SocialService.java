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

    /**
     * Idempotent. The event is emitted only when a row was actually inserted, so
     * a client that re-sends a like — a retry, a double tap — does not produce a
     * second notification for the creator.
     */
    @Transactional
    public void like(String videoId, String accountId) {
        String videoOwnerId = requireEligible(videoId).creatorId();
        boolean newlyLiked = repository.like(videoId, accountId);
        // Liking your own video is not news to you.
        if (newlyLiked && !accountId.equals(videoOwnerId)) {
            append(EventTypes.SOCIAL_VIDEO_LIKED, new SocialEvents.VideoLiked(videoId, accountId, videoOwnerId));
        }
    }

    @Transactional
    public void unlike(String videoId, String accountId) {
        repository.unlike(videoId, accountId);
    }

    /**
     * Records that this viewer watched the video.
     *
     * <p>Called once the viewer has actually watched enough for it to count --
     * the client decides the threshold, this records the fact. Idempotent per
     * viewer in the sense that matters: a rewatch bumps their existing row rather
     * than inflating the public view count, which is distinct viewers.
     *
     * <p>Deliberately emits no event. A view is the highest-volume fact on the
     * platform by a wide margin, and putting one outbox row and one Kafka message
     * behind every play would swamp the relay for a signal nothing reacts to
     * synchronously.
     */
    @Transactional
    public void recordView(String videoId, String accountId, long watchedMs, boolean completed) {
        requireEligible(videoId);
        repository.recordView(videoId, accountId, watchedMs, completed);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> viewedAmong(String viewerId, Collection<String> videoIds) {
        return repository.viewedAmong(viewerId, videoIds);
    }

    @Transactional
    public void share(String videoId, String accountId) {
        requireEligible(videoId);
        repository.share(videoId, accountId);
    }

    /**
     * @param accountId null for a signed-out viewer. The totals are public; only
     *     {@code liked} is viewer-relative, and with no viewer it is false.
     */
    @Transactional(readOnly = true)
    public VideoCountsView videoCounts(String videoId, String accountId) {
        requireEligible(videoId);
        SocialCounts counts = repository.countsFor(videoId);
        return new VideoCountsView(counts, accountId != null && repository.isLiked(videoId, accountId));
    }

    @Transactional
    public CommentView comment(String videoId, String accountId, String body) {
        String videoOwnerId = requireEligible(videoId).creatorId();
        CommentView comment = repository.addComment(videoId, accountId, body, null);
        append(
                EventTypes.SOCIAL_VIDEO_COMMENTED,
                new SocialEvents.VideoCommented(videoId, accountId, videoOwnerId, comment.commentId()));
        return comment;
    }

    /**
     * A reply is addressed to the author of the comment it answers, which is why
     * this emits {@code SOCIAL_COMMENT_REPLIED} rather than reusing the top-level
     * comment event: that event names the video owner as recipient, so replies
     * used to notify the wrong person and leave the right one in silence.
     */
    @Transactional
    public CommentView reply(String videoId, String parentCommentId, String accountId, String body) {
        String videoOwnerId = requireEligible(videoId).creatorId();
        // One query both validates the parent and yields the person to notify.
        String parentAuthorId = repository
                .topLevelCommentAuthor(parentCommentId, videoId)
                .orElseThrow(() -> new SocialExceptions.CommentNotFound("No such comment on this video"));

        CommentView reply = repository.addComment(videoId, accountId, body, parentCommentId);
        append(
                EventTypes.SOCIAL_COMMENT_REPLIED,
                new SocialEvents.CommentReplied(
                        videoId, accountId, parentAuthorId, videoOwnerId, parentCommentId, reply.commentId()));
        return reply;
    }

    /** Default page size; the client may ask for less, never for more. */
    public static final int COMMENT_PAGE_SIZE = 50;
    public static final int MAX_COMMENT_PAGE_SIZE = 100;

    @Transactional(readOnly = true)
    public CommentPage listComments(String videoId, String cursor, int limit) {
        requireEligible(videoId);
        int size = boundedPageSize(limit);
        // One extra row answers "is there another page?" without a count query;
        // it is dropped before the page is returned.
        List<CommentView> rows =
                repository.listComments(videoId, CommentCursors.decode(cursor), size + 1);
        return withAuthorNames(CommentPage.of(rows, size));
    }

    @Transactional(readOnly = true)
    public CommentPage listReplies(String videoId, String parentCommentId, String cursor, int limit) {
        requireEligible(videoId);
        if (repository.topLevelCommentAuthor(parentCommentId, videoId).isEmpty()) {
            throw new SocialExceptions.CommentNotFound("No such comment on this video");
        }
        int size = boundedPageSize(limit);
        List<CommentView> rows =
                repository.listReplies(parentCommentId, CommentCursors.decode(cursor), size + 1);
        return withAuthorNames(CommentPage.of(rows, size));
    }

    private static int boundedPageSize(int requested) {
        if (requested <= 0) {
            return COMMENT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_COMMENT_PAGE_SIZE);
    }

    /**
     * Removes a comment. Permitted to its author, and to the owner of the video
     * it sits on — a creator moderating their own comment section is the other
     * half of why this exists.
     *
     * <p>Soft: the row is retained and only its visibility changes, so replies
     * are not orphaned and an abuse investigation can still see what was said.
     * Deleting a top-level comment takes its replies with it, because answers to
     * a question nobody can see any more are not worth showing.
     *
     * <p>Idempotent — deleting an already-deleted comment reports success rather
     * than an error, since the caller's intended state is the one that holds.
     */
    @Transactional
    public void deleteComment(String videoId, String commentId, String actorAccountId) {
        String videoOwnerId = requireEligible(videoId).creatorId();
        var comment = repository
                .findForDelete(commentId)
                .filter(c -> c.videoId().equals(videoId))
                .orElse(null);
        if (comment == null) {
            return; // already deleted, or never existed on this video
        }
        if (!actorAccountId.equals(comment.accountId()) && !actorAccountId.equals(videoOwnerId)) {
            // Indistinguishable from "no such comment" on purpose: a caller
            // should not be able to probe for comment ids they cannot touch.
            throw new SocialExceptions.CommentNotFound("No such comment on this video");
        }
        repository.softDeleteComment(commentId, actorAccountId, comment.parentCommentId() == null);
    }

    /**
     * Resolves every author on the page in one query.
     *
     * <p>Comments used to render as {@code @a1b2c3d4e5} -- the first ten hex
     * characters of the author's UUID, fabricated client-side -- because the
     * payload carried no name at all and there is no username in this system.
     * One batched lookup here is what turns a thread of hex into a thread of
     * people.
     */
    private CommentPage withAuthorNames(CommentPage page) {
        if (page.items().isEmpty()) {
            return page;
        }
        Set<String> authorIds =
                page.items().stream().map(CommentView::accountId).collect(java.util.stream.Collectors.toSet());
        Map<String, AccountView> authors = accountDirectory.findAll(authorIds);
        Map<String, String> names = authors.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().displayName()));
        Map<String, String> handles = authors.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().handle()));
        return new CommentPage(page.items(), page.nextCursor(), names, handles);
    }

    /**
     * One page of comments, the cursor that continues it (null at the end), and
     * the display name of every author on the page.
     */
    public record CommentPage(
            List<CommentView> items,
            String nextCursor,
            Map<String, String> authorNames,
            Map<String, String> authorHandles) {
        static CommentPage of(List<CommentView> rows, int pageSize) {
            if (rows.size() <= pageSize) {
                return new CommentPage(List.copyOf(rows), null, Map.of(), Map.of());
            }
            List<CommentView> page = List.copyOf(rows.subList(0, pageSize));
            CommentView last = page.get(page.size() - 1);
            return new CommentPage(
                    page, CommentCursors.encode(last.createdAt(), last.commentId()), Map.of(), Map.of());
        }
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
    public CreatorProfileView profile(String accountId, String viewerId) {
        AccountView account = accountDirectory
                .find(accountId)
                .filter(AccountView::isEligible)
                .orElseThrow(() -> new SocialExceptions.CreatorNotFound("No such creator"));
        return new CreatorProfileView(
                account.accountId(),
                account.displayName(),
                account.handle(),
                account.bio(),
                repository.followerCount(accountId),
                repository.followingCount(accountId),
                // Viewer-relative, and false when there is no viewer.
                viewerId != null && !accountId.equals(viewerId) && repository.isFollowing(viewerId, accountId));
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

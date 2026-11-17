package com.shortvideo.social.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * The Social module's synchronous interface for the Feed module (brief section
 * 15). Ranking inputs only — never used for an authorization decision.
 */
public interface SocialDirectory {

    SocialCounts countsFor(String videoId);

    boolean isFollowing(String followerId, String followeeId);

    /**
     * The counts a ranking scores on, for a whole candidate page at once. The
     * per-video form issued two {@code count(*)} subqueries per candidate; over a
     * 200-video pool that was 200 round trips for what one grouped query answers.
     * Only likes and comments: the share and view counts are display-only, and
     * counting views over the whole pool was the most expensive of the four
     * queries this used to run.
     *
     * @return counts keyed by videoId. A video with no likes and no comments is
     *     present with zeros rather than absent, so callers need no null handling.
     */
    Map<String, EngagementCounts> engagementForAll(Collection<String> videoIds);

    /**
     * Batched form of {@link #isFollowing}: which of {@code creatorIds} this viewer
     * follows, in one query rather than one per candidate.
     */
    Set<String> followedAmong(String followerId, Collection<String> creatorIds);

    /**
     * The creators this viewer follows, most recently followed first, at most
     * {@code limit} of them.
     */
    Set<String> followedCreators(String followerId, int limit);

    /**
     * Which of {@code videoIds} this viewer has already been shown.
     *
     * <p>The feed had no notion of this at all, so a ranking recomputed after its
     * short cache expired produced the same ordering from the same candidate pool
     * and served the same videos again. One query over the whole candidate set,
     * for the same reason the counts and follows are batched.
     */
    Set<String> viewedAmong(String viewerId, Collection<String> videoIds);
}

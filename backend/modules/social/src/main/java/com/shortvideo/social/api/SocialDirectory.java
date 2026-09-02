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
     * Batched form of {@link #countsFor} for ranking a whole candidate page. The
     * per-video form issued two {@code count(*)} subqueries per candidate; over a
     * 200-video pool that was 200 round trips for what one grouped query answers.
     *
     * @return counts keyed by videoId. A video with no likes and no comments is
     *     present with zeros rather than absent, so callers need no null handling.
     */
    Map<String, SocialCounts> countsForAll(Collection<String> videoIds);

    /**
     * Batched form of {@link #isFollowing}: which of {@code creatorIds} this viewer
     * follows, in one query rather than one per candidate.
     */
    Set<String> followedAmong(String followerId, Collection<String> creatorIds);
}

package com.shortvideo.social.api;

/**
 * The Social module's synchronous interface for the Feed module (brief section
 * 15). Ranking inputs only — never used for an authorization decision.
 */
public interface SocialDirectory {

    SocialCounts countsFor(String videoId);

    boolean isFollowing(String followerId, String followeeId);
}

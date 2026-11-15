package com.shortvideo.feed.domain;

/**
 * What a cached ranking keeps of each video: enough to order it and to check its
 * creator for revocation, nothing that can go stale. Title and description are
 * read back from the projection when a page is served, so a cached ranking is
 * about a tenth the size of one that carried them, and a title edit or an
 * ineligible video shows up on the next page instead of when the key expires.
 */
public record FeedRef(String videoId, String creatorId) {}

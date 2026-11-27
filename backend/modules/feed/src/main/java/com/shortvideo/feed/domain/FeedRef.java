package com.shortvideo.feed.domain;

/**
 * What a cached ranking keeps of each video: its id, which is all that is needed to
 * order it. Everything else -- title, description, creator -- is read back from the
 * projection when a page is served, so a cached ranking holds nothing that can go
 * stale, and a title edit, an ineligible video or a revoked creator shows up on the
 * next page instead of when the key expires.
 *
 * <p>The creator id used to be kept here too, to check it for revocation. Nothing
 * read it back: a page's creators come from the hydrated rows. It was about half of
 * every key, and every signed-in viewer holds a key for the whole session TTL.
 */
public record FeedRef(String videoId) {}

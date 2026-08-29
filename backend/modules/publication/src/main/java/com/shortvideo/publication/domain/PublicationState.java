package com.shortvideo.publication.domain;

/** Brief section 7. Persisted as a string, never an ordinal. */
public enum PublicationState {
    PRIVATE,
    PUBLISH_PENDING,
    PUBLISHED,
    SUSPENDED,
    REMOVED
}

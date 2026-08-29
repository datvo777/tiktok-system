package com.shortvideo.moderation.domain;

/** Brief section 7. Persisted as a string, never an ordinal. */
public enum ModerationState {
    PENDING,
    APPROVED,
    REJECTED,
    REINSTATED
}

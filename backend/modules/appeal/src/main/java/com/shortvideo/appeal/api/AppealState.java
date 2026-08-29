package com.shortvideo.appeal.api;

/** Brief section 7. Persisted as a string, never an ordinal. */
public enum AppealState {
    NONE,
    UNDER_APPEAL,
    REVIEWING,
    APPROVED,
    DENIED,
    ESCALATED
}

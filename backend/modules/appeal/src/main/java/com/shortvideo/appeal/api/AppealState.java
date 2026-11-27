package com.shortvideo.appeal.api;

/**
 * Brief section 7. Persisted as a string, never an ordinal. REVIEWING and ESCALATED are part of the
 * brief's vocabulary and the clients already render them, but no action moves an appeal into either yet;
 * a decision is accepted from REVIEWING so that an admin "take this appeal" step can be added without
 * touching it.
 */
public enum AppealState {
    NONE,
    UNDER_APPEAL,
    REVIEWING,
    APPROVED,
    DENIED,
    ESCALATED
}

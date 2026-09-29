package com.shortvideo.eligibility.api;

import java.util.Collection;
import java.util.Map;

/**
 * Durable consecutive-failure streaks for the reconciliation sweep, owned by the
 * {@code eligibility} schema so {@code backend/app} never touches it directly.
 * Only ids currently failing have a row, so it stays small.
 */
public interface ReconciliationFailureTracker {

    /** Current streak per id for {@code kind} ({@code "video"} / {@code "account"}); one query per sweep. */
    Map<String, Integer> streaks(String kind);

    /** Increments and returns the new streak for {@code id}. */
    int recordFailure(String kind, String id, String error);

    /** Clears streaks for ids that succeeded, in one statement. */
    void clear(String kind, Collection<String> ids);
}

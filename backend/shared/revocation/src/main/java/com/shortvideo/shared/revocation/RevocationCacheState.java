package com.shortvideo.shared.revocation;

/**
 * Brief section 8: "WARMING, READY, DEGRADED, and STALE are operational states
 * for Redis rebuild, reconciliation, lag monitoring... Cache readiness does not
 * authorize media in the local MVP." Purely observational — nothing in the
 * authorization path branches on this (Rule 12).
 */
public enum RevocationCacheState {
    WARMING,
    READY,
    DEGRADED,
    STALE
}

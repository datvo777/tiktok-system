package com.shortvideo.shared.revocation;

import java.util.Set;

/**
 * The deny check shared by the media gateway and the endpoint that issues a preview session, so the two
 * cannot answer differently about the same subject.
 */
public final class RevocationChecks {

    private RevocationChecks() {}

    /** Whether any restrictive source is active: Redis as a fast deny, the durable record as the authority. */
    public static boolean isBlocked(
            RevocationCache cache, DurableRevocationReader reader, String subjectType, String subjectId) {
        return cache.isDenied(subjectType, subjectId) || reader.isActive(subjectType, subjectId);
    }

    /**
     * As {@link #isBlocked}, but a source in {@code ignored} does not count. Redis absence still never
     * permits anything: the durable record is always asked, and any other active source blocks.
     */
    public static boolean isBlockedExcept(
            RevocationCache cache,
            DurableRevocationReader reader,
            String subjectType,
            String subjectId,
            Set<String> ignored) {
        if (ignored.isEmpty()) {
            return isBlocked(cache, reader, subjectType, subjectId);
        }
        return hasOtherThan(cache.activeSources(subjectType, subjectId), ignored)
                || hasOtherThan(reader.activeSources(subjectType, subjectId), ignored);
    }

    private static boolean hasOtherThan(Set<String> sources, Set<String> ignored) {
        return sources.stream().anyMatch(source -> !ignored.contains(source));
    }
}

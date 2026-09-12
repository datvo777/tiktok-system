package com.shortvideo.shared.revocation;

/**
 * Authoritative revocation read (brief section 8, Rule 12).
 *
 * <p>The gateway consults this after the Redis deny fast-path fails to find an
 * entry — Redis absence never authorizes; only this durable check, or an explicit
 * durable eligibility allow read afterwards, may permit delivery.
 */
public interface DurableRevocationReader {

    boolean isActive(String subjectType, String subjectId);

    /**
     * Batched form of {@link #isActive} for callers ranking or filtering a whole
     * page at once, so a 200-candidate feed costs one query rather than 200.
     *
     * <p>Deliberately not used on the media authorization path: that path
     * authorizes exactly one object per request, so it has nothing to batch, and
     * keeping it on the single-subject call preserves the per-request check
     * Rule 12 requires.
     *
     * @return the subset of {@code subjectIds} with an active revocation.
     */
    java.util.Set<String> activeAmong(String subjectType, java.util.Collection<String> subjectIds);
}

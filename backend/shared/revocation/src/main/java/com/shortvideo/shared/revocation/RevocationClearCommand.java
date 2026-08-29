package com.shortvideo.shared.revocation;

/**
 * Clears one restrictive source, only if it is still the active blocking decision
 * (brief section 16). A permissive transition never blindly deletes the subject's
 * whole revocation state — only its own source field, and only when
 * {@code expectedBlockingVersion} still matches.
 */
public record RevocationClearCommand(
        String subjectType, String subjectId, String sourceType, long expectedBlockingVersion) {}

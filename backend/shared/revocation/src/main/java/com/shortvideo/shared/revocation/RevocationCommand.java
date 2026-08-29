package com.shortvideo.shared.revocation;

/**
 * Activates one restrictive source against one subject (brief section 16).
 *
 * <p>{@code sourceVersion} must come from the same authoritative source stream
 * that is restricting the subject (e.g. the moderation aggregate version, or the
 * account aggregate version for a suspension) — never compared against a version
 * from a different domain (Rule 10).
 */
public record RevocationCommand(
        String subjectType, String subjectId, String sourceType, long sourceVersion, String reason) {}

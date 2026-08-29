package com.shortvideo.shared.revocation;

/** One row of {@code platform.revocation} where {@code active = true} — used to rebuild the Redis cache. */
public record ActiveRevocation(String subjectType, String subjectId, String sourceType, String reason) {}

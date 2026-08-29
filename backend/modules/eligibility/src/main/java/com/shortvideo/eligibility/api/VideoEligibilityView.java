package com.shortvideo.eligibility.api;

import java.time.Instant;

/**
 * Brief section 17. Covers only the video-side conjuncts of section 8 — never
 * account state. Three independent sources write into this one row
 * (processing/durability/asset-lifecycle/legal from the Video module, moderation
 * from the Moderation module, publication from the Publication module), each
 * guarded by its own version column — never compared against a version from a
 * different domain (Rule 10).
 */
public record VideoEligibilityView(
        String videoId,
        String creatorId,
        String processingState,
        Integer processingVersion,
        String durabilityState,
        String moderationState,
        String publicationState,
        boolean publicationIntentRequested,
        String assetLifecycleState,
        String legalServingState,
        boolean isVideoEligible,
        long processingVersionSource,
        long moderationVersionSource,
        long publicationVersionSource,
        Instant updatedAt) {}

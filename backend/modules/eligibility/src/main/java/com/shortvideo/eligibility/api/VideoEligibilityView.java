package com.shortvideo.eligibility.api;

import java.time.Instant;

/**
 * Brief section 17. Covers only the video-side conjuncts of section 8 — never
 * account state. Four independent sources write into this one row
 * (processing/durability/asset-lifecycle/legal and title/description from the
 * Video module, moderation from the Moderation module, publication from the
 * Publication module), each guarded by its own version column — never compared
 * against a version from a different domain (Rule 10).
 *
 * <p>{@code title}/{@code description} are ranking/display payload only (brief
 * section 15) — never part of {@code isVideoEligible} (Rule 12: authorization
 * never depends on content metadata).
 */
public record VideoEligibilityView(
        String videoId,
        String creatorId,
        String title,
        String description,
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
        long metadataVersionSource,
        Instant updatedAt,
        Instant firstEligibleAt) {

    /** For a row that has no first-eligible time to report (never eligible, or built without one). */
    public VideoEligibilityView(
            String videoId,
            String creatorId,
            String title,
            String description,
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
            long metadataVersionSource,
            Instant updatedAt) {
        this(
                videoId, creatorId, title, description, processingState, processingVersion, durabilityState,
                moderationState, publicationState, publicationIntentRequested, assetLifecycleState,
                legalServingState, isVideoEligible, processingVersionSource, moderationVersionSource,
                publicationVersionSource, metadataVersionSource, updatedAt, null);
    }

    /**
     * How old the video is for ranking: when it first became eligible, not when its row was last
     * written. {@code updatedAt} moves on every publish toggle, moderation decision and asset
     * lifecycle change, so ranking on it let any of those make an old video look new. Falls back to
     * {@code updatedAt} for a row without a first-eligible time.
     */
    public Instant rankedSince() {
        return firstEligibleAt != null ? firstEligibleAt : updatedAt;
    }
}

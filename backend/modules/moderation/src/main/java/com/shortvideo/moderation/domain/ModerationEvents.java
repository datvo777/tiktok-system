package com.shortvideo.moderation.domain;

import com.shortvideo.moderation.api.PolicyCategory;

/** Canonical absolute-state payload (brief section 10). */
public final class ModerationEvents {

    /**
     * {@code policyCategory} is additive and nullable, so existing consumers —
     * which read named fields off a decoded map — are unaffected and the schema
     * version stays at 1. It is carried here as well as in the moderation table
     * because the outbox is the durable history: a consumer reconstructing why a
     * video was taken down should not have to call back into this module.
     */
    public record ModerationStateChanged(
            String videoId,
            String creatorId,
            ModerationState state,
            long aggregateVersion,
            String reason,
            PolicyCategory policyCategory) {}

    private ModerationEvents() {}
}

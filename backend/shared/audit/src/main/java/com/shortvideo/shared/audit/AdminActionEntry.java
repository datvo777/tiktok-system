package com.shortvideo.shared.audit;

import java.time.Instant;

/** One recorded admin action, as read back for the console. */
public record AdminActionEntry(
        String actionId,
        String actorAccountId,
        String action,
        String targetType,
        String targetId,
        String policyCategory,
        String reason,
        String correlationId,
        Instant occurredAt) {}

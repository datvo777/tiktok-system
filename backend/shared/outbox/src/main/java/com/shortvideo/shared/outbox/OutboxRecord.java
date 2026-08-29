package com.shortvideo.shared.outbox;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code platform.outbox_event}. */
public record OutboxRecord(
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        int schemaVersion,
        long aggregateVersion,
        String payloadJson,
        Instant occurredAt,
        int attemptCount,
        UUID claimToken) {}

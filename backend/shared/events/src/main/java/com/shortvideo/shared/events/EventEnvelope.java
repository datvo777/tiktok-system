package com.shortvideo.shared.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The versioned envelope every domain event carries (brief section 11).
 *
 * <p>{@code aggregateVersion} is nullable on purpose: a stateless producer such as
 * the media worker owns no aggregate and therefore has no version to report. Its
 * records are commands/results, never authoritative absolute-state events
 * (Rule 16). Authoritative events emitted from an outbox always set it.
 *
 * @param <T> payload type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        Long aggregateVersion,
        Instant occurredAt,
        String producer,
        String producerModule,
        String correlationId,
        String causationId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }

    /** True when this envelope carries authoritative absolute state. */
    public boolean isAuthoritative() {
        return aggregateVersion != null;
    }
}

package com.shortvideo.shared.outbox;

import com.shortvideo.shared.events.EventEnvelope;
import java.time.Instant;

/**
 * Inserts an outbox event inside the caller's transaction (brief section 10).
 *
 * <p>The authoritative aggregate update and this insert commit together, or
 * neither happens. Never call this outside a transaction that also writes the
 * state the event describes.
 */
public interface OutboxWriter {

    /**
     * @throws org.springframework.dao.DuplicateKeyException if an event already
     *     exists for this (aggregateType, aggregateId, aggregateVersion) — the
     *     unique constraint enforcing one canonical event per transition.
     */
    void append(EventEnvelope<?> envelope);

    /**
     * Same as {@link #append(EventEnvelope)}, but the relay does not publish the event before
     * {@code availableAt}. This is how a retry gets its backoff without a timer of its own.
     */
    void append(EventEnvelope<?> envelope, Instant availableAt);
}

package com.shortvideo.shared.outbox;

import com.shortvideo.shared.events.EventEnvelope;

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
}

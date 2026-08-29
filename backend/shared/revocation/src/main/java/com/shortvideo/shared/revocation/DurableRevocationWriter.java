package com.shortvideo.shared.revocation;

/**
 * Writes durable revocation state inside the caller's transaction (brief section 9,
 * "Shared transactional revocation ownership").
 *
 * <p>The same transaction commits the authoritative aggregate update, this
 * revocation update, and the canonical outbox event together. No network call is
 * made here; the Redis acceleration cache is updated after commit.
 */
public interface DurableRevocationWriter {

    void activate(RevocationCommand command);

    void clear(RevocationClearCommand command);
}

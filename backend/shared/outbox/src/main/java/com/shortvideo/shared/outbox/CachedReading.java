package com.shortvideo.shared.outbox;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * A value read from the database for a gauge, cached briefly so a scrape every few seconds does not
 * become a query every few seconds. If the database cannot answer, the last known value is kept
 * rather than reporting zero, which would clear an alert exactly when the database is struggling.
 */
final class CachedReading {

    private final DoubleSupplier source;
    private final long ttlNanos;
    private double cached;
    private long readAt;
    private boolean read;

    CachedReading(DoubleSupplier source, Duration ttl) {
        this.source = source;
        this.ttlNanos = ttl.toNanos();
    }

    synchronized double current() {
        long now = System.nanoTime();
        if (!read || now - readAt >= ttlNanos) {
            try {
                cached = source.getAsDouble();
                read = true;
            } catch (RuntimeException e) {
                if (!read) {
                    throw e;
                }
            }
            readAt = now;
        }
        return cached;
    }
}

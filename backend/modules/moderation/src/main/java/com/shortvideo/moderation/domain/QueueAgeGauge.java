package com.shortvideo.moderation.domain;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * How long the longest-waiting record in the review queue has been waiting, in seconds; 0 when the
 * queue is empty. Read from the table rather than counted in memory, so it survives restarts and
 * reflects what reviewers actually see.
 *
 * <p>The value is cached briefly so a scrape every few seconds does not become a query every few
 * seconds; if the database cannot answer, the last known value is kept rather than reporting zero.
 */
@Component
class QueueAgeGauge implements MeterBinder {

    static final String NAME = "moderation.queue.oldest.age";
    private static final Duration TTL = Duration.ofSeconds(15);

    private final Supplier<Instant> oldestQueuedAt;
    private final long ttlNanos;
    private double cached;
    private long readAt;
    private boolean read;

    @Autowired
    QueueAgeGauge(ModerationJpaRepository repository) {
        this(() -> repository.findOldestQueuedAt(ModerationState.PENDING), TTL);
    }

    QueueAgeGauge(Supplier<Instant> oldestQueuedAt, Duration ttl) {
        this.oldestQueuedAt = oldestQueuedAt;
        this.ttlNanos = ttl.toNanos();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(NAME, this, QueueAgeGauge::current)
                .baseUnit("seconds")
                .description("Age of the longest-waiting record in the human review queue")
                .register(registry);
    }

    private synchronized double current() {
        long now = System.nanoTime();
        if (!read || now - readAt >= ttlNanos) {
            try {
                Instant oldest = oldestQueuedAt.get();
                cached = oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds());
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

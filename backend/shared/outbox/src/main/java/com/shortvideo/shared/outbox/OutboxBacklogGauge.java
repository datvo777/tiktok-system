package com.shortvideo.shared.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * How long the oldest event still waiting to be published has been waiting, in seconds; zero when
 * nothing is. This is the delay consumers see, and it is the signal that the relay has fallen behind
 * (Kafka slow or down, relay stopped), which the other outbox metrics cannot show: the DEAD gauge
 * only moves once an event has exhausted its attempts, and the published counter says nothing about
 * what is queued.
 *
 * <p>Age rather than a row count because it is one read of the first index entry however large the
 * backlog is, and because "events are 5 minutes late" is what an operator acts on.
 *
 * <p>The value is cached briefly (see {@link CachedReading}).
 */
public class OutboxBacklogGauge implements MeterBinder {

    public static final String NAME = "outbox.oldest.waiting.seconds";

    private final CachedReading reading;

    public OutboxBacklogGauge(OutboxRepository repository, Duration ttl) {
        this(repository::oldestWaitingSeconds, ttl);
    }

    OutboxBacklogGauge(DoubleSupplier age, Duration ttl) {
        this.reading = new CachedReading(age, ttl);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(NAME, reading, CachedReading::current)
                .description("Age of the oldest outbox event not yet published (0 when none is waiting)")
                .register(registry);
    }
}

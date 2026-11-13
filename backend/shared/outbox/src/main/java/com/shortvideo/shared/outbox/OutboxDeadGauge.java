package com.shortvideo.shared.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * How many outbox rows are DEAD right now. The relay's {@code outbox.events.dead} counter only
 * records that an event died during this process's lifetime: it resets on restart and says nothing
 * about rows already sitting there. Nothing replays or removes a DEAD row, so this is read from the
 * table, and an alert on it above zero is how an operator learns that consumers are missing an event.
 *
 * <p>The count is cached briefly (see {@link CachedReading}).
 */
public class OutboxDeadGauge implements MeterBinder {

    public static final String NAME = "outbox.dead.rows";

    private final CachedReading reading;

    public OutboxDeadGauge(OutboxRepository repository, Duration ttl) {
        this(repository::countDead, ttl);
    }

    OutboxDeadGauge(LongSupplier counter, Duration ttl) {
        this.reading = new CachedReading(counter::getAsLong, ttl);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(NAME, reading, CachedReading::current)
                .description("Outbox events in DEAD status, awaiting an operator")
                .register(registry);
    }
}

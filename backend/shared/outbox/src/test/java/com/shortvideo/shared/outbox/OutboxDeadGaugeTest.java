package com.shortvideo.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OutboxDeadGaugeTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private double value() {
        return meters.get(OutboxDeadGauge.NAME).gauge().value();
    }

    @Test
    void reportsTheNumberOfDeadRows() {
        AtomicLong dead = new AtomicLong(3);
        new OutboxDeadGauge(dead::get, Duration.ZERO).bindTo(meters);

        assertThat(value()).isEqualTo(3.0);
        dead.set(0);
        assertThat(value()).isZero();
    }

    @Test
    void doesNotQueryAgainWithinTheCacheWindow() {
        AtomicInteger queries = new AtomicInteger();
        new OutboxDeadGauge(() -> queries.incrementAndGet(), Duration.ofMinutes(5)).bindTo(meters);

        value();
        value();
        value();

        assertThat(queries).hasValue(1);
    }

    @Test
    void keepsTheLastKnownCountWhenTheDatabaseCannotAnswer() {
        AtomicLong dead = new AtomicLong(2);
        boolean[] failing = {false};
        new OutboxDeadGauge(
                        () -> {
                            if (failing[0]) {
                                throw new IllegalStateException("db down");
                            }
                            return dead.get();
                        },
                        Duration.ZERO)
                .bindTo(meters);

        assertThat(value()).isEqualTo(2.0);
        failing[0] = true;

        // Reporting zero here would silently clear the alert exactly when the database is struggling.
        assertThat(value()).isEqualTo(2.0);
    }
}

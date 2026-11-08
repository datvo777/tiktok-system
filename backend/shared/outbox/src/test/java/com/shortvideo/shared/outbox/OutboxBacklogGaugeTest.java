package com.shortvideo.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxBacklogGaugeTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private double value() {
        return meters.get(OutboxBacklogGauge.NAME).gauge().value();
    }

    @Test
    void reportsTheAgeOfTheOldestWaitingEvent() {
        double[] age = {42.5};
        new OutboxBacklogGauge(() -> age[0], Duration.ZERO).bindTo(meters);

        assertThat(value()).isEqualTo(42.5);
        age[0] = 0;
        assertThat(value()).isZero();
    }

    @Test
    void doesNotQueryAgainWithinTheCacheWindow() {
        AtomicInteger queries = new AtomicInteger();
        new OutboxBacklogGauge(queries::incrementAndGet, Duration.ofMinutes(5)).bindTo(meters);

        value();
        value();

        assertThat(queries).hasValue(1);
    }

    @Test
    void keepsTheLastKnownAgeWhenTheDatabaseCannotAnswer() {
        boolean[] failing = {false};
        new OutboxBacklogGauge(
                        () -> {
                            if (failing[0]) {
                                throw new IllegalStateException("db down");
                            }
                            return 900;
                        },
                        Duration.ZERO)
                .bindTo(meters);

        assertThat(value()).isEqualTo(900);
        failing[0] = true;

        // Reporting zero here would clear the alert exactly when the backlog is likely to be growing.
        assertThat(value()).isEqualTo(900);
    }
}

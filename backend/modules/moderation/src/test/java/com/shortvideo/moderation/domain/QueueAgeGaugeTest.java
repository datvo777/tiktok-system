package com.shortvideo.moderation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class QueueAgeGaugeTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double age(Supplier<Instant> oldest, Duration ttl) {
        new QueueAgeGauge(oldest, ttl).bindTo(registry);
        return registry.get(QueueAgeGauge.NAME).gauge().value();
    }

    @Test
    void reportsHowLongTheOldestQueuedRecordHasWaited() {
        assertThat(age(() -> Instant.now().minusSeconds(3600), Duration.ofSeconds(15)))
                .isBetween(3599.0, 3605.0);
    }

    @Test
    void anEmptyQueueIsZeroNotMissing() {
        assertThat(age(() -> null, Duration.ofSeconds(15))).isZero();
    }

    @Test
    void aBriefDatabaseFailureKeepsTheLastKnownValueRatherThanReportingZero() {
        var calls = new AtomicInteger();
        var gauge = new QueueAgeGauge(
                () -> {
                    if (calls.incrementAndGet() > 1) {
                        throw new IllegalStateException("db down");
                    }
                    return Instant.now().minusSeconds(600);
                },
                Duration.ZERO);
        gauge.bindTo(registry);

        double first = registry.get(QueueAgeGauge.NAME).gauge().value();
        double second = registry.get(QueueAgeGauge.NAME).gauge().value();

        assertThat(first).isBetween(599.0, 605.0);
        assertThat(second).isBetween(599.0, 606.0);
    }
}

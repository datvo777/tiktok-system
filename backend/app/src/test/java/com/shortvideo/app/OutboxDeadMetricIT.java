package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.shared.outbox.OutboxDeadGauge;
import com.shortvideo.shared.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The DEAD-row gauge against the real table, and that the app exposes it for an alert to read. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OutboxDeadMetricIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    MeterRegistry appMeters;

    @Test
    void theGaugeCountsDeadRowsAndTheAppExposesIt() {
        long before = outbox.countDead();
        insertDead();
        insertDead();

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new OutboxDeadGauge(outbox, Duration.ZERO).bindTo(meters);

        assertThat(meters.get(OutboxDeadGauge.NAME).gauge().value()).isEqualTo(before + 2);
        assertThat(appMeters.find(OutboxDeadGauge.NAME).gauge()).isNotNull();
    }

    private void insertDead() {
        UUID eventId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                """
                INSERT INTO platform.outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, aggregate_version,
                    payload, occurred_at, available_at, status, last_attempt_at
                ) VALUES (?, 'NOTIFICATION', ?, 'notification.created', 1, 1, '{}'::jsonb, ?, ?, 'DEAD', ?)
                """,
                eventId,
                eventId.toString(),
                now,
                now,
                now);
    }
}

package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.shared.outbox.OutboxRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A relay that dies while sending an event never gets to record a failure, so the event is
 * reclaimed after each lease and its attempt count climbs without anything ever marking it DEAD.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OutboxAbandonedClaimIT {

    private static final int MAX_ATTEMPTS = 10;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRepository outbox;

    @Test
    void anExpiredClaimWithItsAttemptBudgetSpentIsMovedToDead() {
        UUID spent = insertClaimed(MAX_ATTEMPTS, "-1 minute");
        UUID withBudgetLeft = insertClaimed(MAX_ATTEMPTS - 1, "-1 minute");
        UUID stillLeased = insertClaimed(MAX_ATTEMPTS, "+1 minute");

        assertThat(outbox.buryAbandoned(MAX_ATTEMPTS)).isGreaterThanOrEqualTo(1);

        assertThat(status(spent)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM platform.outbox_event WHERE event_id = ?", String.class, spent))
                .contains("without recording an outcome");
        assertThat(status(withBudgetLeft)).isEqualTo("CLAIMED");
        assertThat(status(stillLeased)).isEqualTo("CLAIMED");
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM platform.outbox_event WHERE event_id = ?", String.class, eventId);
    }

    private UUID insertClaimed(int attempts, String leaseOffset) {
        UUID eventId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                """
                INSERT INTO platform.outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, aggregate_version,
                    payload, occurred_at, available_at, status, attempt_count, claimed_by, claim_token, claimed_until
                ) VALUES (?, 'NOTIFICATION', ?, 'notification.created', 1, 1, '{}'::jsonb, ?, ?, 'CLAIMED', ?,
                          'relay-1', ?, now() + ?::interval)
                """,
                eventId,
                eventId.toString(),
                now,
                now,
                attempts,
                UUID.randomUUID(),
                leaseOffset);
        return eventId;
    }
}

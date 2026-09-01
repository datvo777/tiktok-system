package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.Topics;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Milestone 8 acceptance (brief section 20): outbox relay crash/reclaim,
 * duplicate-delivery-has-one-effect, and inbox retention. Each test reproduces,
 * as an automated regression, a scenario that was also verified live against a
 * running local stack during development (a crashed relay's claim really did
 * get reclaimed and published; a redelivered event really did produce exactly
 * one notification; the cleanup job really did prune only rows past its
 * retention window) -- see the Milestone 8 summary for that live evidence.
 *
 * <p>Not runnable in every environment: this class needs a working Docker
 * daemon for Testcontainers' Postgres and Kafka containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ResilienceIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.4-alpine")
            .withDatabaseName("short_video")
            .withUsername("short_video_app")
            .withPassword("short_video_app");

    @Container
    @SuppressWarnings("resource")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("shortvideo.outbox.enabled", () -> "true");
        registry.add("shortvideo.outbox.poll-interval", () -> "200ms");
        // Fast enough to observe within a test timeout without weakening the
        // real production default anywhere outside this test.
        registry.add("shortvideo.inbox.cleanup-interval", () -> "500ms");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * A relay claims a batch, then crashes before publishing or renewing its
     * lease -- simulated here by inserting a row already in that exact state.
     * The still-running relay must treat the expired lease as reclaimable on
     * its very next poll (brief section 20: "outbox relay crash/reclaim...
     * tests pass").
     */
    @Test
    void reclaimsAnEventClaimedByARelayThatCrashedBeforePublishing() {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO platform.outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, aggregate_version,
                    payload, occurred_at, available_at, status, attempt_count,
                    claimed_by, claim_token, claimed_until
                ) VALUES (?, 'NOTIFICATION', ?, 'notification.created', 1, 999999,
                    '{}'::jsonb, now() - interval '5 minutes', now() - interval '5 minutes',
                    'CLAIMED', 0, 'dead-relay-simulating-a-crash', ?, now() - interval '1 minute')
                """,
                eventId,
                eventId.toString(),
                UUID.randomUUID());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, claimed_by, published_at FROM platform.outbox_event WHERE event_id = ?", eventId);
            assertThat(row.get("status")).isEqualTo("PUBLISHED");
            assertThat(row.get("claimed_by")).isNull();
            assertThat(row.get("published_at")).isNotNull();
        });
    }

    /**
     * Redelivering the exact same event (same eventId) must never double-apply
     * its effect (Rule 5, brief section 20: "duplicate notification delivery
     * has one user-visible effect"). Published directly to Kafka -- unlike a
     * real crash-and-redeliver, this does not depend on timing, so the
     * assertion is exact rather than "eventually consistent."
     */
    @Test
    void redeliveringTheSameEventIdProducesExactlyOneNotification() throws Exception {
        String recipientId = UUID.randomUUID().toString();
        String videoId = UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "videoId", videoId,
                "creatorId", recipientId,
                "state", "REJECTED",
                "aggregateVersion", 1,
                "reason", "resilience-test");
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                eventId,
                EventTypes.VIDEO_MODERATION_REJECTED,
                1,
                "MODERATION",
                videoId,
                1L,
                Instant.now(),
                "resilience-it",
                "test",
                null,
                null,
                payload);
        String json = objectMapper.writeValueAsString(envelope);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(Topics.VIDEO_EVENTS, videoId, json)).get();
            // Redelivery: the exact same message, same eventId, sent again.
            producer.send(new ProducerRecord<>(Topics.VIDEO_EVENTS, videoId, json)).get();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM notification.notification WHERE recipient_account_id = ?::uuid",
                    Integer.class,
                    recipientId);
            assertThat(count).isEqualTo(1);
        });

        // Give a genuine duplicate every chance to slip through before declaring success.
        Thread.sleep(2000);
        Integer finalCount = jdbc.queryForObject(
                "SELECT count(*) FROM notification.notification WHERE recipient_account_id = ?::uuid",
                Integer.class,
                recipientId);
        assertThat(finalCount).isEqualTo(1);
    }

    /**
     * The cleanup job must prune rows past its retention window and leave
     * recent ones alone -- pruning everything (or nothing) would both pass a
     * sloppier test than this one.
     */
    @Test
    void inboxCleanupPrunesOnlyRowsPastRetentionAndKeepsRecentOnes() {
        UUID oldEventId = UUID.randomUUID();
        UUID recentEventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO platform.consumed_event (consumer_name, event_id, processed_at) VALUES (?, ?, ?)",
                "resilience-it-consumer",
                oldEventId,
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        jdbc.update(
                "INSERT INTO platform.consumed_event (consumer_name, event_id, processed_at) VALUES (?, ?, ?)",
                "resilience-it-consumer",
                recentEventId,
                Timestamp.from(Instant.now()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer oldRemaining = jdbc.queryForObject(
                    "SELECT count(*) FROM platform.consumed_event WHERE event_id = ?", Integer.class, oldEventId);
            assertThat(oldRemaining).isZero();
        });

        Integer recentRemaining = jdbc.queryForObject(
                "SELECT count(*) FROM platform.consumed_event WHERE event_id = ?", Integer.class, recentEventId);
        assertThat(recentRemaining).isEqualTo(1);
    }

    private Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return props;
    }
}

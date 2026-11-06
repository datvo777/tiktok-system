package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The listener container acks once per poll, after every record in it has been applied. What that
 * must still guarantee when one record in the middle of a poll fails for good: the records around
 * it are each applied exactly once, the failed one is dead-lettered and never applied, and the
 * group's offset moves past all of them instead of the poll being redelivered forever.
 *
 * <p>Uses the production container factory and error handler. The topic has three partitions and its
 * dead-letter topic one, as in the Compose stack, and every record goes to partition 2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class BatchAckIT {

    static final String TOPIC = "it.batch";
    static final String GROUP = "it-batch-group";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.4-alpine")
            .withDatabaseName("short_video").withUsername("short_video_app").withPassword("short_video_app");

    @Container
    @SuppressWarnings("resource")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("shortvideo.outbox.enabled", () -> "false");
    }

    static final Map<String, AtomicInteger> APPLIED = new ConcurrentHashMap<>();
    static final AtomicInteger ATTEMPTS = new AtomicInteger();
    static final List<String> DEAD = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Config {
        @Bean NewTopic topic() { return TopicBuilder.name(TOPIC).partitions(3).build(); }
        @Bean NewTopic dlt() { return TopicBuilder.name(TOPIC + ".DLT").partitions(1).build(); }
        @Bean Listeners listeners(com.shortvideo.shared.inbox.InboxGuard inbox) { return new Listeners(inbox); }
    }

    static class Listeners {
        final com.shortvideo.shared.inbox.InboxGuard inbox;
        Listeners(com.shortvideo.shared.inbox.InboxGuard inbox) { this.inbox = inbox; }

        /** Same shape as the real listeners: one transaction, inbox claim first, a failure rolls it all back. */
        @KafkaListener(topics = TOPIC, groupId = GROUP)
        @Transactional
        public void onRecord(String payload) {
            ATTEMPTS.incrementAndGet();
            String[] parts = payload.split(":");
            if (inbox.claim("it-batch", UUID.nameUUIDFromBytes(payload.getBytes()))) {
                if (parts[0].equals("poison")) {
                    // Not retryable in the production handler, so it goes straight to the DLT.
                    throw new IllegalArgumentException("cannot ever succeed");
                }
                APPLIED.computeIfAbsent(payload, k -> new AtomicInteger()).incrementAndGet();
            }
        }

        @KafkaListener(topics = TOPIC + ".DLT", groupId = "it-batch-dlt")
        public void onDead(String payload) {
            DEAD.add(payload);
        }
    }

    @Autowired KafkaTemplate<String, String> template;

    @Test
    void aRecordFailingForGoodMidPollIsDeadLetteredAndTheRestAreAppliedOnce() throws Exception {
        Thread.sleep(8000); // let the containers join their groups
        List<String> payloads = List.of("ok:1", "ok:2", "poison:3", "ok:4", "ok:5");
        for (String payload : payloads) {
            template.send(TOPIC, 2, "same-key", payload).get();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(APPLIED.keySet()).containsExactlyInAnyOrder("ok:1", "ok:2", "ok:4", "ok:5");
            assertThat(DEAD).containsExactly("poison:3");
        });

        APPLIED.values().forEach(count -> assertThat(count).hasValue(1));
        assertThat(APPLIED).doesNotContainKey("poison:3");

        // The offset moved past all five, so nothing is being redelivered.
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                Map<TopicPartition, OffsetAndMetadata> offsets =
                        admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
                assertThat(offsets.get(new TopicPartition(TOPIC, 2)).offset()).isEqualTo(5);
            });
        }
    }
}

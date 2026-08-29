package com.shortvideo.shared.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Single relay process for the local MVP (brief section 11).
 *
 * <p>Ordering convention: one relay + deterministic selection + aggregate-id key +
 * aggregate-version submission order + idempotent producer. That reduces normal
 * reordering; it is not the correctness proof. Consumers still deduplicate through
 * a durable inbox and compare aggregate versions (Rule 13).
 *
 * <p>Delivery is at-least-once. This relay can publish successfully and die before
 * recording success, which republishes after the lease expires. That is expected.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TopicResolver topicResolver;
    private final OutboxProperties properties;
    private final Counter published;
    private final Counter failed;
    private final Counter dead;

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            TopicResolver topicResolver,
            OutboxProperties properties,
            MeterRegistry meters) {
        this.repository = repository;
        this.kafka = kafka;
        this.topicResolver = topicResolver;
        this.properties = properties;
        this.published = Counter.builder("outbox.events.published").register(meters);
        this.failed = Counter.builder("outbox.events.failed").register(meters);
        this.dead = Counter.builder("outbox.events.dead").register(meters);
    }

    @Scheduled(fixedDelayString = "${shortvideo.outbox.poll-interval:500ms}")
    public void drain() {
        UUID claimToken = UUID.randomUUID();
        List<OutboxRecord> batch;
        try {
            batch = repository.claimBatch(
                    properties.getRelayId(), claimToken, properties.getBatchSize(), properties.getLease().toSeconds());
        } catch (RuntimeException e) {
            log.warn("Outbox claim failed; will retry on next poll", e);
            return;
        }
        if (batch.isEmpty()) {
            return;
        }

        // SKIP LOCKED does not guarantee the returned set preserves ORDER BY, so
        // re-sort before submitting (brief section 10).
        List<OutboxRecord> ordered = batch.stream()
                .sorted(Comparator.comparing(OutboxRecord::occurredAt)
                        .thenComparing(OutboxRecord::aggregateId)
                        .thenComparingLong(OutboxRecord::aggregateVersion))
                .toList();

        for (OutboxRecord record : ordered) {
            publish(record);
        }
    }

    private void publish(OutboxRecord record) {
        try {
            String topic = topicResolver.topicFor(record);
            kafka.send(topic, record.aggregateId(), record.payloadJson())
                    .get(properties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);

            // Finalise only if we still own the claim.
            if (repository.markPublished(record.eventId(), record.claimToken())) {
                published.increment();
            } else {
                log.warn("Lost claim on {} before finalisation; another relay may republish", record.eventId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(record, "interrupted");
        } catch (Exception e) {
            recordFailure(record, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void recordFailure(OutboxRecord record, String error) {
        boolean exhausted = record.attemptCount() >= properties.getMaxAttempts();
        Instant retryAt = Instant.now().plus(backoff(record.attemptCount()));
        repository.markFailed(record.eventId(), record.claimToken(), error, retryAt, exhausted);
        if (exhausted) {
            dead.increment();
            log.error("Outbox event {} exhausted {} attempts and moved to DEAD: {}",
                    record.eventId(), record.attemptCount(), error);
        } else {
            failed.increment();
            log.warn("Outbox event {} attempt {} failed, retrying at {}: {}",
                    record.eventId(), record.attemptCount(), retryAt, error);
        }
    }

    /** Bounded exponential backoff. */
    private Duration backoff(int attemptCount) {
        long seconds = (long) Math.min(Math.pow(2, Math.min(attemptCount, 20)), properties.getMaxBackoff().toSeconds());
        return Duration.ofSeconds(Math.max(1, seconds));
    }
}

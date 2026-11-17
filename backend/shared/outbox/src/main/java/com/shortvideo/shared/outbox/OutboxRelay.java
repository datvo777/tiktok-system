package com.shortvideo.shared.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
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
 *
 * <p>Throughput comes from putting many aggregates in flight at once; one aggregate's events are
 * sent one at a time, each waiting for its acknowledgement, which is what keeps them in order. Measured
 * locally that is about 2,000 events/s across many aggregates but about 125/s for a single one, so a
 * producer that emits many events under one aggregate id would be limited to that. None does today
 * (social events use a fresh id each, the others carry a few per aggregate); if one is added, either
 * give its events distinct ids or decide that type does not need per-aggregate ordering.
 *
 * <p>A batch is processed within its lease: no round starts unless it can finish inside it,
 * and what is left is handed back un-sent. Otherwise a slow broker could let the lease lapse
 * mid-batch, and rows this relay is still sending would become claimable by another.
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
    private final LongSupplier nanoClock;

    /** Share of the lease a batch may use; the rest is margin for finalising and for clock skew. */
    private static final double LEASE_BUDGET = 0.9;

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            TopicResolver topicResolver,
            OutboxProperties properties,
            MeterRegistry meters) {
        this(repository, kafka, topicResolver, properties, meters, System::nanoTime);
    }

    OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            TopicResolver topicResolver,
            OutboxProperties properties,
            MeterRegistry meters,
            LongSupplier nanoClock) {
        // One round must fit in the lease with room to spare, or no batch could ever finish in time.
        if (properties.getLease().compareTo(properties.getPublishTimeout().multipliedBy(2)) < 0) {
            throw new IllegalStateException(
                    "shortvideo.outbox.lease (" + properties.getLease() + ") must be at least twice "
                            + "shortvideo.outbox.publish-timeout (" + properties.getPublishTimeout() + ")");
        }
        this.nanoClock = nanoClock;
        this.repository = repository;
        this.kafka = kafka;
        this.topicResolver = topicResolver;
        this.properties = properties;
        this.published = Counter.builder("outbox.events.published").register(meters);
        this.failed = Counter.builder("outbox.events.failed").register(meters);
        this.dead = Counter.builder("outbox.events.dead").register(meters);
    }

    /** Bounds one scheduled run, so a permanent backlog cannot keep this thread in the loop forever. */
    private static final int MAX_BATCHES_PER_RUN = 20;

    @Scheduled(fixedDelayString = "${shortvideo.outbox.poll-interval:500ms}")
    public void drain() {
        buryAbandoned();
        // A full batch means there is more behind it: carry straight on instead of idling for the
        // poll interval, which would cap throughput at one batch per interval.
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            if (drainOnce() < properties.getBatchSize()) {
                return;
            }
        }
    }

    /**
     * A row left CLAIMED by a relay that died mid-send is reclaimed after its lease, counting an
     * attempt each time. If every one of those attempts killed the relay, nothing ever reaches
     * {@link #recordFailure}, so the budget is enforced here instead.
     */
    private void buryAbandoned() {
        try {
            int buried = repository.buryAbandoned(properties.getMaxAttempts());
            if (buried > 0) {
                dead.increment(buried);
                log.error("{} outbox event(s) were claimed {} times without the relay recording an outcome and were "
                        + "moved to DEAD; a payload that crashes the relay is the usual cause",
                        buried, properties.getMaxAttempts());
            }
        } catch (RuntimeException e) {
            log.warn("Could not check for abandoned outbox claims; will retry on next poll", e);
        }
    }

    /** @return how many rows were claimed; zero if the claim itself failed. */
    private int drainOnce() {
        UUID claimToken = UUID.randomUUID();
        List<OutboxRecord> batch;
        try {
            batch = repository.claimBatch(
                    properties.getRelayId(), claimToken, properties.getBatchSize(), properties.getLease().toSeconds());
        } catch (RuntimeException e) {
            log.warn("Outbox claim failed; will retry on next poll", e);
            return 0;
        }
        if (batch.isEmpty()) {
            return 0;
        }

        // SKIP LOCKED does not guarantee the returned set preserves ORDER BY, so
        // re-sort before submitting (brief section 10).
        List<OutboxRecord> ordered = batch.stream()
                .sorted(Comparator.comparing(OutboxRecord::occurredAt)
                        .thenComparing(OutboxRecord::aggregateId)
                        .thenComparingLong(OutboxRecord::aggregateVersion))
                .toList();

        publishBatch(ordered, claimToken);
        return batch.size();
    }

    /**
     * Sends in rounds: each round puts the next event of every aggregate in flight at once, so the
     * producer can batch them, then waits for that round's acknowledgements and finalises the
     * acknowledged ones in a single statement.
     *
     * <p>An aggregate's events go in separate rounds, so an event is never sent while an earlier one
     * of the same aggregate is still unacknowledged. When one fails, the rest of that aggregate's
     * events in this batch are released un-sent, to come back with it, rather than overtaking it.
     */
    private void publishBatch(List<OutboxRecord> ordered, UUID claimToken) {
        Map<String, Deque<OutboxRecord>> remaining = new LinkedHashMap<>();
        for (OutboxRecord record : ordered) {
            remaining.computeIfAbsent(record.aggregateId(), id -> new ArrayDeque<>()).add(record);
        }

        long startedAt = nanoClock.getAsLong();
        long leaseBudgetNanos = (long) (properties.getLease().toNanos() * LEASE_BUDGET);
        long roundNanos = properties.getPublishTimeout().toNanos();
        while (!remaining.isEmpty()) {
            if (nanoClock.getAsLong() - startedAt + roundNanos > leaseBudgetNanos) {
                // Another round might not finish before the lease runs out. Hand the rest back
                // un-sent (no attempt counted) rather than risk sending rows another relay can claim.
                List<UUID> unsent = remaining.values().stream()
                        .flatMap(Deque::stream)
                        .map(OutboxRecord::eventId)
                        .toList();
                repository.release(unsent, claimToken, Instant.now());
                log.info("Outbox batch used its lease budget; released {} unsent event(s) for the next claim", unsent.size());
                return;
            }
            List<InFlight> round = new ArrayList<>(remaining.size());
            for (Iterator<Map.Entry<String, Deque<OutboxRecord>>> it = remaining.entrySet().iterator(); it.hasNext(); ) {
                Deque<OutboxRecord> events = it.next().getValue();
                round.add(send(events.pollFirst()));
                if (events.isEmpty()) {
                    it.remove();
                }
            }

            List<UUID> acknowledged = new ArrayList<>();
            long deadline = System.nanoTime() + properties.getPublishTimeout().toNanos();
            boolean interrupted = false;
            for (InFlight sent : round) {
                Ack ack = interrupted ? Ack.cutShort() : await(sent, deadline);
                interrupted |= ack.interrupted();
                if (ack.error() == null) {
                    acknowledged.add(sent.record().eventId());
                    continue;
                }
                Instant retryAt = recordFailure(sent.record(), ack.error());
                Deque<OutboxRecord> held = remaining.remove(sent.record().aggregateId());
                if (held != null && !held.isEmpty()) {
                    repository.release(
                            held.stream().map(OutboxRecord::eventId).toList(),
                            claimToken,
                            retryAt != null ? retryAt : Instant.now());
                }
            }

            int finalised = repository.markPublishedBatch(acknowledged, claimToken);
            published.increment(finalised);
            if (finalised != acknowledged.size()) {
                log.warn("Lost claim on {} of {} acknowledged events before finalisation; another relay may republish them",
                        acknowledged.size() - finalised, acknowledged.size());
            }
            if (interrupted) {
                return; // what is left stays claimed and is reclaimed when its lease expires
            }
        }
    }

    private InFlight send(OutboxRecord record) {
        try {
            String topic = topicResolver.topicFor(record);
            return new InFlight(record, kafka.send(topic, record.aggregateId(), record.payloadJson()), null);
        } catch (Exception e) {
            return new InFlight(record, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** @return the outcome of one send: acknowledged, failed with a reason, or cut short by an interrupt */
    private Ack await(InFlight sent, long deadlineNanos) {
        if (sent.sendError() != null) {
            return Ack.failed(sent.sendError());
        }
        try {
            sent.future().get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            return Ack.ACKNOWLEDGED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Ack.cutShort();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Ack.failed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (Exception e) {
            return Ack.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** {@code error} is null when acknowledged. */
    private record Ack(String error, boolean interrupted) {
        static final Ack ACKNOWLEDGED = new Ack(null, false);

        static Ack failed(String error) {
            return new Ack(error, false);
        }

        static Ack cutShort() {
            return new Ack("interrupted", true);
        }
    }

    private record InFlight(OutboxRecord record, CompletableFuture<?> future, String sendError) {}

    /** @return when the event will next be tried, or null if this relay no longer owned the claim */
    private Instant recordFailure(OutboxRecord record, String error) {
        boolean exhausted = record.attemptCount() >= properties.getMaxAttempts();
        Instant retryAt = Instant.now().plus(backoff(record.attemptCount()));
        boolean applied = repository.markFailed(
                record.eventId(), record.claimToken(), error, retryAt, exhausted);

        if (!applied) {
            log.warn("Lost claim on {} before recording failure; another relay owns it now", record.eventId());
            return null;
        }

        if (exhausted) {
            dead.increment();
            log.error("Outbox event {} exhausted {} attempts and moved to DEAD: {}",
                    record.eventId(), record.attemptCount(), error);
        } else {
            failed.increment();
            log.warn("Outbox event {} attempt {} failed, retrying at {}: {}",
                    record.eventId(), record.attemptCount(), retryAt, error);
        }
        return retryAt;
    }

    /** Bounded exponential backoff. */
    private Duration backoff(int attemptCount) {
        long seconds = (long) Math.min(Math.pow(2, Math.min(attemptCount, 20)), properties.getMaxBackoff().toSeconds());
        return Duration.ofSeconds(Math.max(1, seconds));
    }
}

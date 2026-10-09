package com.shortvideo.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxRelayTest {

    /** The token the relay passes to {@code claimBatch}; the fake database stamps it on the rows it returns. */
    private UUID token;

    private final OutboxRepository repository = mock(OutboxRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final TopicResolver topics = mock(TopicResolver.class);
    private final OutboxProperties properties = new OutboxProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(repository, kafka, topics, properties, meters);

    private static OutboxRecord record(String aggregateId, long version, long millis) {
        return new OutboxRecord(
                UUID.randomUUID(), "VIDEO", aggregateId, "video.x", 1, version, "{}",
                Instant.ofEpochMilli(millis), 1, null);
    }

    /** Each successive claim returns the next batch, stamped with the token the relay claimed under. */
    @SafeVarargs
    private void claims(List<OutboxRecord>... batches) {
        int[] call = {0};
        when(repository.claimBatch(anyString(), any(), anyInt(), anyLong())).thenAnswer(invocation -> {
            token = invocation.getArgument(1);
            if (call[0] >= batches.length) {
                return List.of();
            }
            return batches[call[0]++].stream().map(r -> withToken(r, token)).toList();
        });
        when(topics.topicFor(any())).thenReturn("topic");
    }

    private static OutboxRecord withToken(OutboxRecord r, UUID token) {
        return new OutboxRecord(
                r.eventId(), r.aggregateType(), r.aggregateId(), r.eventType(), r.schemaVersion(),
                r.aggregateVersion(), r.payloadJson(), r.occurredAt(), r.attemptCount(), token);
    }

    private void sendSucceeds() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void everyAcknowledgedEventIsFinalisedInOneStatement() {
        OutboxRecord a = record("a", 1, 1);
        OutboxRecord b = record("b", 1, 2);
        claims(List.of(a, b));
        sendSucceeds();
        when(repository.markPublishedBatch(anyList(), any())).thenReturn(2);

        relay.drain();

        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).markPublishedBatch(ids.capture(), eq(token));
        assertThat(ids.getValue()).containsExactlyInAnyOrder(a.eventId(), b.eventId());
        assertThat(meters.get("outbox.events.published").counter().count()).isEqualTo(2.0);
    }

    @Test
    void anAggregatesLaterEventIsHeldBackWhenAnEarlierOneFails() {
        OutboxRecord v1 = record("a", 1, 1);
        OutboxRecord v2 = record("a", 2, 2);
        OutboxRecord other = record("b", 1, 3);
        claims(List.of(v1, v2, other));
        when(kafka.send(anyString(), eq("a"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        when(kafka.send(anyString(), eq("b"), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markFailed(any(), any(), anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
        when(repository.markPublishedBatch(anyList(), any())).thenReturn(1);

        relay.drain();

        // v2 was never sent: sending it would let it overtake v1, which is coming back for a retry.
        verify(kafka, times(2)).send(anyString(), anyString(), anyString());
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).markFailed(eq(v1.eventId()), eq(token), anyString(), retryAt.capture(), eq(false));
        // ...and is released to become available at the same moment as v1, so they return together.
        ArgumentCaptor<List<UUID>> released = ArgumentCaptor.forClass(List.class);
        verify(repository).release(released.capture(), eq(token), eq(retryAt.getValue()));
        assertThat(released.getValue()).containsExactly(v2.eventId());
        // The unrelated aggregate is unaffected.
        ArgumentCaptor<List<UUID>> published = ArgumentCaptor.forClass(List.class);
        verify(repository).markPublishedBatch(published.capture(), eq(token));
        assertThat(published.getValue()).containsExactly(other.eventId());
    }

    @Test
    void anAggregatesEventsAreSentInOrderAcrossRoundsNotAllAtOnce() {
        OutboxRecord v1 = record("a", 1, 1);
        OutboxRecord v2 = record("a", 2, 2);
        claims(List.of(v1, v2));
        List<String> order = new ArrayList<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            order.add("send");
            return CompletableFuture.completedFuture(null);
        });
        when(repository.markPublishedBatch(anyList(), any())).thenAnswer(call -> {
            order.add("finalise");
            return ((List<?>) call.getArgument(0)).size();
        });

        relay.drain();

        // v2 goes out only after v1's round was acknowledged and finalised.
        assertThat(order).containsExactly("send", "finalise", "send", "finalise");
    }

    @Test
    void aFullBatchIsFollowedStraightAwayByAnotherClaim() {
        properties.setBatchSize(2);
        OutboxRecord a = record("a", 1, 1);
        OutboxRecord b = record("b", 1, 2);
        OutboxRecord c = record("c", 1, 3);
        claims(List.of(a, b), List.of(c));
        sendSucceeds();
        when(repository.markPublishedBatch(anyList(), any())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());

        relay.drain();

        // The second claim returned fewer than a full batch, so the run stops there.
        verify(repository, times(2)).claimBatch(anyString(), any(), anyInt(), anyLong());
    }

    @Test
    void aShortBatchEndsTheRun() {
        OutboxRecord a = record("a", 1, 1);
        claims(List.of(a));
        sendSucceeds();
        when(repository.markPublishedBatch(anyList(), any())).thenReturn(1);

        relay.drain();

        verify(repository, times(1)).claimBatch(anyString(), any(), anyInt(), anyLong());
        verify(repository, never()).release(anyList(), any(), any());
    }

    @Test
    void aBatchThatHasUsedItsLeaseBudgetReleasesTheRestUnsent() {
        OutboxRecord v1 = record("a", 1, 1);
        OutboxRecord v2 = record("a", 2, 2);
        claims(List.of(v1, v2));
        AtomicLong clock = new AtomicLong();
        OutboxRelay timed = new OutboxRelay(repository, kafka, topics, properties, meters, clock::get);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(40)); // a slow round: 40s of the 60s lease
            return CompletableFuture.completedFuture(null);
        });
        when(repository.markPublishedBatch(anyList(), any())).thenReturn(1);

        timed.drain();

        // Another 30s round could outlast the lease, so v2 is handed back rather than sent.
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        ArgumentCaptor<List<UUID>> released = ArgumentCaptor.forClass(List.class);
        verify(repository).release(released.capture(), eq(token), any(Instant.class));
        assertThat(released.getValue()).containsExactly(v2.eventId());
    }

    @Test
    void aLeaseTooShortForOneRoundIsRejectedAtStartup() {
        properties.setLease(Duration.ofSeconds(40));
        properties.setPublishTimeout(Duration.ofSeconds(30));

        assertThatThrownBy(() -> new OutboxRelay(repository, kafka, topics, properties, meters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease");
    }

    @Test
    void eventsTheRelayKeepsClaimingWithoutSettlingAreBuriedBeforeTheNextClaim() {
        claims();
        when(repository.buryAbandoned(properties.getMaxAttempts())).thenReturn(2);

        relay.drain();

        var order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).buryAbandoned(properties.getMaxAttempts());
        order.verify(repository).claimBatch(anyString(), any(), anyInt(), anyLong());
        assertThat(meters.get("outbox.events.dead").counter().count()).isEqualTo(2.0);
    }

    @Test
    void aFailureToBuryDoesNotStopTheRelayFromPublishing() {
        claims();
        when(repository.buryAbandoned(anyInt())).thenThrow(new IllegalStateException("db down"));

        relay.drain();

        verify(repository).claimBatch(anyString(), any(), anyInt(), anyLong());
    }
}

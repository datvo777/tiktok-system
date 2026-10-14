package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.events.MediaEvents;
import com.shortvideo.shared.inbox.InboxGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MediaResultListenerTest {

    private final InboxGuard inbox = mock(InboxGuard.class);
    private final VideoService videoService = mock(VideoService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    // Like the application's mapper: the envelope serialises a derived field it does not read back.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final MediaResultListener listener = new MediaResultListener(
            inbox, videoService, mapper, new TransactionTemplate(transactionManager), meters);

    private final UUID eventId = UUID.randomUUID();

    private String payload() throws Exception {
        var result = new MediaEvents.MediaResultCommand(
                "v1:1", "v1", 1, "COMPLETED",
                new MediaEvents.Assets("processed/v1/1/master.m3u8", List.of("processed/v1/1/high/index.m3u8"), 3, 4.0),
                null);
        var envelope = new EventEnvelope<>(
                eventId, EventTypes.MEDIA_RESULT_REPORTED, 1, AggregateTypes.VIDEO, "v1", null, Instant.now(),
                "media-worker", "media-worker", "corr", null, result);
        return mapper.writeValueAsString(envelope);
    }

    @Test
    void theAssetsAreCheckedBeforeAnyTransactionOpensAndTheClaimShareOneWithTheUpdate() throws Exception {
        when(videoService.verifyAssets(any())).thenReturn(true);
        when(inbox.claim(any(), any())).thenReturn(true);

        listener.onMediaResult(payload());

        InOrder order = inOrder(videoService, transactionManager, inbox);
        order.verify(videoService).verifyAssets(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(inbox).claim(any(), any());
        order.verify(videoService).applyMediaResult(any(), org.mockito.ArgumentMatchers.eq(true));
        order.verify(transactionManager).commit(any());
    }

    @Test
    void aStoreThatCannotBeAskedOpensNoTransactionAndClaimsNothingSoTheDeliveryIsRetried() throws Exception {
        when(videoService.verifyAssets(any()))
                .thenThrow(new VideoExceptions.AssetStoreUnavailable("MinIO down", new RuntimeException()));

        assertThatThrownBy(() -> listener.onMediaResult(payload())).hasRootCauseInstanceOf(RuntimeException.class);

        verify(transactionManager, never()).getTransaction(any());
        verify(inbox, never()).claim(any(), any());
        verify(videoService, never()).applyMediaResult(any(), any());
    }

    @Test
    void aResultThatWasAlreadyClaimedIsCountedAndNotAppliedAgain() throws Exception {
        when(videoService.verifyAssets(any())).thenReturn(true);
        when(inbox.claim(any(), any())).thenReturn(false);

        listener.onMediaResult(payload());

        verify(videoService, never()).applyMediaResult(any(), any());
        assertThat(meters.counter("video.transcode.result.duplicate").count()).isEqualTo(1.0);
    }

    @Test
    void aFailureWhileApplyingRollsTheClaimBackWithIt() throws Exception {
        when(videoService.verifyAssets(any())).thenReturn(true);
        when(inbox.claim(any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(videoService).applyMediaResult(any(), any());

        assertThatThrownBy(() -> listener.onMediaResult(payload())).isInstanceOf(RuntimeException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }
}

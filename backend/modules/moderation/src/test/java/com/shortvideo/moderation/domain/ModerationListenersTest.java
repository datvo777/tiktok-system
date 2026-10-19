package com.shortvideo.moderation.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.inbox.InboxGuard;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The listeners that feed moderation from {@code video.events.v1}. That topic carries every video
 * event, so what matters is what each does with one it does not want (nothing, and without
 * opening a transaction), with a redelivery (claim refused, so nothing applied), and with a failure
 * (rethrown, so it is redelivered rather than lost).
 */
class ModerationListenersTest {

    private final InboxGuard inbox = mock(InboxGuard.class);
    private final ModerationService service = mock(ModerationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final UploadCompletedListener uploadCompleted =
            new UploadCompletedListener(inbox, service, mapper, transaction);
    private final PublishRequestedListener publishRequested =
            new PublishRequestedListener(inbox, service, mapper, transaction);
    private final VideoRemovedListener videoRemoved = new VideoRemovedListener(inbox, service, mapper, transaction);
    private final AppealDecisionListener appealDecision =
            new AppealDecisionListener(inbox, service, mapper, transaction);

    private String event(String type, Map<String, Object> payload) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), type, 1, AggregateTypes.VIDEO, "v1", 1L, Instant.now(), "p", "m", "c", null, payload));
    }

    private void assertIgnored(Consumer<String> listener, String unrelated) {
        listener.accept(unrelated);

        // No transaction, no claim: an event for someone else must not cost a pooled connection.
        verify(transactionManager, never()).getTransaction(any());
        verify(inbox, never()).claim(anyString(), any());
    }

    // --- upload.completed -> create the pending record

    @Test
    void anUploadCompletionCreatesThePendingRecordAfterClaimingIt() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        uploadCompleted.onVideoEvent(event(EventTypes.VIDEO_UPLOAD_COMPLETED, Map.of("videoId", "v1", "accountId", "a1")));

        InOrder order = inOrder(transactionManager, inbox, service);
        order.verify(transactionManager).getTransaction(any());
        order.verify(inbox).claim(anyString(), any());
        order.verify(service).createPending("v1", "a1");
        order.verify(transactionManager).commit(any());
    }

    @Test
    void aRedeliveredUploadCompletionCreatesNothing() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(false);

        uploadCompleted.onVideoEvent(event(EventTypes.VIDEO_UPLOAD_COMPLETED, Map.of("videoId", "v1", "accountId", "a1")));

        verify(service, never()).createPending(anyString(), anyString());
    }

    @Test
    void aFailureCreatingTheRecordIsRethrownAndRolledBack() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("db")).when(service).createPending(anyString(), anyString());

        assertThatThrownBy(() -> uploadCompleted.onVideoEvent(
                        event(EventTypes.VIDEO_UPLOAD_COMPLETED, Map.of("videoId", "v1", "accountId", "a1"))))
                .isInstanceOf(RuntimeException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void anUnrelatedEventIsIgnoredByTheUploadListener() throws Exception {
        assertIgnored(uploadCompleted::onVideoEvent, event(EventTypes.VIDEO_PROCESSING_READY, Map.of("videoId", "v1")));
    }

    // --- publication.pending -> queue a held video

    @Test
    void aPublishRequestQueuesTheHeldVideo() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        publishRequested.onVideoEvent(event(EventTypes.VIDEO_PUBLICATION_PENDING, Map.of("videoId", "v1")));

        verify(service).requestReview("v1");
    }

    /** The record may not exist yet; the service throws and the listener must let that propagate to be retried. */
    @Test
    void aPublishRequestThatOvertakesTheRecordIsRetried() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("no record yet")).when(service).requestReview(anyString());

        assertThatThrownBy(() -> publishRequested.onVideoEvent(
                        event(EventTypes.VIDEO_PUBLICATION_PENDING, Map.of("videoId", "v1"))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void anUnrelatedEventIsIgnoredByThePublishListener() throws Exception {
        assertIgnored(publishRequested::onVideoEvent, event(EventTypes.VIDEO_PUBLICATION_PUBLISHED, Map.of("videoId", "v1")));
    }

    // --- lifecycle.changed -> close a removed video

    @Test
    void aRemovalClosesTheRecord() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        videoRemoved.onVideoEvent(event(
                EventTypes.VIDEO_LIFECYCLE_CHANGED, Map.of("videoId", "v1", "assetLifecycleState", "DELETE_SCHEDULED")));

        verify(service).closeForRemovedVideo("v1");
    }

    @Test
    void aLifecycleChangeThatIsNotARemovalIsIgnored() throws Exception {
        assertIgnored(
                videoRemoved::onVideoEvent,
                event(EventTypes.VIDEO_LIFECYCLE_CHANGED, Map.of("videoId", "v1", "assetLifecycleState", "ACTIVE")));
        verify(service, never()).closeForRemovedVideo(anyString());
    }

    @Test
    void anUnrelatedEventIsIgnoredByTheRemovalListener() throws Exception {
        assertIgnored(videoRemoved::onVideoEvent, event(EventTypes.VIDEO_UPLOAD_COMPLETED, Map.of("videoId", "v1")));
    }

    // --- appeal.approved -> reinstate

    @Test
    void anApprovedAppealReinstatesTheVideo() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        appealDecision.onVideoEvent(event(EventTypes.VIDEO_APPEAL_APPROVED, Map.of("videoId", "v1")));

        verify(service).reinstateFromAppeal("v1");
    }

    @Test
    void aRedeliveredAppealDecisionReinstatesNothing() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(false);

        appealDecision.onVideoEvent(event(EventTypes.VIDEO_APPEAL_APPROVED, Map.of("videoId", "v1")));

        verify(service, never()).reinstateFromAppeal(anyString());
    }

    @Test
    void anUnrelatedEventIsIgnoredByTheAppealListener() throws Exception {
        assertIgnored(appealDecision::onVideoEvent, event(EventTypes.VIDEO_UPLOAD_COMPLETED, Map.of("videoId", "v1")));
    }
}

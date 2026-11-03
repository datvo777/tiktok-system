package com.shortvideo.video.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.inbox.InboxGuard;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationSources;
import com.shortvideo.shared.revocation.RevocationSubjects;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class VideoLifecycleReactionListenerTest {

    private final InboxGuard inbox = mock(InboxGuard.class);
    private final DurableRevocationReader revocations = mock(DurableRevocationReader.class);
    private final VideoService videoService = mock(VideoService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final VideoLifecycleReactionListener listener = new VideoLifecycleReactionListener(
            inbox, revocations, videoService, mapper, new TransactionTemplate(transactionManager));

    private String event(String type) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), type, 1, "VIDEO", "v1", 1L, Instant.now(), "p", "m", "c", null, Map.of("videoId", "v1")));
    }

    private void moderationBlock(boolean active) {
        when(revocations.activeSources(RevocationSubjects.VIDEO, "v1"))
                .thenReturn(active ? Set.of(RevocationSources.MODERATION) : Set.of());
    }

    @Test
    void aRejectionRetainsTheAssetWhileTheBlockIsInForce() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);
        moderationBlock(true);

        listener.onVideoEvent(event(EventTypes.VIDEO_MODERATION_REJECTED));

        verify(videoService).retainAsRejected("v1");
    }

    @Test
    void aReinstatementRestoresTheAssetOnceTheBlockIsLifted() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);
        moderationBlock(false);

        listener.onVideoEvent(event(EventTypes.VIDEO_MODERATION_REINSTATED));

        verify(videoService).restoreAfterReinstatement("v1");
    }

    @Test
    void aRejectionDeliveredAfterTheVideoWasReinstatedLeavesTheAssetAlone() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);
        moderationBlock(false);

        listener.onVideoEvent(event(EventTypes.VIDEO_MODERATION_REJECTED));

        verify(videoService, never()).retainAsRejected(anyString());
    }

    @Test
    void aReinstatementDeliveredAfterTheVideoWasRejectedAgainLeavesTheAssetRetained() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);
        moderationBlock(true);

        listener.onVideoEvent(event(EventTypes.VIDEO_MODERATION_REINSTATED));

        verify(videoService, never()).restoreAfterReinstatement(anyString());
    }

    @Test
    void anUnrelatedEventOpensNoTransactionAndTakesNoInboxRow() throws Exception {
        listener.onVideoEvent(event(EventTypes.VIDEO_PROCESSING_READY));

        verify(transactionManager, never()).getTransaction(any());
        verify(inbox, never()).claim(anyString(), any());
    }
}

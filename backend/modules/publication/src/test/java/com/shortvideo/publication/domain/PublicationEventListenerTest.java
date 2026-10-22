package com.shortvideo.publication.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationEventListenerTest {

    private final InboxGuard inbox = mock(InboxGuard.class);
    private final PublicationService service = mock(PublicationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final PublicationEventListener listener =
            new PublicationEventListener(inbox, service, mapper, new TransactionTemplate(transactionManager));

    private String event(String type, Map<String, Object> payload) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), type, 1, "VIDEO", "v1", 1L, Instant.now(), "p", "m", "c", null, payload));
    }

    /** The coordinator shares the topic with every other listener; an event it has no handler for costs nothing. */
    @Test
    void anEventItHasNoHandlerForOpensNoTransactionAndTakesNoInboxRow() throws Exception {
        listener.onVideoEvent(event(EventTypes.VIDEO_METADATA_SET, Map.of("videoId", "v1")));

        verify(transactionManager, never()).getTransaction(any());
        verify(inbox, never()).claim(anyString(), any());
    }

    @Test
    void aModerationApprovalIsAppliedAfterTheClaim() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        listener.onVideoEvent(event(EventTypes.VIDEO_MODERATION_APPROVED, Map.of("videoId", "v1")));

        verify(service).onModerationApproved("v1");
    }

    @Test
    void aRedeliveryAppliesNothing() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(false);

        listener.onVideoEvent(event(EventTypes.VIDEO_MODERATION_APPROVED, Map.of("videoId", "v1")));

        verify(service, never()).onModerationApproved(anyString());
    }

    @Test
    void anUploadWithNoAccountIsRefusedNotDraftedForTheTextNull() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        assertThatThrownBy(() -> listener.onVideoEvent(event(EventTypes.VIDEO_UPLOAD_COMPLETED, Map.of("videoId", "v1"))))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        verify(service, never()).ensureDraft(anyString(), anyString());
    }
}

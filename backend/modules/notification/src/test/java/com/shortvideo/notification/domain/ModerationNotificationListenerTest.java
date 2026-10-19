package com.shortvideo.notification.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

class ModerationNotificationListenerTest {

    private final InboxGuard inbox = mock(InboxGuard.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ModerationNotificationListener listener = new ModerationNotificationListener(
            inbox, notifications, mapper, new TransactionTemplate(transactionManager));

    private String event(String type, Map<String, Object> payload) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), type, 1, "VIDEO", "v1", 1L, Instant.now(), "p", "m", "c", null, payload));
    }

    @Test
    void aRejectionTellsTheCreatorWhyWhenAReasonWasGiven() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        listener.onVideoEvent(event(
                EventTypes.VIDEO_MODERATION_REJECTED, Map.of("videoId", "v1", "creatorId", "c1", "reason", "spam")));

        verify(notifications).create(eq("c1"), eq("MODERATION_REJECTED"), contains(": spam"), eq("v1"));
    }

    @Test
    void aRejectionWithNoReasonEndsAfterTheFullStop() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        listener.onVideoEvent(
                event(EventTypes.VIDEO_MODERATION_REJECTED, Map.of("videoId", "v1", "creatorId", "c1")));

        verify(notifications).create(eq("c1"), eq("MODERATION_REJECTED"), eq("Your video was rejected."), eq("v1"));
    }

    @Test
    void anEventItDoesNotNotifyAboutOpensNoTransactionAndTakesNoInboxRow() throws Exception {
        listener.onVideoEvent(event(EventTypes.VIDEO_UPLOAD_COMPLETED, Map.of("videoId", "v1")));

        verify(transactionManager, never()).getTransaction(any());
        verify(inbox, never()).claim(anyString(), any());
    }

    /** No recipient means nobody to tell; better a dead letter than a notification addressed to null. */
    @Test
    void aNotificationWithNoRecipientIsRefused() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        assertThatThrownBy(() -> listener.onVideoEvent(
                        event(EventTypes.VIDEO_MODERATION_REJECTED, Map.of("videoId", "v1"))))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        verify(notifications, never()).create(any(), any(), any(), any());
    }
}

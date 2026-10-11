package com.shortvideo.notification.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.inbox.InboxGuard;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The uploader may have left the page, so processing outcomes also reach their inbox. */
class ProcessingNotificationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // Spring Boot's mapper ignores unknown properties; the envelope serialises a derived one.
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final InboxGuard inbox = mock(InboxGuard.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private ModerationNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new ModerationNotificationListener(inbox, notifications, mapper);
        when(inbox.claim(anyString(), any())).thenReturn(true);
    }

    private String event(String type, Map<String, Object> payload) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), type, 1, "VIDEO", "v1", 1L, Instant.now(), "test", "video", null, null, payload));
    }

    @Test
    void tellsTheOwnerWhenProcessingFinishes() throws Exception {
        listener.onVideoEvent(event(EventTypes.VIDEO_PROCESSING_READY, Map.of("videoId", "v1", "ownerAccountId", "a1")));

        verify(notifications).create(eq("a1"), eq("PROCESSING_READY"), contains("ready"), eq("v1"));
    }

    @Test
    void saysWhetherTryingAgainIsWorthIt() throws Exception {
        listener.onVideoEvent(event(
                EventTypes.VIDEO_PROCESSING_FAILED,
                Map.of("videoId", "v1", "ownerAccountId", "a1", "failureClass", "TRANSIENT")));
        listener.onVideoEvent(event(
                EventTypes.VIDEO_PROCESSING_FAILED,
                Map.of("videoId", "v2", "ownerAccountId", "a1", "failureClass", "TERMINAL")));

        verify(notifications).create(eq("a1"), eq("PROCESSING_FAILED"), contains("Try uploading it again"), eq("v1"));
        verify(notifications).create(eq("a1"), eq("PROCESSING_FAILED"), contains("different file"), eq("v2"));
    }

    @Test
    void aRedeliveredEventDoesNotNotifyTwice() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(false);

        listener.onVideoEvent(event(EventTypes.VIDEO_PROCESSING_READY, Map.of("videoId", "v1", "ownerAccountId", "a1")));

        verify(notifications, never()).create(anyString(), anyString(), anyString(), anyString());
    }
}

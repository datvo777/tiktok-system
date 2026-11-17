package com.shortvideo.notification.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealtimeListenerTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SseRegistry registry = mock(SseRegistry.class);
    private final RealtimeListener listener = new RealtimeListener(registry, mapper);

    private String event(String type, Map<String, Object> payload) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), type, 1, "NOTIFICATION", "n1", 1L, Instant.now(), "test", "notification", null, null, payload));
    }

    @Test
    void aCreatedNotificationBecomesAHintForItsRecipient() throws Exception {
        listener.onNotificationEvent(event(
                EventTypes.NOTIFICATION_CREATED,
                Map.of("notificationId", "n1", "recipientAccountId", "a1", "type", "PROCESSING_READY", "relatedVideoId", "v1")));

        verify(registry).publish(eq("a1"), eq("PROCESSING_READY"), eq("v1"), any());
    }

    @Test
    void otherEventTypesAreIgnored() throws Exception {
        listener.onNotificationEvent(event("something.else", Map.of("recipientAccountId", "a1")));

        verify(registry, never()).publish(any(), any(), any(), any());
    }

    @Test
    void neverThrowsSoAHintCanNotEnterTheRetryAndDeadLetterPath() throws Exception {
        doThrow(new IllegalStateException("boom")).when(registry).publish(any(), any(), any(), any());

        listener.onNotificationEvent(event(
                EventTypes.NOTIFICATION_CREATED, Map.of("recipientAccountId", "a1", "type", "X")));
        listener.onNotificationEvent("not json at all");
    }
}

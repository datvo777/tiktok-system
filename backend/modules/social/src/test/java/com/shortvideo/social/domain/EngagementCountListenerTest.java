package com.shortvideo.social.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EngagementCountListenerTest {

    private final InboxGuard inbox = mock(InboxGuard.class);
    private final SocialRepository repository = mock(SocialRepository.class);
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final EngagementCountListener listener = new EngagementCountListener(inbox, repository, mapper);

    private String event(String eventType, Object payload) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), eventType, 1, AggregateTypes.SOCIAL, UUID.randomUUID().toString(),
                1L, Instant.now(), "short-video-backend", "social", "corr", null, payload));
    }

    @Test
    void appliesTheDeltaOnceItHasClaimedTheEvent() throws Exception {
        when(inbox.claim(eq(EngagementCountListener.CONSUMER), any())).thenReturn(true);

        listener.onSocialEvent(event(
                EventTypes.SOCIAL_VIDEO_ENGAGEMENT_CHANGED, new SocialEvents.EngagementChanged("v1", 1, -2)));

        verify(repository).applyEngagementDelta("v1", 1, -2);
    }

    @Test
    void aRedeliveredEventIsNotAppliedAgain() throws Exception {
        when(inbox.claim(eq(EngagementCountListener.CONSUMER), any())).thenReturn(false);

        listener.onSocialEvent(event(
                EventTypes.SOCIAL_VIDEO_ENGAGEMENT_CHANGED, new SocialEvents.EngagementChanged("v1", 1, 0)));

        verify(repository, never()).applyEngagementDelta(anyString(), anyInt(), anyInt());
    }

    @Test
    void otherSocialEventsAreIgnoredWithoutAnInboxRow() throws Exception {
        listener.onSocialEvent(
                event(EventTypes.SOCIAL_VIDEO_LIKED, new SocialEvents.VideoLiked("v1", "a", "b")));

        verify(inbox, never()).claim(any(), any());
        verify(repository, never()).applyEngagementDelta(anyString(), anyInt(), anyInt());
    }
}

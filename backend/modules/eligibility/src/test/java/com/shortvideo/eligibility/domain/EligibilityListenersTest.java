package com.shortvideo.eligibility.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The projectors that build the eligibility read model from the event topics. They share a topic with
 * every other listener, so an event they do not project must cost nothing, and an event missing a field
 * they cannot do without must be refused rather than projected as the text {@code "null"}.
 */
class EligibilityListenersTest {

    private final InboxGuard inbox = mock(InboxGuard.class);
    private final EligibilityProjectorService projector = mock(EligibilityProjectorService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final VideoEligibilityListener video = new VideoEligibilityListener(inbox, projector, mapper, transaction);
    private final ModerationEligibilityListener moderation =
            new ModerationEligibilityListener(inbox, projector, mapper, transaction);
    private final PublicationEligibilityListener publication =
            new PublicationEligibilityListener(inbox, projector, mapper, transaction);
    private final AccountEligibilityListener account = new AccountEligibilityListener(inbox, projector, mapper, transaction);

    private String event(String type, Map<String, Object> payload) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(
                UUID.randomUUID(), type, 1, "VIDEO", "v1", 3L, Instant.now(), "p", "m", "c", null, payload));
    }

    private void assertIgnored(Consumer<String> listener, String unrelated) {
        listener.accept(unrelated);

        verify(transactionManager, never()).getTransaction(any());
        verify(inbox, never()).claim(anyString(), any());
    }

    private void assertRefusedAsMalformed(Consumer<String> listener, String malformed) {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        assertThatThrownBy(() -> listener.accept(malformed))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        verify(transactionManager).rollback(any());
    }

    @Test
    void theVideoProjectorIgnoresEventsItDoesNotProject() throws Exception {
        assertIgnored(video::onVideoEvent, event(EventTypes.VIDEO_MODERATION_APPROVED, Map.of("videoId", "v1")));
    }

    @Test
    void theVideoProjectorAppliesMetadataAfterClaimingIt() throws Exception {
        when(inbox.claim(anyString(), any())).thenReturn(true);

        video.onVideoEvent(event(
                EventTypes.VIDEO_METADATA_SET, Map.of("videoId", "v1", "ownerAccountId", "a1", "title", "t")));

        verify(projector).applyMetadata("v1", "a1", "t", null, 3L);
    }

    @Test
    void theVideoProjectorRefusesAnEventWithNoVideoId() throws Exception {
        assertRefusedAsMalformed(
                video::onVideoEvent, event(EventTypes.VIDEO_METADATA_SET, Map.of("ownerAccountId", "a1", "title", "t")));
        verify(projector, never()).applyMetadata(any(), any(), any(), any(), anyLong());
    }

    @Test
    void theModerationProjectorIgnoresEventsItDoesNotProject() throws Exception {
        assertIgnored(moderation::onVideoEvent, event(EventTypes.VIDEO_PROCESSING_READY, Map.of("videoId", "v1")));
    }

    @Test
    void theModerationProjectorRefusesAnEventWithNoState() throws Exception {
        assertRefusedAsMalformed(
                moderation::onVideoEvent,
                event(EventTypes.VIDEO_MODERATION_APPROVED, Map.of("videoId", "v1", "creatorId", "c1")));
    }

    @Test
    void thePublicationProjectorIgnoresEventsItDoesNotProject() throws Exception {
        assertIgnored(publication::onVideoEvent, event(EventTypes.VIDEO_MODERATION_APPROVED, Map.of("videoId", "v1")));
    }

    @Test
    void thePublicationProjectorRefusesAnEventWithNoOwner() throws Exception {
        assertRefusedAsMalformed(
                publication::onVideoEvent,
                event(EventTypes.VIDEO_PUBLICATION_PUBLISHED, Map.of("videoId", "v1", "state", "PUBLISHED")));
    }

    @Test
    void theAccountProjectorIgnoresEventsItDoesNotProject() throws Exception {
        assertIgnored(account::onAccountEvent, event("account.profile.updated", Map.of("state", "ACTIVE")));
    }

    @Test
    void theAccountProjectorRefusesAnEventWithNoState() throws Exception {
        assertRefusedAsMalformed(account::onAccountEvent, event(EventTypes.ACCOUNT_SUSPENDED, Map.of()));
    }
}

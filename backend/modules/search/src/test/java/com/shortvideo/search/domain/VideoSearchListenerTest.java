package com.shortvideo.search.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.inbox.InboxGuard;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class VideoSearchListenerTest {

    private final InboxGuard inbox = mock(InboxGuard.class);
    private final SearchIndexService index = mock(SearchIndexService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final VideoSearchListener listener = new VideoSearchListener(
            inbox, index, mock(AccountDirectory.class), mock(EligibilityDirectory.class), mapper,
            new TransactionTemplate(transactionManager));

    private String removed(Long aggregateVersion) throws Exception {
        return event(EventTypes.VIDEO_PUBLICATION_REMOVED, aggregateVersion);
    }

    private String event(String eventType, Long aggregateVersion) throws Exception {
        var envelope = new EventEnvelope<>(
                UUID.randomUUID(), eventType, 1, AggregateTypes.VIDEO, "v1",
                aggregateVersion, Instant.now(), "video", "video", "corr", null, Map.of("videoId", "v1"));
        return mapper.writeValueAsString(envelope);
    }

    @Test
    void aPublishedVideoFallingBackToPendingLeavesTheIndex() throws Exception {
        listener.onVideoEvent(event(EventTypes.VIDEO_PUBLICATION_PENDING, 8L));

        verify(index).removeVideo("v1", 8L);
        verify(inbox).claim(any(), any());
    }

    @Test
    void theIndexIsWrittenBeforeAnyTransactionOpensAndTheClaimGetsOneOfItsOwn() throws Exception {
        listener.onVideoEvent(removed(7L));

        InOrder order = inOrder(index, transactionManager, inbox);
        order.verify(index).removeVideo("v1", 7L);
        order.verify(transactionManager).getTransaction(any());
        order.verify(inbox).claim(any(), any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    void aFailedWriteIsNotClaimedSoTheEventIsRedelivered() throws Exception {
        doThrow(new IllegalStateException("search is down")).when(index).removeVideo(anyString(), anyLong());

        assertThatThrownBy(() -> listener.onVideoEvent(removed(7L))).isInstanceOf(RuntimeException.class);

        verify(inbox, never()).claim(any(), any());
    }

    @Test
    void anEventWithNoVersionIsClaimedWithoutTouchingTheIndex() throws Exception {
        listener.onVideoEvent(removed(null));

        verify(index, never()).removeVideo(anyString(), anyLong());
        verify(inbox).claim(any(), any());
    }
}

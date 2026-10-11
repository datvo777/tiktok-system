package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.MediaEvents;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class TranscodeJobHandlerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final WorkerProperties properties = new WorkerProperties();
    private final TranscodeJobHandler handler = new TranscodeJobHandler(
            mock(MinioObjectStore.class), mock(TranscodePipeline.class), properties, kafka, new ObjectMapper().findAndRegisterModules());

    private static MediaEvents.MediaJobCommand job(int attempt) {
        return new MediaEvents.MediaJobCommand("v1:4", "v1", 4, "sources/v1/original", List.of("720p"), attempt);
    }

    /** The mocked store downloads nothing, so the job fails as a worker fault and reports TRANSIENT. */
    private List<String> reportedPayloads(MediaEvents.MediaJobCommand... jobs) {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        for (MediaEvents.MediaJobCommand j : jobs) {
            handler.handle(j, "corr");
        }
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(jobs.length)).send(anyString(), anyString(), payload.capture());
        return payload.getAllValues();
    }

    private static String eventId(String json) {
        return json.replaceAll("(?s).*\"eventId\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void aRedeliveredCommandReportsWithTheSameEventId() {
        List<String> sent = reportedPayloads(job(1), job(1));

        assertThat(eventId(sent.get(0))).isEqualTo(eventId(sent.get(1)));
    }

    @Test
    void aRetryOfTheSameJobReportsWithItsOwnEventId() {
        List<String> sent = reportedPayloads(job(1), job(2));

        assertThat(eventId(sent.get(0))).isNotEqualTo(eventId(sent.get(1)));
    }

    @Test
    void aCommandFromBeforeTheAttemptFieldCountsAsTheFirstTry() {
        assertThat(TranscodeJobHandler.resultEventId(job(0), "FAILED", "TRANSIENT"))
                .isEqualTo(TranscodeJobHandler.resultEventId(job(1), "FAILED", "TRANSIENT"));
    }

    @Test
    void aResultTheBrokerDoesNotAcknowledgeFailsTheCommandInsteadOfBeingDropped() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> handler.handle(job(1), "corr"))
                .isInstanceOf(TranscodeJobHandler.ResultNotPublishedException.class);
        // One send only: the failure is not reported again as a second, equally doomed result.
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
    }
}

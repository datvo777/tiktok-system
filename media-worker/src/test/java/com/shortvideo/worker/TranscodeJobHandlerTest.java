package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.MediaEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class TranscodeJobHandlerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final WorkerProperties properties = new WorkerProperties();

    {
        properties.setResultPublishRetryDelay(java.time.Duration.ZERO);
    }
    private final MinioObjectStore store = mock(MinioObjectStore.class);
    private final TranscodePipeline pipeline = mock(TranscodePipeline.class);
    private final TranscodeJobHandler handler = new TranscodeJobHandler(
            store, pipeline, properties, kafka, new ObjectMapper().findAndRegisterModules(), meters);

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
        // The same result is sent again, up to the limit; a failure is never reported as a second,
        // different result that is equally doomed.
        verify(kafka, times(properties.getResultPublishAttempts())).send(anyString(), anyString(), anyString());
    }

    @Test
    void aBrokerThatAnswersOnTheSecondSendDoesNotCostTheJobARerun() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("leader election")))
                .thenReturn(CompletableFuture.completedFuture(null));

        handler.handle(job(1), "corr");

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2)).send(anyString(), anyString(), payload.capture());
        // The same bytes both times, so the inbox sees one event whichever of them landed.
        assertThat(payload.getAllValues().get(0)).isEqualTo(payload.getAllValues().get(1));
        assertThat(meters.find("worker.jobs").counters().stream().mapToDouble(c -> c.count()).sum()).isEqualTo(1.0);
    }

    @Test
    void aReportedFailureIsCountedByOutcomeAndFailureClass() {
        reportedPayloads(job(1)); // the mocked store downloads nothing: reported as TRANSIENT

        assertThat(meters.counter("worker.jobs", "outcome", "FAILED", "failureClass", "TRANSIENT").count())
                .isEqualTo(1.0);
        assertThat(meters.timer("worker.job.duration").count()).isEqualTo(1);
    }

    @Test
    void aResultTheBrokerDidNotAcknowledgeIsNotCounted() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> handler.handle(job(1), "corr"))
                .isInstanceOf(TranscodeJobHandler.ResultNotPublishedException.class);

        assertThat(meters.find("worker.jobs").counters()).isEmpty();
        assertThat(meters.timer("worker.job.duration").count()).isEqualTo(1); // the time was still spent
    }

    private static final String STAGING = "processing-temp/v1:4/";

    /** Local job directories this handler left in the temp dir (named after the job id). */
    private static List<Path> localJobDirs() throws IOException {
        try (var dirs = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "media-worker-v1-4-*")) {
            List<Path> found = new ArrayList<>();
            dirs.forEach(found::add);
            return found;
        }
    }

    /** A source that downloads, and a pipeline that produces a small output tree. */
    private void sourceDownloadsAndPipelineSucceeds() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(1), "bytes");
            return null;
        }).when(store).downloadTo(anyString(), any(Path.class));
        when(pipeline.run(any(Path.class), any(Path.class))).thenAnswer(invocation -> {
            Path out = invocation.getArgument(1);
            Files.createDirectories(out.resolve("high"));
            Files.writeString(out.resolve("master.m3u8"), "#EXTM3U");
            Files.writeString(out.resolve("high/index.m3u8"), "#EXTM3U");
            return new HlsTranscoder.TranscodeOutput(
                    out.resolve("master.m3u8"), out.resolve("high/index.m3u8"), "high/index.m3u8", 1, 3.0,
                    List.of("high/index.m3u8"), null);
        });
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void stagingAndTheLocalDirectoryAreRemovedAfterASuccessfulJob() throws Exception {
        sourceDownloadsAndPipelineSucceeds();

        handler.handle(job(1), "corr");

        verify(store).deleteAllUnder(STAGING);
        assertThat(localJobDirs()).isEmpty();
    }

    @Test
    void stagingIsRemovedAfterATerminalFailure() throws Exception {
        sourceDownloadsAndPipelineSucceeds();
        when(pipeline.run(any(Path.class), any(Path.class)))
                .thenThrow(new TranscodeFailedException("TERMINAL", "unsupported"));

        handler.handle(job(1), "corr");

        verify(store).deleteAllUnder(STAGING);
        assertThat(localJobDirs()).isEmpty();
    }

    @Test
    void stagingIsRemovedWhenTheResultCouldNotBePublished() throws Exception {
        sourceDownloadsAndPipelineSucceeds();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> handler.handle(job(1), "corr"))
                .isInstanceOf(TranscodeJobHandler.ResultNotPublishedException.class);

        verify(store).deleteAllUnder(STAGING);
        assertThat(localJobDirs()).isEmpty();
    }

    @Test
    void theLocalDirectoryIsGoneBeforeMinioIsAsked() throws Exception {
        sourceDownloadsAndPipelineSucceeds();
        List<Path> leftWhenMinioWasAsked = new ArrayList<>();
        when(store.deleteAllUnder(anyString())).thenAnswer(invocation -> {
            leftWhenMinioWasAsked.addAll(localJobDirs());
            return 0;
        });

        handler.handle(job(1), "corr");

        // A slow or unreachable MinIO must not be what keeps a 500 MB source on the disk.
        assertThat(leftWhenMinioWasAsked).isEmpty();
    }

    @Test
    void objectsThatCouldNotBeRemovedAreCountedAndDoNotFailTheJob() throws Exception {
        sourceDownloadsAndPipelineSucceeds();
        when(store.deleteAllUnder(anyString())).thenReturn(2);

        handler.handle(job(1), "corr");

        assertThat(meters.counter("worker.staging.cleanup.failed").count()).isEqualTo(2.0);
        assertThat(meters.counter("worker.jobs", "outcome", "COMPLETED", "failureClass", "none").count()).isEqualTo(1.0);
    }

    @Test
    void aStagingPrefixThatCannotBeListedIsCountedAndDoesNotFailTheJob() throws Exception {
        sourceDownloadsAndPipelineSucceeds();
        when(store.deleteAllUnder(anyString())).thenThrow(new IllegalStateException("cannot list"));

        handler.handle(job(1), "corr");

        assertThat(meters.counter("worker.staging.cleanup.failed").count()).isEqualTo(1.0);
        assertThat(meters.counter("worker.jobs", "outcome", "COMPLETED", "failureClass", "none").count()).isEqualTo(1.0);
    }

    @Test
    void aTimedOutJobReportsTheReasonSoTheVideoModuleCanRetryItLess() throws Exception {
        sourceDownloadsAndPipelineSucceeds();
        when(pipeline.run(any(Path.class), any(Path.class)))
                .thenThrow(new TranscodeFailedException("TRANSIENT", "ffmpeg timed out", "TIMEOUT"));

        handler.handle(job(1), "corr");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(anyString(), anyString(), payload.capture());
        assertThat(payload.getValue()).contains("\"failureClass\":\"TRANSIENT\"", "\"failureReason\":\"TIMEOUT\"");
    }

    @Test
    void aFailureThatWasNotATimeoutCarriesNoReason() throws Exception {
        sourceDownloadsAndPipelineSucceeds();
        when(pipeline.run(any(Path.class), any(Path.class)))
                .thenThrow(new TranscodeFailedException("TERMINAL", "unsupported"));

        handler.handle(job(1), "corr");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(anyString(), anyString(), payload.capture());
        assertThat(payload.getValue()).contains("\"failureReason\":null");
    }
}

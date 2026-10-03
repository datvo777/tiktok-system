package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** What the probe refuses before FFmpeg is ever pointed at the source. */
class HlsTranscoderProbeTest {

    private static final String MP4 = "mov,mp4,m4a,3gp,3g2,mj2";

    private static HlsTranscoder transcoderProbing(String width, String height, WorkerProperties properties)
            throws IOException {
        ProcessRunner runner = mock(ProcessRunner.class);
        String stdout = "format_name=" + MP4 + "\nwidth=" + width + "\nheight=" + height + "\nduration=10.0\n";
        when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, stdout, "", false));
        return new HlsTranscoder(runner, properties);
    }

    @Test
    void aSourceWithAnOversizedFrameIsRejectedAsTerminal() throws IOException {
        HlsTranscoder transcoder = transcoderProbing("16000", "9000", new WorkerProperties());

        assertThatThrownBy(() -> transcoder.probeSource(Path.of("source")))
                .isInstanceOfSatisfying(TranscodeFailedException.class, e -> {
                    assertThat(e.failureClass()).isEqualTo("TERMINAL");
                    assertThat(e.getMessage()).contains("16000x9000");
                });
    }

    @Test
    void a4kPortraitSourceIsAccepted() throws IOException {
        HlsTranscoder transcoder = transcoderProbing("2160", "3840", new WorkerProperties());

        assertThat(transcoder.probeSource(Path.of("source")).height()).isEqualTo(3840);
    }

    @Test
    void zeroDisablesTheFrameSizeCheck() throws IOException {
        WorkerProperties properties = new WorkerProperties();
        properties.setMaxSourceEdge(0);
        HlsTranscoder transcoder = transcoderProbing("16000", "9000", properties);

        assertThat(transcoder.probeSource(Path.of("source")).width()).isEqualTo(16000);
    }
}

package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ladder run through a real ffmpeg. The unit tests cover the decisions; only
 * this shows that the rungs a job produces actually line up, which is the
 * property adaptive switching depends on. Skipped where ffmpeg is not installed.
 */
class HlsTranscoderFfmpegTest {

    private static final WorkerProperties PROPERTIES = new WorkerProperties();
    private static final ProcessRunner RUNNER = new ProcessRunner();

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final TranscodePipeline pipeline =
            new TranscodePipeline(new HlsTranscoder(RUNNER, PROPERTIES), PROPERTIES, meters);

    @BeforeAll
    static void ffmpegIsInstalled() {
        boolean installed;
        try {
            installed = RUNNER.run(List.of(PROPERTIES.getFfmpegPath(), "-version"), PROPERTIES.getProbeTimeout(), null)
                    .succeeded();
        } catch (IOException e) {
            installed = false;
        }
        assumeTrue(installed, "ffmpeg is not installed");
    }

    private static Path source(Path dir, String size, int fps, boolean withAudio) throws IOException {
        Path file = dir.resolve("source.mp4");
        List<String> command = new java.util.ArrayList<>(List.of(
                PROPERTIES.getFfmpegPath(), "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=" + fps));
        if (withAudio) {
            command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440"));
        }
        command.addAll(List.of("-t", "10", "-c:v", "libx264", "-pix_fmt", "yuv420p"));
        if (withAudio) {
            command.addAll(List.of("-c:a", "aac"));
        }
        command.add(file.toString());
        assertThat(RUNNER.run(command, PROPERTIES.getJobTimeout(), dir).succeeded()).isTrue();
        return file;
    }

    private static List<String> segmentDurations(Path rungDir) throws IOException {
        return Files.readAllLines(rungDir.resolve("index.m3u8")).stream()
                .filter(l -> l.startsWith("#EXTINF:"))
                .map(l -> l.substring("#EXTINF:".length()).replace(",", ""))
                .toList();
    }

    private static boolean hasAudio(Path file) throws IOException {
        ProcessResult result = RUNNER.run(
                List.of(PROPERTIES.getFfprobePath(), "-v", "error", "-select_streams", "a:0",
                        "-show_entries", "stream=index", "-of", "default=noprint_wrappers=1", file.toString()),
                PROPERTIES.getProbeTimeout(), null);
        return result.stdoutTail().contains("index=");
    }

    /** At 30fps a fixed 48-frame GOP made 4.8s/3.2s segments; the cut has to follow time. */
    @Test
    void everyRungCutsTheSameFourSecondSegmentsAtThirtyFps(@TempDir Path dir) throws IOException {
        HlsTranscoder.TranscodeOutput output = pipeline.run(source(dir, "720x1280", 30, true), dir.resolve("out"));
        Path out = output.masterPlaylist().getParent();

        assertThat(output.variantRelativePaths()).containsExactly("high/index.m3u8", "medium/index.m3u8", "low/index.m3u8");
        List<String> high = segmentDurations(out.resolve("high"));
        assertThat(high).containsExactly("4.000000", "4.000000", "2.000000");
        assertThat(segmentDurations(out.resolve("medium"))).isEqualTo(high);
        assertThat(segmentDurations(out.resolve("low"))).isEqualTo(high);
        assertThat(Files.readString(output.masterPlaylist()))
                .contains("RESOLUTION=720x1280", "RESOLUTION=480x854", "RESOLUTION=270x480")
                .contains("CODECS=\"avc1.640028,mp4a.40.2\"");
    }

    /** 24 and 60 are the other frame rates phones record at; neither divides into 4s the way 30 does. */
    @Test
    void everyRungCutsTheSameSegmentsAtTwentyFourFps(@TempDir Path dir) throws IOException {
        assertRungsAlign(dir, "720x1280", 24);
    }

    @Test
    void everyRungCutsTheSameSegmentsAtSixtyFps(@TempDir Path dir) throws IOException {
        assertRungsAlign(dir, "720x1280", 60);
    }

    @Test
    void aLandscapeSourceKeepsItsOrientationAcrossTheLadder(@TempDir Path dir) throws IOException {
        HlsTranscoder.TranscodeOutput output = pipeline.run(source(dir, "1920x1080", 30, true), dir.resolve("out"));

        assertThat(Files.readString(output.masterPlaylist()))
                .contains("RESOLUTION=1280x720", "RESOLUTION=854x480", "RESOLUTION=480x270");
        Path out = output.masterPlaylist().getParent();
        assertThat(segmentDurations(out.resolve("medium"))).isEqualTo(segmentDurations(out.resolve("high")));
    }

    private void assertRungsAlign(Path dir, String size, int fps) throws IOException {
        HlsTranscoder.TranscodeOutput output = pipeline.run(source(dir, size, fps, true), dir.resolve("out"));
        Path out = output.masterPlaylist().getParent();

        List<String> high = segmentDurations(out.resolve("high"));
        assertThat(high).hasSize(3);
        assertThat(segmentDurations(out.resolve("medium"))).isEqualTo(high);
        assertThat(segmentDurations(out.resolve("low"))).isEqualTo(high);
    }

    @Test
    void aSourceWithoutAudioProducesRungsWithoutAudioAndSaysSo(@TempDir Path dir) throws IOException {
        HlsTranscoder.TranscodeOutput output = pipeline.run(source(dir, "720x1280", 25, false), dir.resolve("out"));
        Path out = output.masterPlaylist().getParent();

        assertThat(Files.readString(output.masterPlaylist())).doesNotContain("mp4a");
        try (Stream<Path> files = Files.list(out.resolve("low"))) {
            Path firstSegment = files.filter(p -> p.toString().endsWith(".ts")).sorted().findFirst().orElseThrow();
            assertThat(hasAudio(firstSegment)).isFalse();
        }
    }

    @Test
    void aSmallSourceIsNotUpscaled(@TempDir Path dir) throws IOException {
        HlsTranscoder.TranscodeOutput output = pipeline.run(source(dir, "360x640", 30, true), dir.resolve("out"));

        assertThat(output.variantRelativePaths()).containsExactly("low/index.m3u8");
        // Every step of the run was timed.
        assertThat(meters.timer("worker.transcode.step.duration", "step", "probe").count()).isEqualTo(1);
        assertThat(meters.timer("worker.transcode.step.duration", "step", "encode-ladder").count()).isEqualTo(1);
        assertThat(Files.readString(output.masterPlaylist())).contains("RESOLUTION=270x480");
    }
}

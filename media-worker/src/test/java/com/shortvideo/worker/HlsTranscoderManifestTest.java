package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The master playlist text and the time budget, both decided without running ffmpeg. */
class HlsTranscoderManifestTest {

    private final HlsTranscoder transcoder = new HlsTranscoder(new ProcessRunner(), new WorkerProperties());

    private static HlsTranscoder.Variant variant(HlsTranscoder.Rung rung, int width, int height) {
        return new HlsTranscoder.Variant(
                rung,
                new HlsTranscoder.Dimensions(width, height),
                Path.of(rung.name(), "index.m3u8"),
                rung.name() + "/index.m3u8",
                3);
    }

    private String master(Path dir, boolean hasAudio) throws IOException {
        List<HlsTranscoder.Variant> variants = HlsTranscoder.ladderFor(720, 1280).stream()
                .map(r -> variant(r, 720, 1280))
                .toList();
        return Files.readString(transcoder.writeMasterPlaylist(dir, variants, hasAudio));
    }

    @Test
    void everyVariantDeclaresBandwidthResolutionAndCodecs(@TempDir Path dir) throws IOException {
        String master = master(dir, true);

        assertThat(master.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF:"))).hasSize(3)
                .allSatisfy(line -> assertThat(line)
                        .contains("BANDWIDTH=", "RESOLUTION=720x1280", "CODECS=\"avc1.640028,mp4a.40.2\""));
    }

    /** A wrong CODECS is worse than none: a player would wait for an audio track that is not there. */
    @Test
    void aSourceWithoutAudioDoesNotAdvertiseAnAudioCodec(@TempDir Path dir) throws IOException {
        String master = master(dir, false);

        assertThat(master).contains("CODECS=\"avc1.640028\"").doesNotContain("mp4a");
    }

    @Test
    void rungsAreListedWidestFirst(@TempDir Path dir) throws IOException {
        assertThat(master(dir, true).lines().filter(l -> l.endsWith("index.m3u8")))
                .containsExactly("high/index.m3u8", "medium/index.m3u8", "low/index.m3u8");
    }

    @Test
    void aRungIsNeverGivenMoreTimeThanTheJobHasLeft() {
        assertThat(HlsTranscoder.boundedTimeout(600, Duration.ofMinutes(4))).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void aShortClipStillGetsTheFloor() {
        assertThat(HlsTranscoder.boundedTimeout(2, Duration.ofMinutes(15))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aLongClipIsLimitedToThreeTimesItsDuration() {
        assertThat(HlsTranscoder.boundedTimeout(100, Duration.ofMinutes(15))).isEqualTo(Duration.ofSeconds(300));
    }
}

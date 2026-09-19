package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two pure decisions the transcoder makes before it ever runs ffmpeg: which
 * rungs to encode, and at what size. Both are cheap to get subtly wrong and
 * expensive to notice — a squashed frame or an upscaled rung only shows up once
 * someone watches the output.
 */
class HlsTranscoderLadderTest {

    @Test
    void aFullSizeSourceGetsEveryRung() {
        assertThat(HlsTranscoder.ladderFor(720, 1280)).hasSize(3);
    }

    /** Never upscale: re-encoding a small clip larger costs three times as much for no detail. */
    @Test
    void aSmallSourceOnlyGetsTheRungsItCanFill() {
        assertThat(HlsTranscoder.ladderFor(480, 854))
                .extracting(HlsTranscoder.Rung::name)
                .containsExactly("medium", "low");

        assertThat(HlsTranscoder.ladderFor(270, 480))
                .extracting(HlsTranscoder.Rung::name)
                .containsExactly("low");
    }

    @Test
    void aSourceSmallerThanEveryRungStillGetsOne() {
        assertThat(HlsTranscoder.ladderFor(180, 320))
                .extracting(HlsTranscoder.Rung::name)
                .containsExactly("low");
    }

    @Test
    void malformedProbeDataFallsBackToASingleRendition() {
        assertThat(HlsTranscoder.ladderFor(0, 0)).hasSize(1);
    }

    /**
     * A fixed 1280x720 stretched every portrait upload into a squashed landscape
     * frame, which is the opposite of what this app is for.
     */
    @Test
    void portraitStaysPortraitAndLandscapeStaysLandscape() {
        assertThat(HlsTranscoder.targetResolution(720, 1280, 1280))
                .isEqualTo(new HlsTranscoder.Dimensions(720, 1280));
        assertThat(HlsTranscoder.targetResolution(1920, 1080, 1280))
                .isEqualTo(new HlsTranscoder.Dimensions(1280, 720));
    }

    @Test
    void scalesTheLongEdgeDownToTheRung() {
        assertThat(HlsTranscoder.targetResolution(720, 1280, 854))
                .isEqualTo(new HlsTranscoder.Dimensions(480, 854));
    }

    @Test
    void neverScalesUp() {
        assertThat(HlsTranscoder.targetResolution(360, 640, 1280))
                .isEqualTo(new HlsTranscoder.Dimensions(360, 640));
    }

    /** libx264's 4:2:0 chroma subsampling cannot encode an odd edge. */
    @Test
    void bothEdgesAreAlwaysEven() {
        for (int width : new int[] {721, 1081, 333, 999}) {
            HlsTranscoder.Dimensions target = HlsTranscoder.targetResolution(width, 1280, 854);
            assertThat(target.width() % 2).isZero();
            assertThat(target.height() % 2).isZero();
        }
    }
}

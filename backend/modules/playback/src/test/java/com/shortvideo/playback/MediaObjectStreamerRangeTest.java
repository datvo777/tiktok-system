package com.shortvideo.playback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Range parsing is pure logic with the same "gets it wrong = leaks/breaks bytes"
 * stakes as {@link MediaPathValidator} (brief section 8, Rule 19), so it gets the
 * same direct unit-test treatment rather than only exercising it through a live
 * gateway request.
 */
class MediaObjectStreamerRangeTest {

    private final MediaObjectStreamer streamer = new MediaObjectStreamer(null, "bucket", new MediaStreamProperties());
    private static final long TOTAL_SIZE = 1000;

    @Test
    void startEndRange() {
        assertThat(streamer.parseRange("bytes=100-199", TOTAL_SIZE)).containsExactly(100, 199);
    }

    @Test
    void openEndedRangeGoesToEndOfObject() {
        assertThat(streamer.parseRange("bytes=500-", TOTAL_SIZE)).containsExactly(500, 999);
    }

    @Test
    void suffixRangeReturnsLastNBytes() {
        assertThat(streamer.parseRange("bytes=-100", TOTAL_SIZE)).containsExactly(900, 999);
    }

    @Test
    void suffixRangeLargerThanObjectClampsToStart() {
        assertThat(streamer.parseRange("bytes=-5000", TOTAL_SIZE)).containsExactly(0, 999);
    }

    @Test
    void endBeyondObjectSizeClampsToLastByte() {
        assertThat(streamer.parseRange("bytes=100-5000", TOTAL_SIZE)).containsExactly(100, 999);
    }

    @Test
    void startAtOrBeyondTotalSizeIsRejected() {
        assertThat(streamer.parseRange("bytes=1000-1999", TOTAL_SIZE)).isNull();
    }

    @Test
    void negativeImpliedStartIsRejected() {
        // "-" with nothing before or after is neither a valid start-end nor a
        // valid suffix form.
        assertThat(streamer.parseRange("bytes=-", TOTAL_SIZE)).isNull();
    }

    @Test
    void endBeforeStartIsRejected() {
        assertThat(streamer.parseRange("bytes=500-100", TOTAL_SIZE)).isNull();
    }

    @Test
    void missingBytesPrefixIsRejected() {
        assertThat(streamer.parseRange("100-199", TOTAL_SIZE)).isNull();
    }

    @Test
    void nonNumericRangeIsRejected() {
        assertThat(streamer.parseRange("bytes=abc-def", TOTAL_SIZE)).isNull();
    }

    @Test
    void malformedRangeWithNoDashIsRejected() {
        assertThat(streamer.parseRange("bytes=100", TOTAL_SIZE)).isNull();
    }

    @Test
    void onlyFirstRangeInAMultiRangeRequestIsHonored() {
        // Multi-range responses are out of scope for the local MVP; the first
        // range is used rather than rejecting the whole request.
        assertThat(streamer.parseRange("bytes=0-99,200-299", TOTAL_SIZE)).containsExactly(0, 99);
    }
}

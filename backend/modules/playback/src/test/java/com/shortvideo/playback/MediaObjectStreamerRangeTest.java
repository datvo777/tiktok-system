package com.shortvideo.playback;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.playback.MediaObjectStreamer.Range;
import org.junit.jupiter.api.Test;

/**
 * Range parsing is pure logic with the same "gets it wrong = leaks/breaks bytes"
 * stakes as {@link MediaPathValidator} (brief section 8, Rule 19), so it gets the
 * same direct unit-test treatment rather than only exercising it through a live
 * gateway request.
 *
 * <p>The three outcomes are distinguished deliberately: an <em>ignored</em> header
 * means "serve the whole object with 200", an <em>unsatisfiable</em> one means
 * "answer 416". Collapsing both onto null previously meant a range past the end of
 * the object silently returned the entire file.
 */
class MediaObjectStreamerRangeTest {

    private final MediaObjectStreamer streamer = new MediaObjectStreamer(null, "bucket", new MediaStreamProperties());
    private static final long TOTAL_SIZE = 1000;

    private Range parse(String header) {
        return streamer.parseRange(header, TOTAL_SIZE);
    }

    private void assertSatisfiable(String header, long start, long end) {
        assertThat(parse(header)).isEqualTo(new Range.Satisfiable(start, end));
    }

    @Test
    void startEndRange() {
        assertSatisfiable("bytes=100-199", 100, 199);
    }

    @Test
    void openEndedRangeGoesToEndOfObject() {
        assertSatisfiable("bytes=500-", 500, 999);
    }

    @Test
    void suffixRangeReturnsLastNBytes() {
        assertSatisfiable("bytes=-100", 900, 999);
    }

    @Test
    void suffixRangeLargerThanObjectClampsToStart() {
        assertSatisfiable("bytes=-5000", 0, 999);
    }

    @Test
    void endBeyondObjectSizeClampsToLastByte() {
        assertSatisfiable("bytes=100-5000", 100, 999);
    }

    @Test
    void satisfiableRangeReportsItsOwnLength() {
        assertThat(((Range.Satisfiable) parse("bytes=100-199")).length()).isEqualTo(100);
    }

    @Test
    void startAtOrBeyondTotalSizeIsUnsatisfiable() {
        // Well-formed but unmeetable: RFC 9110 wants 416, not the whole object.
        assertThat(parse("bytes=1000-1999")).isEqualTo(Range.UNSATISFIABLE);
    }

    /**
     * "bytes=-0" asks for the last zero bytes. It used to yield start=1000, end=999,
     * i.e. a length of 0 that MinIO rejects from inside the response body — after
     * the 206 and its Content-Length had already been sent.
     */
    @Test
    void zeroLengthSuffixIsUnsatisfiable() {
        assertThat(parse("bytes=-0")).isEqualTo(Range.UNSATISFIABLE);
    }

    /**
     * "bytes=--5" parses as a suffix length of -5, which used to produce
     * start=1005, end=999 and therefore a Content-Length of -5. One header from any
     * authenticated viewer was enough to break the response.
     */
    @Test
    void negativeSuffixLengthIsUnsatisfiable() {
        assertThat(parse("bytes=--5")).isEqualTo(Range.UNSATISFIABLE);
    }

    @Test
    void negativeImpliedStartIsIgnored() {
        // "-" with nothing before or after is neither a valid start-end nor a
        // valid suffix form.
        assertThat(parse("bytes=-")).isEqualTo(Range.IGNORED);
    }

    @Test
    void endBeforeStartIsIgnored() {
        assertThat(parse("bytes=500-100")).isEqualTo(Range.IGNORED);
    }

    @Test
    void missingBytesPrefixIsIgnored() {
        assertThat(parse("100-199")).isEqualTo(Range.IGNORED);
    }

    @Test
    void absentHeaderIsIgnored() {
        assertThat(parse(null)).isEqualTo(Range.IGNORED);
    }

    @Test
    void nonNumericRangeIsIgnored() {
        assertThat(parse("bytes=abc-def")).isEqualTo(Range.IGNORED);
    }

    @Test
    void malformedRangeWithNoDashIsIgnored() {
        assertThat(parse("bytes=100")).isEqualTo(Range.IGNORED);
    }

    @Test
    void onlyFirstRangeInAMultiRangeRequestIsHonored() {
        // Multi-range responses are out of scope for the local MVP; the first
        // range is used rather than rejecting the whole request.
        assertSatisfiable("bytes=0-99,200-299", 0, 99);
    }
}

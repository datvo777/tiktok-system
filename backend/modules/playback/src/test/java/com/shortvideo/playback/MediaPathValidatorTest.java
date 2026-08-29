package com.shortvideo.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MediaPathValidatorTest {

    private static final String VIDEO_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    private final MediaPathValidator validator = new MediaPathValidator();

    @Test
    void buildsKeyInsideTheAuthorizedPrefix() {
        MediaObjectKey key = validator.validate(VIDEO_ID, "4", "/720p/segment_003.ts");

        assertThat(key.objectKey()).isEqualTo("processed/" + VIDEO_ID + "/4/720p/segment_003.ts");
        assertThat(key.processingVersion()).isEqualTo(4);
        assertThat(key.assetPath()).isEqualTo("720p/segment_003.ts");
    }

    @Test
    void acceptsMasterPlaylistAtTheRoot() {
        assertThat(validator.validate(VIDEO_ID, "1", "/master.m3u8").objectKey())
                .isEqualTo("processed/" + VIDEO_ID + "/1/master.m3u8");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/../../etc/passwd",
        "/720p/../../../secrets.m3u8",
        "/720p//segment.ts",
        "/720p/seg ment.ts"
    })
    void rejectsTraversalAndIllegalSegments(String assetPath) {
        assertThatThrownBy(() -> validator.validate(VIDEO_ID, "1", assetPath))
                .isInstanceOf(InvalidMediaPathException.class);
    }

    @Test
    void rejectsPercentEncodingThatSurvivedDecoding() {
        // A percent sign still present after servlet decoding means double
        // encoding. Decoding again is how "%252e%252e" becomes traversal, so the
        // request is refused instead.
        assertThatThrownBy(() -> validator.validate(VIDEO_ID, "1", "/%2e%2e/master.m3u8"))
                .isInstanceOf(InvalidMediaPathException.class);
    }

    @Test
    void rejectsBackslashSegments() {
        assertThatThrownBy(() -> validator.validate(VIDEO_ID, "1", "/720p\\..\\master.m3u8"))
                .isInstanceOf(InvalidMediaPathException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/evil.sh", "/master.m3u", "/segment.exe", "/noextension", "/trailingdot."})
    void rejectsUnsupportedExtensions(String assetPath) {
        assertThatThrownBy(() -> validator.validate(VIDEO_ID, "1", assetPath))
                .isInstanceOf(InvalidMediaPathException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "3f2504e0-4f89-41d3-9a0c", "", "  "})
    void rejectsMalformedVideoIds(String videoId) {
        assertThatThrownBy(() -> validator.validate(videoId, "1", "/master.m3u8"))
                .isInstanceOf(InvalidMediaPathException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "01", "abc", "1.5", "99999999999"})
    void rejectsMalformedProcessingVersions(String version) {
        assertThatThrownBy(() -> validator.validate(VIDEO_ID, version, "/master.m3u8"))
                .isInstanceOf(InvalidMediaPathException.class);
    }

    @Test
    void rejectsPathsDeeperThanTheLayoutAllows() {
        assertThatThrownBy(() -> validator.validate(VIDEO_ID, "1", "/a/b/c/d.ts"))
                .isInstanceOf(InvalidMediaPathException.class);
    }

    @Test
    void rejectsEmptyAssetPath() {
        assertThatThrownBy(() -> validator.validate(VIDEO_ID, "1", "/"))
                .isInstanceOf(InvalidMediaPathException.class);
    }
}

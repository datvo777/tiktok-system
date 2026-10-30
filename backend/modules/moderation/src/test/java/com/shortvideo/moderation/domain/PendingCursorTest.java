package com.shortvideo.moderation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingCursorTest {

    private static String encodeRaw(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCursorSurvivesAnEncodeDecodeRoundTrip() {
        // The timestamp itself contains colons, which is why the separator is the last one.
        var cursor = new PendingCursor(Instant.parse("2026-10-29T15:49:00.123456Z"), UUID.randomUUID());

        assertThat(PendingCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void noCursorStartsFromBeforeEverything() {
        assertThat(PendingCursor.decode(null)).isEqualTo(PendingCursor.decode(""));
        assertThat(PendingCursor.decode(" ").queuedAt()).isEqualTo(Instant.EPOCH);
        assertThat(PendingCursor.decode(null).videoId()).isEqualTo(new UUID(0, 0));
    }

    @Test
    void aCursorThisServiceDidNotIssueIsRefusedNotAnInternalError() {
        String[] garbage = {
            "not a cursor!",                                         // not base64
            encodeRaw("no-separator-at-all"),                        // no colon
            encodeRaw("not-a-time:" + UUID.randomUUID()),            // bad timestamp
            encodeRaw("2026-10-29T15:49:00Z:not-a-uuid"),            // bad id
            encodeRaw(":"),                                          // empty halves
        };
        for (String cursor : garbage) {
            assertThatThrownBy(() -> PendingCursor.decode(cursor))
                    .as(cursor)
                    .isInstanceOf(ModerationExceptions.InvalidCursor.class);
        }
    }
}

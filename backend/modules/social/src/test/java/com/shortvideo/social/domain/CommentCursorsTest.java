package com.shortvideo.social.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommentCursorsTest {

    private static final Instant AT = Instant.parse("2026-09-08T12:34:56.789Z");
    private static final String COMMENT_ID = "3f1a2b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b";

    @Test
    void roundTripsTheFullSortKey() {
        SocialRepository.CommentCursor decoded = CommentCursors.decode(CommentCursors.encode(AT, COMMENT_ID));

        assertThat(decoded).isNotNull();
        assertThat(decoded.createdAt()).isEqualTo(AT);
        assertThat(decoded.commentId()).isEqualTo(COMMENT_ID);
    }

    @Test
    void absentCursorMeansStartAtTheBeginning() {
        assertThat(CommentCursors.decode(null)).isNull();
        assertThat(CommentCursors.decode("  ")).isNull();
    }

    @Test
    void encodesToSomethingSafeInAQueryString() {
        String cursor = CommentCursors.encode(AT, COMMENT_ID);

        // Base64url: no '+', '/' or '=' to be mangled by a URL.
        assertThat(cursor).doesNotContain("+").doesNotContain("/").doesNotContain("=");
    }

    /**
     * Rejecting rather than silently restarting: quietly falling back to page one
     * is how a client ends up paging the same rows forever.
     */
    @Test
    void refusesACursorItDidNotIssue() {
        assertThatThrownBy(() -> CommentCursors.decode("not-base64!!"))
                .isInstanceOf(SocialExceptions.InvalidCursor.class);

        String noSeparator = Base64.getUrlEncoder().withoutPadding().encodeToString("nonsense".getBytes());
        assertThatThrownBy(() -> CommentCursors.decode(noSeparator))
                .isInstanceOf(SocialExceptions.InvalidCursor.class);

        String badTimestamp =
                Base64.getUrlEncoder().withoutPadding().encodeToString(("yesterday|" + COMMENT_ID).getBytes());
        assertThatThrownBy(() -> CommentCursors.decode(badTimestamp))
                .isInstanceOf(SocialExceptions.InvalidCursor.class);
    }

    /**
     * The id reaches the query as a uuid cast, so a malformed one has to fail
     * here as a 400 rather than downstream as a database error.
     */
    @Test
    void refusesACursorWhoseIdIsNotAUuid() {
        String badId = Base64.getUrlEncoder().withoutPadding().encodeToString((AT + "|not-a-uuid").getBytes());

        assertThatThrownBy(() -> CommentCursors.decode(badId)).isInstanceOf(SocialExceptions.InvalidCursor.class);
    }

    @Test
    void separatesTwoCommentsWrittenInTheSameInstant() {
        String first = CommentCursors.encode(AT, COMMENT_ID);
        String second = CommentCursors.encode(AT, UUID.randomUUID().toString());

        assertThat(first).isNotEqualTo(second);
    }
}

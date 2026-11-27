package com.shortvideo.social.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes and decodes the keyset cursor a comment page hands back.
 *
 * <p>The cursor is the full sort key — timestamp and id — because keyset
 * pagination needs both: the timestamp alone cannot separate two comments posted
 * in the same instant, and a page boundary that falls between them would either
 * skip one or show it twice.
 *
 * <p>Base64url-encoded so it survives a query string unescaped, and so it reads
 * as an opaque token rather than an invitation to construct one by hand. That is
 * presentation, not protection: a caller who decodes it and asks for a different
 * position gets exactly the page they asked for, which is harmless — the cursor
 * carries no authority, only a position in a list the caller may already read.
 */
final class CommentCursors {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    static String encode(Instant createdAt, String commentId) {
        String raw = createdAt.toString() + "|" + commentId;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return null for an absent cursor, meaning "start at the beginning".
     * @throws SocialExceptions.InvalidCursor when the token is present but not
     *     one this method produced. Deliberately an error rather than a silent
     *     fall back to page one: quietly restarting the list is how a client ends
     *     up in an infinite paging loop, showing the first page forever.
     */
    static SocialRepository.CommentCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator < 0) {
                throw new SocialExceptions.InvalidCursor("Malformed page cursor");
            }
            Instant createdAt = Instant.parse(raw.substring(0, separator));
            // Parsed, not just copied through: this value reaches the query as a
            // uuid cast, and a bad one would surface as a database error rather
            // than as the 400 it is.
            String commentId = UUID.fromString(raw.substring(separator + 1)).toString();
            return new SocialRepository.CommentCursor(createdAt, commentId);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new SocialExceptions.InvalidCursor("Malformed page cursor");
        }
    }

    private CommentCursors() {}
}

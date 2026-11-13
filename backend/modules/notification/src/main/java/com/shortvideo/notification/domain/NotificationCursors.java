package com.shortvideo.notification.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset cursor for the notification list: the full sort key, because the
 * timestamp alone cannot separate two notifications written in the same instant
 * and a page boundary falling between them would skip or repeat one.
 *
 * <p>Base64url so it survives a query string unescaped. That is presentation,
 * not protection — the list is already scoped to the caller by the query, so a
 * hand-made cursor only chooses a position in a list they may already read.
 */
final class NotificationCursors {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    record Position(Instant createdAt, UUID id) {}

    static String encode(Instant createdAt, UUID id) {
        return ENCODER.encodeToString((createdAt.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return null for an absent cursor, meaning "start at the newest".
     * @throws NotificationExceptions.InvalidCursor for a token this class did not
     *     produce. An error rather than a silent restart: quietly falling back to
     *     page one is how a client ends up paging the same rows forever.
     */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator < 0) {
                throw new NotificationExceptions.InvalidCursor("Malformed page cursor");
            }
            return new Position(
                    Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new NotificationExceptions.InvalidCursor("Malformed page cursor");
        }
    }

    private NotificationCursors() {}
}

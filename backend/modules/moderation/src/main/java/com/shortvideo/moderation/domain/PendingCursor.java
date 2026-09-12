package com.shortvideo.moderation.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset-pagination cursor over (createdAt, videoId): the pair
 * identifies the last row of a page, so the next page can resume with "strictly
 * after this row" instead of an offset that drifts as the queue changes.
 */
record PendingCursor(Instant createdAt, UUID videoId) {

    private static final Instant FLOOR_CREATED_AT = Instant.EPOCH;
    private static final UUID FLOOR_VIDEO_ID = new UUID(0, 0);

    static PendingCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new PendingCursor(FLOOR_CREATED_AT, FLOOR_VIDEO_ID);
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            // lastIndexOf, not indexOf: the ISO-8601 timestamp itself contains
            // colons (e.g. "15:49:00"), so the first colon is not the separator.
            int separator = raw.lastIndexOf(':');
            return new PendingCursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new ModerationExceptions.InvalidCursor("Invalid pagination cursor");
        }
    }

    String encode() {
        String raw = createdAt + ":" + videoId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}

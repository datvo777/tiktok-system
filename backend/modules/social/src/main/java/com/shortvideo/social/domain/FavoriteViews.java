package com.shortvideo.social.domain;

import java.time.Instant;
import java.util.List;

/** Read models for the favorite-collections feature. */
public final class FavoriteViews {

    /**
     * One collection in the owner's list. {@code itemCount} counts saved rows,
     * including any whose video has since become ineligible — the owner saved
     * them, and a count that silently shrank would read as data loss.
     * {@code containsVideo} is only meaningful for the save-picker query.
     */
    public record Collection(
            String collectionId,
            String name,
            long itemCount,
            boolean containsVideo,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * One saved video, resolved through the eligibility projection so the row
     * carries the same title/creator the rest of the app shows rather than a
     * snapshot taken at save time.
     */
    public record Item(
            String videoId,
            String creatorId,
            String creatorDisplayName,
            String title,
            String description,
            Instant savedAt) {}

    /**
     * A collection with its currently-playable items. {@code unavailableCount}
     * is the difference between what is saved and what is listed — videos taken
     * down, still filed, deliberately not rendered as playable rows.
     */
    public record Detail(Collection collection, List<Item> items, int unavailableCount) {}

    private FavoriteViews() {}
}

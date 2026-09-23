package com.shortvideo.social.web;

import com.shortvideo.social.domain.FavoriteViews;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class FavoriteDtos {

    public record CollectionNameRequest(@NotBlank @Size(max = 60) String name) {}

    /** {@code collectionId} null means "the default collection", created on first use. */
    public record SaveVideoRequest(String collectionId) {}

    public record CollectionResponse(
            String collectionId,
            String name,
            long itemCount,
            boolean containsVideo,
            Instant createdAt,
            Instant updatedAt) {
        public static CollectionResponse from(FavoriteViews.Collection view) {
            return new CollectionResponse(
                    view.collectionId(),
                    view.name(),
                    view.itemCount(),
                    view.containsVideo(),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    public record CollectionListResponse(List<CollectionResponse> items) {
        public static CollectionListResponse from(List<FavoriteViews.Collection> views) {
            return new CollectionListResponse(views.stream().map(CollectionResponse::from).toList());
        }
    }

    public record SavedVideoResponse(
            String videoId,
            String creatorId,
            String creatorDisplayName,
            String title,
            String description,
            Instant savedAt) {
        static SavedVideoResponse from(FavoriteViews.Item view) {
            return new SavedVideoResponse(
                    view.videoId(),
                    view.creatorId(),
                    view.creatorDisplayName(),
                    view.title(),
                    view.description(),
                    view.savedAt());
        }
    }

    /** {@code unavailableCount} is saved-but-unplayable: see {@code FavoriteService#collection}. */
    public record CollectionDetailResponse(
            CollectionResponse collection, List<SavedVideoResponse> items, int unavailableCount) {
        public static CollectionDetailResponse from(FavoriteViews.Detail view) {
            return new CollectionDetailResponse(
                    CollectionResponse.from(view.collection()),
                    view.items().stream().map(SavedVideoResponse::from).toList(),
                    view.unavailableCount());
        }
    }

    public record SavedStateResponse(boolean saved) {}

    private FavoriteDtos() {}
}

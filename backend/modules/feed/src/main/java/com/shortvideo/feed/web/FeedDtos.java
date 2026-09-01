package com.shortvideo.feed.web;

import com.shortvideo.feed.domain.FeedItemView;
import com.shortvideo.feed.domain.FeedService;
import java.util.List;

public final class FeedDtos {

    public record FeedItemResponse(String videoId, String creatorId) {
        public static FeedItemResponse from(FeedItemView view) {
            return new FeedItemResponse(view.videoId(), view.creatorId());
        }
    }

    /** {@code hasMore} lets a client stop instead of paging into empty results. */
    public record FeedResponse(int page, List<FeedItemResponse> items, boolean hasMore) {
        public static FeedResponse from(int page, FeedService.FeedPage feedPage) {
            return new FeedResponse(
                    page,
                    feedPage.items().stream().map(FeedItemResponse::from).toList(),
                    feedPage.hasMore());
        }
    }

    private FeedDtos() {}
}

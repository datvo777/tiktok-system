package com.shortvideo.feed.web;

import com.shortvideo.feed.domain.FeedItemView;
import java.util.List;

public final class FeedDtos {

    public record FeedItemResponse(String videoId, String creatorId) {
        public static FeedItemResponse from(FeedItemView view) {
            return new FeedItemResponse(view.videoId(), view.creatorId());
        }
    }

    public record FeedResponse(int page, List<FeedItemResponse> items) {
        public static FeedResponse from(int page, List<FeedItemView> views) {
            return new FeedResponse(page, views.stream().map(FeedItemResponse::from).toList());
        }
    }

    private FeedDtos() {}
}

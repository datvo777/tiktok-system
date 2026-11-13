package com.shortvideo.search.web;

import java.util.List;
import java.util.Map;

public final class CreatorVideoDtos {

    public record CreatorVideoResponse(String videoId, String title, String description, String publishedAt) {
        @SuppressWarnings("unchecked")
        static CreatorVideoResponse from(Map<String, Object> source) {
            return new CreatorVideoResponse(
                    (String) source.get("videoId"),
                    (String) source.get("title"),
                    (String) source.get("description"),
                    (String) source.get("publishedAt"));
        }
    }

    /** {@code hasMore} follows the same convention as the Feed and "my videos" listings. */
    public record CreatorVideoListResponse(int page, List<CreatorVideoResponse> items, boolean hasMore) {}

    private CreatorVideoDtos() {}
}

package com.shortvideo.search.web;

import java.util.Map;

public final class SearchDtos {

    public record SearchHit(
            String videoId, String creatorId, String creatorDisplayName, String title, String description, String publishedAt) {
        @SuppressWarnings("unchecked")
        static SearchHit from(Map<String, Object> source) {
            return new SearchHit(
                    (String) source.get("videoId"),
                    (String) source.get("creatorId"),
                    (String) source.get("creatorDisplayName"),
                    (String) source.get("title"),
                    (String) source.get("description"),
                    (String) source.get("publishedAt"));
        }
    }

    public record SearchResponse(String query, java.util.List<SearchHit> results) {}

    private SearchDtos() {}
}

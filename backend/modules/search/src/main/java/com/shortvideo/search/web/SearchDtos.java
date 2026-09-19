package com.shortvideo.search.web;

import java.util.Map;

public final class SearchDtos {

    /**
     * @param creatorHandle empty for documents indexed before handles existed;
     *     the client falls back to the display name rather than showing "@".
     */
    public record SearchHit(
            String videoId,
            String creatorId,
            String creatorDisplayName,
            String creatorHandle,
            String title,
            String description,
            String publishedAt) {
        @SuppressWarnings("unchecked")
        static SearchHit from(Map<String, Object> source) {
            return new SearchHit(
                    (String) source.get("videoId"),
                    (String) source.get("creatorId"),
                    (String) source.get("creatorDisplayName"),
                    (String) source.getOrDefault("creatorHandle", ""),
                    (String) source.get("title"),
                    (String) source.get("description"),
                    (String) source.get("publishedAt"));
        }
    }

    /**
     * @param hasMore lets the client offer another page instead of guessing
     *     whether a full page means the end of the results.
     */
    public record SearchResponse(String query, int page, java.util.List<SearchHit> results, boolean hasMore) {}

    private SearchDtos() {}
}

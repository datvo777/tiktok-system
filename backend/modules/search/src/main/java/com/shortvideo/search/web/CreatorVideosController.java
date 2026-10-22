package com.shortvideo.search.web;

import com.shortvideo.search.domain.SearchIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A creator's public video grid, backed by the same index {@link com.shortvideo.search.web.SearchController} queries. */
@RestController
@RequestMapping("/api/v1/creators/{creatorId}/videos")
@Tag(name = "Creator profile")
@Validated
public class CreatorVideosController {

    private static final int PAGE_SIZE = 20;

    private final SearchIndexService indexService;

    public CreatorVideosController(SearchIndexService indexService) {
        this.indexService = indexService;
    }

    @GetMapping
    @Operation(summary = "A creator's published videos, newest first")
    public CreatorVideoDtos.CreatorVideoListResponse videos(
            @PathVariable UUID creatorId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        // One extra document fetched, never returned: its presence is what tells
        // the caller there's a next page, the same trick VideoService.listMine
        // gets for free from Spring Data's Page.hasNext().
        List<CreatorVideoDtos.CreatorVideoResponse> hits = indexService
                .byCreator(creatorId.toString(), page * PAGE_SIZE, PAGE_SIZE + 1)
                .stream()
                .map(CreatorVideoDtos.CreatorVideoResponse::from)
                .toList();
        boolean hasMore = hits.size() > PAGE_SIZE;
        List<CreatorVideoDtos.CreatorVideoResponse> items = hasMore ? hits.subList(0, PAGE_SIZE) : hits;
        return new CreatorVideoDtos.CreatorVideoListResponse(page, items, hasMore);
    }
}

package com.shortvideo.search.web;

import com.shortvideo.search.domain.SearchIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Search API (brief section 20, Milestone 7). */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search")
@Validated
public class SearchController {

    private static final int DEFAULT_LIMIT = 20;

    private final SearchIndexService indexService;

    public SearchController(SearchIndexService indexService) {
        this.indexService = indexService;
    }

    @GetMapping
    @Operation(summary = "Search published videos by creator name")
    public SearchDtos.SearchResponse search(
            // Unbounded before: a multi-megabyte q was forwarded verbatim to
            // OpenSearch. No injection risk (q is a bound value in a structured
            // match clause), but no cost ceiling either.
            @RequestParam @NotBlank @Size(max = 128) String q,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(50) int limit) {
        var hits = indexService.search(q, limit).stream().map(SearchDtos.SearchHit::from).toList();
        return new SearchDtos.SearchResponse(q, hits);
    }
}

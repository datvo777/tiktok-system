package com.shortvideo.search.web;

import com.shortvideo.search.domain.SearchIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Search API (brief section 20, Milestone 7). */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search")
public class SearchController {

    private final SearchIndexService indexService;

    public SearchController(SearchIndexService indexService) {
        this.indexService = indexService;
    }

    @GetMapping
    @Operation(summary = "Search published videos by creator name")
    public SearchDtos.SearchResponse search(@RequestParam String q) {
        var hits = indexService.search(q, 20).stream().map(SearchDtos.SearchHit::from).toList();
        return new SearchDtos.SearchResponse(q, hits);
    }
}

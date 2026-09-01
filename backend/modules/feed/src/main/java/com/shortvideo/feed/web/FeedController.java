package com.shortvideo.feed.web;

import com.shortvideo.feed.domain.FeedService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feed")
@Tag(name = "Feed")
@Validated
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    @Operation(summary = "Rule-based feed page (brief section 15)")
    public FeedDtos.FeedResponse feed(
            @AuthenticationPrincipal AuthenticatedAccount caller,
            // Bounded rather than clamped: a negative or absurd page is a client
            // bug, and answering 400 says so instead of silently serving page 0.
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page) {
        return FeedDtos.FeedResponse.from(page, feedService.feed(caller.accountId(), page));
    }
}

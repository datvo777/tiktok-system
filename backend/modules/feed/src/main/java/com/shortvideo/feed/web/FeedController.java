package com.shortvideo.feed.web;

import com.shortvideo.feed.domain.FeedService;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feed")
@Tag(name = "Feed")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    @Operation(summary = "Rule-based feed page (brief section 15)")
    public FeedDtos.FeedResponse feed(
            @AuthenticationPrincipal AuthenticatedAccount caller, @RequestParam(defaultValue = "0") int page) {
        return FeedDtos.FeedResponse.from(page, feedService.feed(caller.accountId(), Math.max(0, page)));
    }
}

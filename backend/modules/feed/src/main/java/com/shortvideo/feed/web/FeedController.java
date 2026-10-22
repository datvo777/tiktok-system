package com.shortvideo.feed.web;

import com.shortvideo.feed.domain.FeedService;
import java.util.UUID;
import com.shortvideo.shared.security.AuthenticatedAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "FOR_YOU") FeedService.Scope scope) {
        // Signed out: no follows, no watch history, and nothing viewer-specific
        // to key a cache on. `null` is the whole difference, and FeedService
        // treats it as "rank for a stranger".
        String viewerId = caller == null ? null : caller.accountId();
        if (viewerId == null && scope == FeedService.Scope.FOLLOWING) {
            // Nobody to follow. An empty page is the honest answer; the client
            // shows a sign-in prompt rather than an error.
            return new FeedDtos.FeedResponse(page, java.util.List.of(), false);
        }
        return FeedDtos.FeedResponse.from(page, feedService.feed(viewerId, page, scope));
    }

    /**
     * Resolves one video by id, in the same shape the feed serves it. This is
     * what a shared link and a notification both land on: without it a URL naming
     * a video had nothing to fetch, so the client could only ever show the
     * generic ranking.
     *
     * <p>404 rather than 403 when the video is ineligible or revoked — whether a
     * particular id exists but is withheld is not something an arbitrary caller
     * needs to learn from the status code.
     */
    @GetMapping("/videos/{videoId}")
    @Operation(summary = "One feed item by video id, for links that name a specific video")
    public FeedDtos.FeedItemResponse item(@PathVariable UUID videoId) {
        return feedService
                .item(videoId.toString())
                .map(FeedDtos.FeedItemResponse::from)
                .orElseThrow(() -> new FeedExceptions.FeedItemNotFound("No such video in the feed"));
    }
}

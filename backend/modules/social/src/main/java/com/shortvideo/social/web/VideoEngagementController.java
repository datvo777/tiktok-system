package com.shortvideo.social.web;

import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.social.domain.SocialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Likes, shares, views, and the counts read model over them.
 *
 * <p>Split out of a single {@code SocialController} that carried likes, shares,
 * views, comments, replies and this projection behind one prefix — four
 * aggregates and a read model in one class. Nothing about that was broken; it
 * just meant no single file answered "what can you do to a video's engagement?",
 * and the class grew a little every time the answer changed.
 */
@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Social")
public class VideoEngagementController {

    private final SocialService socialService;

    public VideoEngagementController(SocialService socialService) {
        this.socialService = socialService;
    }

    @PostMapping("/{videoId}/likes")
    @Operation(summary = "Like a video; idempotent")
    public ResponseEntity<Void> like(@PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.like(videoId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{videoId}/likes")
    @Operation(summary = "Unlike a video; idempotent")
    public ResponseEntity<Void> unlike(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.unlike(videoId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/shares")
    @Operation(summary = "Record a share of a video")
    public ResponseEntity<Void> share(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.share(videoId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Records a watch. The client calls this once per video once the viewer has
     * watched enough for it to count, not on every timeupdate.
     */
    @PostMapping("/{videoId}/views")
    @Operation(summary = "Record that the caller watched this video")
    public ResponseEntity<Void> view(
            @PathVariable UUID videoId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody SocialDtos.RecordViewRequest request) {
        socialService.recordView(
                videoId.toString(), caller.accountId(), request.watchedMs(), Boolean.TRUE.equals(request.completed()));
        return ResponseEntity.noContent().build();
    }

    /** Public: the totals are not viewer-specific, and only {@code liked} depends on who is asking. */
    @GetMapping("/{videoId}/counts")
    @Operation(summary = "Like, comment, share and view counts, plus whether the caller has liked it")
    public ResponseEntity<SocialDtos.VideoCountsResponse> counts(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        return ResponseEntity.ok(SocialDtos.VideoCountsResponse.from(
                socialService.videoCounts(videoId.toString(), caller == null ? null : caller.accountId())));
    }
}

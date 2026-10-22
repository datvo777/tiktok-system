package com.shortvideo.video.web;

import java.util.UUID;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.video.domain.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Video")
@Validated
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @GetMapping
    @Operation(summary = "List the caller's own videos, most recent first")
    public VideoDtos.VideoListResponse mine(
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page) {
        return VideoDtos.VideoListResponse.from(page, videoService.listMine(caller.accountId(), page));
    }

    @GetMapping("/{videoId}")
    @Operation(summary = "Processing status; restricted to the owner while private")
    public VideoDtos.VideoResponse get(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        return VideoDtos.VideoResponse.from(videoService.findForPolling(videoId.toString(), caller.accountId()));
    }

    /**
     * Deletes the caller's own video: the same lifecycle an admin takedown runs,
     * refused unless the caller owns it.
     *
     * <p>Idempotent, and answers 204 either way — a second delete of a video
     * already scheduled for deletion is not an error, it is the state the caller
     * asked for.
     */
    @DeleteMapping("/{videoId}")
    @Operation(summary = "Delete your own video; schedules its assets for deletion")
    public ResponseEntity<Void> delete(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        videoService.deleteByOwner(videoId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }
}

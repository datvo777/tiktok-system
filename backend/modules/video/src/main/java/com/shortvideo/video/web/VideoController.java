package com.shortvideo.video.web;

import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.video.domain.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Video")
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @GetMapping("/{videoId}")
    @Operation(summary = "Processing status; restricted to the owner while private")
    public VideoDtos.VideoResponse get(
            @PathVariable String videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        return VideoDtos.VideoResponse.from(videoService.findForPolling(videoId, caller.accountId()));
    }
}

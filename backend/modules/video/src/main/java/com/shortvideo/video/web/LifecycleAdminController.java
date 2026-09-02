package com.shortvideo.video.web;

import java.util.UUID;
import com.shortvideo.video.domain.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin lifecycle actions (brief section 18, Milestone 6): quarantine, restore,
 * remove, and a forced reprocess. Not listed in section 12's API contract table,
 * same as several other admin actions section 18 names (e.g. "remove video") —
 * kept under the same {@code /internal/v1} admin namespace as moderation and
 * account.
 */
@RestController
@RequestMapping("/internal/v1/videos")
@Tag(name = "Video lifecycle (internal)")
public class LifecycleAdminController {

    private final VideoService videoService;

    public LifecycleAdminController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping("/{videoId}/quarantine")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Place a lifecycle hold on a video, independent of moderation")
    public ResponseEntity<Void> quarantine(@PathVariable UUID videoId, @RequestBody(required = false) LifecycleDtos.ReasonRequest request) {
        videoService.quarantine(videoId.toString(), request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reverse a quarantine if the underlying assets are still verifiably present")
    public ResponseEntity<Void> restore(@PathVariable UUID videoId) {
        videoService.restoreFromQuarantine(videoId.toString());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/remove")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Permanently take down a video; schedules its assets for deletion")
    public ResponseEntity<Void> remove(@PathVariable UUID videoId, @RequestBody(required = false) LifecycleDtos.ReasonRequest request) {
        videoService.remove(videoId.toString(), request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/reprocess")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Redispatch transcoding from the original source object")
    public ResponseEntity<Void> reprocess(@PathVariable UUID videoId) {
        videoService.reprocess(videoId.toString());
        return ResponseEntity.noContent().build();
    }
}

package com.shortvideo.video.web;

import java.util.UUID;
import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.video.domain.VideoExceptions;
import com.shortvideo.video.domain.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import com.shortvideo.shared.security.AuthenticatedAccount;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final EligibilityDirectory eligibilityDirectory;
    private final AccountDirectory accountDirectory;

    public LifecycleAdminController(
            VideoService videoService, EligibilityDirectory eligibilityDirectory, AccountDirectory accountDirectory) {
        this.videoService = videoService;
        this.eligibilityDirectory = eligibilityDirectory;
        this.accountDirectory = accountDirectory;
    }

    /**
     * A single lookup that composes the eligibility projection's four-source
     * status with the creator's display name, so an admin pasting a video id
     * gets full context before acting on it instead of a blind quarantine/remove.
     */
    @GetMapping("/{videoId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Full status snapshot for a video, for admin lookup")
    public ResponseEntity<LifecycleDtos.VideoDetailResponse> get(@PathVariable UUID videoId) {
        VideoEligibilityView eligibility = eligibilityDirectory
                .findVideoEligibility(videoId.toString())
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));
        String creatorDisplayName =
                accountDirectory.find(eligibility.creatorId()).map(a -> a.displayName()).orElse("");
        return ResponseEntity.ok(LifecycleDtos.VideoDetailResponse.from(eligibility, creatorDisplayName));
    }

    @PostMapping("/{videoId}/quarantine")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Place a lifecycle hold on a video, independent of moderation")
    public ResponseEntity<Void> quarantine(
            @PathVariable UUID videoId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @RequestBody(required = false) LifecycleDtos.ReasonRequest request) {
        videoService.quarantine(videoId.toString(), request == null ? null : request.reason(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reverse a quarantine if the underlying assets are still verifiably present")
    public ResponseEntity<Void> restore(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        videoService.restoreFromQuarantine(videoId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/remove")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Permanently take down a video; schedules its assets for deletion")
    public ResponseEntity<Void> remove(
            @PathVariable UUID videoId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @RequestBody(required = false) LifecycleDtos.ReasonRequest request) {
        videoService.remove(videoId.toString(), request == null ? null : request.reason(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/reprocess")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Redispatch transcoding from the original source object")
    public ResponseEntity<Void> reprocess(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        videoService.reprocess(videoId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }
}

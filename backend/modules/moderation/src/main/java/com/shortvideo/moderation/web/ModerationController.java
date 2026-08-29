package com.shortvideo.moderation.web;

import com.shortvideo.moderation.domain.ModerationService;
import com.shortvideo.moderation.domain.ModerationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin surface (brief section 18). Moderation can be manual in the local MVP. */
@RestController
@RequestMapping("/internal/v1/videos")
@Tag(name = "Moderation (internal)")
public class ModerationController {

    private final ModerationService moderationService;

    public ModerationController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List videos awaiting a moderation decision")
    public List<ModerationDtos.PendingVideoResponse> pending() {
        return moderationService.listPending().stream().map(ModerationDtos.PendingVideoResponse::from).toList();
    }

    @PostMapping("/{videoId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve (PENDING) or reinstate (REJECTED) a video")
    public ResponseEntity<Void> approve(@PathVariable String videoId) {
        moderationService.approve(videoId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reject a video; immediately revokes playback")
    public ResponseEntity<Void> reject(@PathVariable String videoId, @Valid @RequestBody ModerationDtos.RejectRequest request) {
        moderationService.reject(videoId, request.reason());
        return ResponseEntity.noContent().build();
    }
}

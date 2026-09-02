package com.shortvideo.social.web;

import java.util.UUID;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.social.domain.SocialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Social")
public class SocialController {

    private final SocialService socialService;

    public SocialController(SocialService socialService) {
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
    public ResponseEntity<Void> unlike(@PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.unlike(videoId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/comments")
    @Operation(summary = "Comment on a video")
    public ResponseEntity<SocialDtos.CommentResponse> comment(
            @PathVariable UUID videoId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody SocialDtos.CreateCommentRequest request) {
        var comment = socialService.comment(videoId.toString(), caller.accountId(), request.body());
        return ResponseEntity.status(201).body(SocialDtos.CommentResponse.from(comment));
    }

    @GetMapping("/{videoId}/comments")
    @Operation(summary = "List comments on a video, newest first")
    public ResponseEntity<SocialDtos.CommentListResponse> listComments(@PathVariable UUID videoId) {
        return ResponseEntity.ok(SocialDtos.CommentListResponse.from(socialService.listComments(videoId.toString())));
    }
}

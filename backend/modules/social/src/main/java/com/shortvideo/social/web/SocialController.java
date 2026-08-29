package com.shortvideo.social.web;

import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.social.domain.SocialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    public ResponseEntity<Void> like(@PathVariable String videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.like(videoId, caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{videoId}/likes")
    @Operation(summary = "Unlike a video; idempotent")
    public ResponseEntity<Void> unlike(@PathVariable String videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.unlike(videoId, caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/comments")
    @Operation(summary = "Comment on a video")
    public ResponseEntity<SocialDtos.CommentResponse> comment(
            @PathVariable String videoId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody SocialDtos.CreateCommentRequest request) {
        var comment = socialService.comment(videoId, caller.accountId(), request.body());
        return ResponseEntity.status(201).body(SocialDtos.CommentResponse.from(comment));
    }
}

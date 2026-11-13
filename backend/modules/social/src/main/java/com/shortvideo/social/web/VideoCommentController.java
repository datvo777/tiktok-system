package com.shortvideo.social.web;

import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.social.domain.SocialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Comments and their replies. Split out of the former single social controller. */
@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Comments")
@Validated
public class VideoCommentController {

    private final SocialService socialService;

    public VideoCommentController(SocialService socialService) {
        this.socialService = socialService;
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

    /** Public: reading a published video's comments needs no account. */
    @GetMapping("/{videoId}/comments")
    @Operation(summary = "List top-level comments on a video, newest first")
    public ResponseEntity<SocialDtos.CommentListResponse> listComments(
            @PathVariable UUID videoId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100) int limit) {
        return ResponseEntity.ok(
                SocialDtos.CommentListResponse.from(socialService.listComments(videoId.toString(), cursor, limit)));
    }

    /**
     * Deletes a comment. Allowed to its author and to the owner of the video it
     * sits on; 404 either way for anything the caller may not touch, so comment
     * ids cannot be probed.
     */
    @DeleteMapping("/{videoId}/comments/{commentId}")
    @Operation(summary = "Delete your own comment, or any comment on your own video")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID videoId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.deleteComment(videoId.toString(), commentId.toString(), caller.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{videoId}/comments/{commentId}/replies")
    @Operation(summary = "Reply to a comment on a video")
    public ResponseEntity<SocialDtos.CommentResponse> reply(
            @PathVariable UUID videoId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal AuthenticatedAccount caller,
            @Valid @RequestBody SocialDtos.CreateCommentRequest request) {
        var reply = socialService.reply(videoId.toString(), commentId.toString(), caller.accountId(), request.body());
        return ResponseEntity.status(201).body(SocialDtos.CommentResponse.from(reply));
    }

    @GetMapping("/{videoId}/comments/{commentId}/replies")
    @Operation(summary = "List replies to a comment, oldest first")
    public ResponseEntity<SocialDtos.CommentListResponse> listReplies(
            @PathVariable UUID videoId,
            @PathVariable UUID commentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100) int limit) {
        return ResponseEntity.ok(SocialDtos.CommentListResponse.from(
                socialService.listReplies(videoId.toString(), commentId.toString(), cursor, limit)));
    }
}

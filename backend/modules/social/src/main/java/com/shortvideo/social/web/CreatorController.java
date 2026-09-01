package com.shortvideo.social.web;

import java.util.UUID;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.social.domain.SocialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/creators")
@Tag(name = "Creator profile")
public class CreatorController {

    private final SocialService socialService;

    public CreatorController(SocialService socialService) {
        this.socialService = socialService;
    }

    @GetMapping("/{creatorId}")
    @Operation(summary = "Read a creator profile")
    public SocialDtos.CreatorProfileResponse profile(@PathVariable UUID creatorId) {
        return SocialDtos.CreatorProfileResponse.from(socialService.profile(creatorId.toString()));
    }

    @PostMapping("/{creatorId}/follow")
    @Operation(summary = "Follow a creator; idempotent")
    public ResponseEntity<Void> follow(
            @PathVariable UUID creatorId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.follow(caller.accountId(), creatorId.toString());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{creatorId}/follow")
    @Operation(summary = "Unfollow a creator; idempotent")
    public ResponseEntity<Void> unfollow(
            @PathVariable UUID creatorId, @AuthenticationPrincipal AuthenticatedAccount caller) {
        socialService.unfollow(caller.accountId(), creatorId.toString());
        return ResponseEntity.noContent().build();
    }
}

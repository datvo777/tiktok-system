package com.shortvideo.video.web;

import java.util.UUID;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.PlaybackCookies;
import com.shortvideo.shared.security.PlaybackMode;
import com.shortvideo.shared.security.PlaybackTokenService;
import com.shortvideo.video.api.VideoPlaybackView;
import com.shortvideo.video.domain.VideoExceptions;
import com.shortvideo.video.domain.VideoService;
import com.shortvideo.video.web.VideoDtos.PlaybackSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an admin actually watch a video before deciding on it (brief section
 * 18's moderation actions presuppose the moderator has seen the content — the
 * original two playback modes don't cover this: {@code OWNER_PREVIEW} requires
 * the caller to be the owner, and {@code PUBLIC} requires the full eligibility
 * invariant, which a video pending its first moderation decision can never yet
 * satisfy). Same authority order as every other playback-session endpoint,
 * with ownership swapped for the ADMIN role the URL already requires.
 */
@RestController
@RequestMapping("/internal/v1/videos")
@Tag(name = "Video lifecycle (internal)")
public class ModeratorPlaybackController {

    private final VideoService videoService;
    private final RevocationCache revocationCache;
    private final DurableRevocationReader revocationReader;
    private final PlaybackTokenService tokenService;
    private final PlaybackCookies playbackCookies;

    public ModeratorPlaybackController(
            VideoService videoService,
            RevocationCache revocationCache,
            DurableRevocationReader revocationReader,
            PlaybackTokenService tokenService,
            PlaybackCookies playbackCookies) {
        this.videoService = videoService;
        this.revocationCache = revocationCache;
        this.revocationReader = revocationReader;
        this.tokenService = tokenService;
        this.playbackCookies = playbackCookies;
    }

    @PostMapping("/{videoId}/moderator-playback-session")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin-only preview session; requires processing to be READY, nothing else")
    public ResponseEntity<PlaybackSessionResponse> moderatorPreview(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {

        VideoPlaybackView video = videoService
                .findForPlayback(videoId.toString())
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));

        if (video.currentProcessingVersion() == null || !video.ownerPreviewEligible(video.currentProcessingVersion())) {
            throw new VideoExceptions.VideoNotReady("Video is not ready for preview");
        }

        requireNotRevoked(RevocationSubjects.VIDEO, video.videoId());
        requireNotRevoked(RevocationSubjects.ACCOUNT, video.ownerAccountId());

        int processingVersion = video.currentProcessingVersion();
        var issued = tokenService.issue(caller.accountId(), videoId.toString(), processingVersion, PlaybackMode.MODERATOR_PREVIEW);
        var cookie = playbackCookies.cookie(issued.token(), issued.expiresAt(), videoId.toString(), processingVersion);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new PlaybackSessionResponse(
                        videoId.toString(), processingVersion, PlaybackMode.MODERATOR_PREVIEW, issued.expiresAt()));
    }

    private void requireNotRevoked(String subjectType, String subjectId) {
        boolean denied = revocationCache.isDenied(subjectType, subjectId)
                || revocationReader.isActive(subjectType, subjectId);
        if (denied) {
            throw new VideoExceptions.VideoNotReady("Playback is currently restricted");
        }
    }
}

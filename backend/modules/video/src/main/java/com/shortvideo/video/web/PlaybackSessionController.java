package com.shortvideo.video.web;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.account.api.AccountView;
import com.shortvideo.eligibility.api.AccountEligibilityView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Playback-session issuance (brief section 8, section 12). Byte delivery itself is
 * the media gateway's job (playback module); this only decides whether a session
 * cookie may be minted, using the same authority order the gateway re-checks on
 * every request: trusted persisted state, then Redis deny, then durable
 * PostgreSQL revocation, then (for public sessions) a durable eligibility allow.
 */
@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Playback session")
public class PlaybackSessionController {

    private final VideoService videoService;
    private final AccountDirectory accountDirectory;
    private final EligibilityDirectory eligibilityDirectory;
    private final RevocationCache revocationCache;
    private final DurableRevocationReader revocationReader;
    private final PlaybackTokenService tokenService;
    private final PlaybackCookies playbackCookies;

    public PlaybackSessionController(
            VideoService videoService,
            AccountDirectory accountDirectory,
            EligibilityDirectory eligibilityDirectory,
            RevocationCache revocationCache,
            DurableRevocationReader revocationReader,
            PlaybackTokenService tokenService,
            PlaybackCookies playbackCookies) {
        this.videoService = videoService;
        this.accountDirectory = accountDirectory;
        this.eligibilityDirectory = eligibilityDirectory;
        this.revocationCache = revocationCache;
        this.revocationReader = revocationReader;
        this.tokenService = tokenService;
        this.playbackCookies = playbackCookies;
    }

    @PostMapping("/{videoId}/preview-playback-session")
    @Operation(summary = "Owner-only preview session; does not require moderation or publication")
    public ResponseEntity<VideoDtos.PlaybackSessionResponse> preview(
            @PathVariable String videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {

        VideoPlaybackView video = videoService
                .findForPlayback(videoId)
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));

        if (!video.ownerAccountId().equals(caller.accountId())) {
            // Same response as a missing video: do not confirm existence to a non-owner.
            throw new VideoExceptions.VideoNotFound("No such video");
        }
        if (video.currentProcessingVersion() == null || !video.ownerPreviewEligible(video.currentProcessingVersion())) {
            throw new VideoExceptions.VideoNotReady("Video is not ready for preview");
        }

        AccountView owner = accountDirectory
                .find(video.ownerAccountId())
                .orElseThrow(() -> new VideoExceptions.VideoNotReady("Owner account is unknown"));
        if (!owner.isEligible()) {
            throw new VideoExceptions.VideoNotReady("Owner account is not active");
        }

        requireNotRevoked(RevocationSubjects.VIDEO, video.videoId());
        requireNotRevoked(RevocationSubjects.ACCOUNT, video.ownerAccountId());

        int processingVersion = video.currentProcessingVersion();
        var issued = tokenService.issue(caller.accountId(), videoId, processingVersion, PlaybackMode.OWNER_PREVIEW);
        var cookie = playbackCookies.cookie(issued.token(), issued.expiresAt(), videoId, processingVersion);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new VideoDtos.PlaybackSessionResponse(
                        videoId, processingVersion, PlaybackMode.OWNER_PREVIEW, issued.expiresAt()));
    }

    @PostMapping("/{videoId}/public-playback-session")
    @Operation(summary = "Public session; requires the full eligibility invariant (brief section 8)")
    public ResponseEntity<VideoDtos.PlaybackSessionResponse> publicSession(
            @PathVariable String videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {

        // A missing projection row is unknown state and denies (Rule 9) — no
        // distinction is made between "not found" and "not yet eligible".
        VideoEligibilityView videoEligibility = eligibilityDirectory
                .findVideoEligibility(videoId)
                .filter(VideoEligibilityView::isVideoEligible)
                .orElseThrow(() -> new VideoExceptions.VideoNotReady("Video is not publicly eligible"));

        AccountEligibilityView accountEligibility = eligibilityDirectory
                .findAccountEligibility(videoEligibility.creatorId())
                .filter(AccountEligibilityView::isAccountEligible)
                .orElseThrow(() -> new VideoExceptions.VideoNotReady("Creator account is not eligible"));

        requireNotRevoked(RevocationSubjects.VIDEO, videoId);
        requireNotRevoked(RevocationSubjects.ACCOUNT, accountEligibility.accountId());

        int processingVersion = videoEligibility.processingVersion();
        String viewerId = caller == null ? "anonymous" : caller.accountId();
        var issued = tokenService.issue(viewerId, videoId, processingVersion, PlaybackMode.PUBLIC);
        var cookie = playbackCookies.cookie(issued.token(), issued.expiresAt(), videoId, processingVersion);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new VideoDtos.PlaybackSessionResponse(
                        videoId, processingVersion, PlaybackMode.PUBLIC, issued.expiresAt()));
    }

    private void requireNotRevoked(String subjectType, String subjectId) {
        boolean denied = revocationCache.isDenied(subjectType, subjectId)
                || revocationReader.isActive(subjectType, subjectId);
        if (denied) {
            throw new VideoExceptions.VideoNotReady("Playback is currently restricted");
        }
    }
}

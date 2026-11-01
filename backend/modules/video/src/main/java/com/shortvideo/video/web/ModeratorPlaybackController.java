package com.shortvideo.video.web;

import java.util.UUID;
import com.shortvideo.shared.audit.AdminAction;
import com.shortvideo.shared.audit.AdminActionRecorder;
import com.shortvideo.shared.audit.AuditActions;
import com.shortvideo.shared.audit.AuditTargets;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.revocation.RevocationChecks;
import com.shortvideo.shared.revocation.RevocationSources;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.Roles;
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
 * with ownership swapped for the staff role the URL already requires.
 *
 * <p>Staff look past the decisions they are there to review: a video retained after a rejection stays
 * watchable and so does its revocation, or an appeal could never be decided; likewise a suspended
 * account's videos. Anything else (a lifecycle hold, a source added later) blocks them as it blocks
 * everyone. Every session issued is audited, since this is how unpublished content is reached.
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
    private final AdminActionRecorder auditRecorder;

    public ModeratorPlaybackController(
            VideoService videoService,
            RevocationCache revocationCache,
            DurableRevocationReader revocationReader,
            PlaybackTokenService tokenService,
            PlaybackCookies playbackCookies,
            AdminActionRecorder auditRecorder) {
        this.videoService = videoService;
        this.revocationCache = revocationCache;
        this.revocationReader = revocationReader;
        this.tokenService = tokenService;
        this.playbackCookies = playbackCookies;
        this.auditRecorder = auditRecorder;
    }

    @PostMapping("/{videoId}/moderator-playback-session")
    @PreAuthorize(Roles.CAN_MODERATE)
    @Operation(summary = "Moderator preview session; requires processing to be READY, nothing else")
    public ResponseEntity<PlaybackSessionResponse> moderatorPreview(
            @PathVariable UUID videoId, @AuthenticationPrincipal AuthenticatedAccount caller) {

        VideoPlaybackView video = videoService
                .findForPlayback(videoId.toString())
                .orElseThrow(() -> new VideoExceptions.VideoNotFound("No such video"));

        if (video.currentProcessingVersion() == null || !video.moderatorPreviewEligible(video.currentProcessingVersion())) {
            throw new VideoExceptions.VideoNotReady("Video is not ready for preview");
        }

        requireNotRevoked(RevocationSubjects.VIDEO, video.videoId());
        requireNotRevoked(RevocationSubjects.ACCOUNT, video.ownerAccountId());

        // Before the session exists: an access that cannot be attributed is not one to allow.
        auditRecorder.record(
                AdminAction.of(caller.accountId(), AuditActions.VIDEO_PREVIEWED, AuditTargets.VIDEO, videoId.toString()));

        int processingVersion = video.currentProcessingVersion();
        var issued = tokenService.issue(caller.accountId(), videoId.toString(), processingVersion, PlaybackMode.MODERATOR_PREVIEW);
        var cookie = playbackCookies.cookie(issued.token(), issued.expiresAt(), videoId.toString(), processingVersion);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new PlaybackSessionResponse(
                        videoId.toString(), processingVersion, PlaybackMode.MODERATOR_PREVIEW, issued.expiresAt()));
    }

    private void requireNotRevoked(String subjectType, String subjectId) {
        boolean denied = RevocationChecks.isBlockedExcept(
                revocationCache,
                revocationReader,
                subjectType,
                subjectId,
                RevocationSources.ignoredByStaffPreview(subjectType));
        if (denied) {
            throw new VideoExceptions.VideoNotReady("Playback is currently restricted");
        }
    }
}

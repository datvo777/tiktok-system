package com.shortvideo.playback;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.eligibility.api.AccountEligibilityView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.InvalidTokenException;
import com.shortvideo.shared.security.PlaybackClaims;
import com.shortvideo.shared.security.PlaybackMode;
import com.shortvideo.shared.security.PlaybackTokenService;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import com.shortvideo.video.api.VideoPlaybackView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Per-request authorization for the media gateway (brief section 8). Enforces the
 * authority order on every request:
 *
 * <pre>
 * trusted owner identity from persisted video state
 * -> Redis active video/account deny
 * -> durable PostgreSQL video/account revocation records
 * -> durable eligibility decision explicitly allows (public mode only)
 * -> media delivery
 * </pre>
 */
@Component
class MediaAuthorizer {

    private final PlaybackTokenService tokenService;
    private final VideoPlaybackDirectory videoDirectory;
    private final AccountDirectory accountDirectory;
    private final EligibilityDirectory eligibilityDirectory;
    private final RevocationCache revocationCache;
    private final DurableRevocationReader revocationReader;
    private final MeterRegistry meterRegistry;
    private final Timer.Builder revocationCheckTimerBuilder;

    MediaAuthorizer(
            PlaybackTokenService tokenService,
            VideoPlaybackDirectory videoDirectory,
            AccountDirectory accountDirectory,
            EligibilityDirectory eligibilityDirectory,
            RevocationCache revocationCache,
            DurableRevocationReader revocationReader,
            MeterRegistry meterRegistry) {
        this.tokenService = tokenService;
        this.videoDirectory = videoDirectory;
        this.accountDirectory = accountDirectory;
        this.eligibilityDirectory = eligibilityDirectory;
        this.revocationCache = revocationCache;
        this.revocationReader = revocationReader;
        this.meterRegistry = meterRegistry;
        this.revocationCheckTimerBuilder = Timer.builder("media.revocation_check")
                .description("Latency of the durable revocation check on the media authorization path")
                .publishPercentileHistogram();
    }

    /**
     * Timed end-to-end and tagged by outcome, so p50/p95/p99 latency and the
     * allow/deny/error mix are both visible under concurrent load (brief
     * section 20, Milestone 8: "revocation latency distributions are measured
     * under concurrent load").
     */
    void authorize(HttpServletRequest request, MediaObjectKey key, AuthenticatedAccount viewer) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            doAuthorize(request, key, viewer);
            outcome = "allow";
        } catch (MediaAuthorizationException.Unauthorized | MediaAuthorizationException.Forbidden e) {
            outcome = "deny";
            throw e;
        } finally {
            sample.stop(Timer.builder("media.authorization")
                    .description("End-to-end media gateway authorization latency, by outcome")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private void doAuthorize(HttpServletRequest request, MediaObjectKey key, AuthenticatedAccount viewer) {
        PlaybackClaims claims = extractClaims(request);

        if (!claims.videoId().equals(key.videoId()) || claims.processingVersion() != key.processingVersion()) {
            throw new MediaAuthorizationException.Unauthorized("Playback token does not match the requested asset");
        }
        if (viewer == null || !claims.viewerId().equals(viewer.accountId())) {
            throw new MediaAuthorizationException.Unauthorized("Session and playback token disagree on viewer identity");
        }

        try {
            VideoPlaybackView video = videoDirectory
                    .findForPlayback(key.videoId())
                    .orElseThrow(() -> new MediaAuthorizationException.Forbidden("Unknown video"));

            // The cookie is scoped to one exact version; a reprocessed video whose
            // currentProcessingVersion has moved on invalidates outstanding cookies.
            if (video.currentProcessingVersion() == null || video.currentProcessingVersion() != key.processingVersion()) {
                throw new MediaAuthorizationException.Forbidden("Requested version is not the current version");
            }

            requireNotRevoked(RevocationSubjects.VIDEO, key.videoId());
            requireNotRevoked(RevocationSubjects.ACCOUNT, video.ownerAccountId());

            if (PlaybackMode.OWNER_PREVIEW.equals(claims.mode())) {
                authorizeOwnerPreview(video, viewer, key.processingVersion());
            } else if (PlaybackMode.PUBLIC.equals(claims.mode())) {
                authorizePublic(key.videoId());
            } else if (PlaybackMode.MODERATOR_PREVIEW.equals(claims.mode())) {
                authorizeModeratorPreview(video, viewer, key.processingVersion());
            } else {
                throw new MediaAuthorizationException.Forbidden("Unknown playback mode");
            }
        } catch (DataAccessException e) {
            throw new MediaAuthorizationException.Unavailable("Authorization state is unavailable", e);
        }
    }

    private void authorizeOwnerPreview(VideoPlaybackView video, AuthenticatedAccount viewer, int requestedVersion) {
        if (!video.ownerAccountId().equals(viewer.accountId())) {
            throw new MediaAuthorizationException.Forbidden("Viewer is not the owner");
        }
        if (!video.ownerPreviewEligible(requestedVersion)) {
            throw new MediaAuthorizationException.Forbidden("Video is not ready for preview");
        }
        boolean ownerActive = accountDirectory
                .find(video.ownerAccountId())
                .map(a -> a.isEligible())
                .orElse(false);
        if (!ownerActive) {
            throw new MediaAuthorizationException.Forbidden("Owner account is not active");
        }
    }

    /**
     * Ownership is irrelevant here — the whole point is letting a moderator
     * watch someone else's pending content. Re-checks the ADMIN role
     * independently of the token's mode claim, the same way owner-preview
     * re-checks ownership rather than trusting the mode alone.
     */
    private void authorizeModeratorPreview(VideoPlaybackView video, AuthenticatedAccount viewer, int requestedVersion) {
        if (!viewer.roles().contains("ADMIN")) {
            throw new MediaAuthorizationException.Forbidden("Viewer is not an admin");
        }
        if (!video.ownerPreviewEligible(requestedVersion)) {
            throw new MediaAuthorizationException.Forbidden("Video is not ready for preview");
        }
    }

    private void authorizePublic(String videoId) {
        VideoEligibilityView videoEligibility = eligibilityDirectory
                .findVideoEligibility(videoId)
                .filter(VideoEligibilityView::isVideoEligible)
                .orElseThrow(() -> new MediaAuthorizationException.Forbidden("Video is not publicly eligible"));

        AccountEligibilityView accountEligibility = eligibilityDirectory
                .findAccountEligibility(videoEligibility.creatorId())
                .filter(AccountEligibilityView::isAccountEligible)
                .orElseThrow(() -> new MediaAuthorizationException.Forbidden("Creator account is not eligible"));
        if (accountEligibility.accountId() == null) {
            throw new MediaAuthorizationException.Forbidden("Creator account is not eligible");
        }
    }

    private void requireNotRevoked(String subjectType, String subjectId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean denied;
        try {
            denied = revocationCache.isDenied(subjectType, subjectId) || revocationReader.isActive(subjectType, subjectId);
        } finally {
            sample.stop(revocationCheckTimerBuilder.tag("subject_type", subjectType).register(meterRegistry));
        }
        if (denied) {
            throw new MediaAuthorizationException.Forbidden("Playback is restricted for " + subjectType);
        }
    }

    private PlaybackClaims extractClaims(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        String token = null;
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (tokenService.cookieName().equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        if (token == null || token.isBlank()) {
            throw new MediaAuthorizationException.Unauthorized("Missing playback cookie");
        }
        try {
            return tokenService.parse(token);
        } catch (InvalidTokenException e) {
            throw new MediaAuthorizationException.Unauthorized("Invalid or expired playback cookie");
        }
    }
}

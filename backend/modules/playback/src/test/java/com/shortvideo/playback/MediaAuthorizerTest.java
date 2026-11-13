package com.shortvideo.playback;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.revocation.RevocationSources;
import com.shortvideo.shared.revocation.RevocationSubjects;
import com.shortvideo.shared.security.AuthenticatedAccount;
import com.shortvideo.shared.security.DeviceIdentity;
import com.shortvideo.shared.security.JwtAuthenticationFilter;
import com.shortvideo.shared.security.PlaybackClaims;
import com.shortvideo.shared.security.PlaybackMode;
import com.shortvideo.shared.security.PlaybackTokenService;
import com.shortvideo.video.api.AssetLifecycleState;
import com.shortvideo.video.api.DurabilityState;
import com.shortvideo.video.api.LegalServingState;
import com.shortvideo.video.api.ProcessingState;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import com.shortvideo.video.api.VideoPlaybackView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MediaAuthorizerTest {

    private static final String VIDEO_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String ACCOUNT_ID = "11111111-1111-1111-1111-111111111111";

    private final PlaybackTokenService tokenService = mock(PlaybackTokenService.class);
    private final VideoPlaybackDirectory videoDirectory = mock(VideoPlaybackDirectory.class);
    private final RevocationCache revocationCache = mock(RevocationCache.class);
    private final DurableRevocationReader revocationReader = mock(DurableRevocationReader.class);
    private final MediaAuthorizer authorizer = new MediaAuthorizer(
            tokenService,
            videoDirectory,
            mock(AccountDirectory.class),
            mock(EligibilityDirectory.class),
            revocationCache,
            revocationReader,
            mock(DeviceIdentity.class),
            new SimpleMeterRegistry());

    private final MediaObjectKey key =
            new MediaObjectKey(VIDEO_ID, 1, "master.m3u8", "processed/" + VIDEO_ID + "/1/master.m3u8");

    private MockHttpServletRequest requestWithPlaybackCookie() {
        when(tokenService.cookieName()).thenReturn("sv_playback");
        when(tokenService.parse("token"))
                .thenReturn(new PlaybackClaims(
                        "jti", ACCOUNT_ID, VIDEO_ID, 1, PlaybackMode.PUBLIC, Instant.now().plusSeconds(60)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("sv_playback", "token"));
        return request;
    }

    /** /media is permitAll, so the filter's "could not judge the session" flag is the only trace of it. */
    @Test
    void answersUnavailableNotUnauthorizedWhenTheSessionCouldNotBeJudged() {
        MockHttpServletRequest request = requestWithPlaybackCookie();
        request.setAttribute(JwtAuthenticationFilter.AUTH_STATE_UNAVAILABLE, Boolean.TRUE);

        assertThatThrownBy(() -> authorizer.authorize(request, key, null))
                .isInstanceOf(MediaAuthorizationException.Unavailable.class);
    }

    @Test
    void stillAnswersUnauthorizedForAViewerWithNoSessionAtAll() {
        MockHttpServletRequest request = requestWithPlaybackCookie();

        assertThatThrownBy(() -> authorizer.authorize(request, key, null))
                .isInstanceOf(MediaAuthorizationException.Unauthorized.class);
    }

    // ------------------------------------------------------------- moderator preview

    private MockHttpServletRequest requestWithModeratorPreviewCookie() {
        when(tokenService.cookieName()).thenReturn("sv_playback");
        when(tokenService.parse("token"))
                .thenReturn(new PlaybackClaims(
                        "jti", ACCOUNT_ID, VIDEO_ID, 1, PlaybackMode.MODERATOR_PREVIEW, Instant.now().plusSeconds(60)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("sv_playback", "token"));
        return request;
    }

    private AuthenticatedAccount viewerWith(String... roles) {
        Instant now = Instant.now();
        return new AuthenticatedAccount(ACCOUNT_ID, Set.of(roles), "t", now, now.plusSeconds(60), now);
    }

    private void aReadyVideoOwnedBySomeoneElse() {
        aVideoOwnedBySomeoneElse(AssetLifecycleState.ACTIVE);
    }

    private void aVideoOwnedBySomeoneElse(AssetLifecycleState lifecycle) {
        when(videoDirectory.findForPlayback(VIDEO_ID))
                .thenReturn(java.util.Optional.of(new VideoPlaybackView(
                        VIDEO_ID, "22222222-2222-2222-2222-222222222222", ProcessingState.READY, 1,
                        DurabilityState.DURABLE, lifecycle, LegalServingState.CLEAR, 1)));
    }

    private void revokedFor(String subjectType, String subjectId, String... sources) {
        when(revocationCache.isDenied(subjectType, subjectId)).thenReturn(true);
        when(revocationCache.activeSources(subjectType, subjectId)).thenReturn(Set.of(sources));
        when(revocationReader.isActive(subjectType, subjectId)).thenReturn(true);
        when(revocationReader.activeSources(subjectType, subjectId)).thenReturn(Set.of(sources));
    }

    @Test
    void aModeratorAndAnAdminMayPreviewSomeoneElsesVideo() {
        aReadyVideoOwnedBySomeoneElse();

        assertThatCode(() -> authorizer.authorize(requestWithModeratorPreviewCookie(), key, viewerWith("USER", "MODERATOR")))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorizer.authorize(requestWithModeratorPreviewCookie(), key, viewerWith("USER", "ADMIN")))
                .doesNotThrowAnyException();
    }

    /** The preview token's mode is not trusted alone: the role is checked again where the media is served. */
    @Test
    void anOrdinaryUserHoldingAModeratorPreviewTokenIsRefused() {
        aReadyVideoOwnedBySomeoneElse();

        assertThatThrownBy(() -> authorizer.authorize(requestWithModeratorPreviewCookie(), key, viewerWith("USER")))
                .isInstanceOf(MediaAuthorizationException.Forbidden.class);
    }

    /** Deciding an appeal means watching the rejected video, which a rejection otherwise shuts to everyone. */
    @Test
    void aModeratorMayWatchARejectedVideoButAnythingElseRecordedAgainstItStillBlocks() {
        aVideoOwnedBySomeoneElse(AssetLifecycleState.REJECTED_RETAINED);
        revokedFor(RevocationSubjects.VIDEO, VIDEO_ID, RevocationSources.MODERATION);

        assertThatCode(() -> authorizer.authorize(requestWithModeratorPreviewCookie(), key, viewerWith("MODERATOR")))
                .doesNotThrowAnyException();

        revokedFor(RevocationSubjects.VIDEO, VIDEO_ID, RevocationSources.MODERATION, RevocationSources.LIFECYCLE);
        assertThatThrownBy(() -> authorizer.authorize(requestWithModeratorPreviewCookie(), key, viewerWith("MODERATOR")))
                .isInstanceOf(MediaAuthorizationException.Forbidden.class);
    }

    @Test
    void aQuarantinedVideoIsNotWatchableEvenByStaff() {
        aVideoOwnedBySomeoneElse(AssetLifecycleState.QUARANTINED);

        assertThatThrownBy(() -> authorizer.authorize(requestWithModeratorPreviewCookie(), key, viewerWith("ADMIN")))
                .isInstanceOf(MediaAuthorizationException.Forbidden.class);
    }
}

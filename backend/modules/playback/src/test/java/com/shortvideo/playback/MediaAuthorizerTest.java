package com.shortvideo.playback;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shortvideo.account.api.AccountDirectory;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.RevocationCache;
import com.shortvideo.shared.security.DeviceIdentity;
import com.shortvideo.shared.security.JwtAuthenticationFilter;
import com.shortvideo.shared.security.PlaybackClaims;
import com.shortvideo.shared.security.PlaybackMode;
import com.shortvideo.shared.security.PlaybackTokenService;
import com.shortvideo.video.api.VideoPlaybackDirectory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MediaAuthorizerTest {

    private static final String VIDEO_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final String ACCOUNT_ID = "11111111-1111-1111-1111-111111111111";

    private final PlaybackTokenService tokenService = mock(PlaybackTokenService.class);
    private final MediaAuthorizer authorizer = new MediaAuthorizer(
            tokenService,
            mock(VideoPlaybackDirectory.class),
            mock(AccountDirectory.class),
            mock(EligibilityDirectory.class),
            mock(RevocationCache.class),
            mock(DurableRevocationReader.class),
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
}

package com.shortvideo.shared.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Playback cookie transport (brief section 8). Path-scoped to the exact
 * {@code /media/videos/{videoId}/{processingVersion}/} prefix — the Path controls
 * only when the browser sends the cookie, not authorization; the gateway still
 * re-verifies every claim on every request.
 */
@Component
public class PlaybackCookies {

    private final PlaybackTokenService tokenService;

    public PlaybackCookies(PlaybackTokenService tokenService) {
        this.tokenService = tokenService;
    }

    public ResponseCookie cookie(String token, Instant expiresAt, String videoId, int processingVersion) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        return ResponseCookie.from(tokenService.cookieName(), token)
                .httpOnly(true)
                .secure(tokenService.cookieSecure())
                .sameSite("Lax")
                .path("/media/videos/" + videoId + "/" + processingVersion + "/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
    }
}

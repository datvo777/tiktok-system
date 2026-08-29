package com.shortvideo.shared.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Session cookie transport (brief section 12.1, Rule 17).
 *
 * <p>Login sets the same JWT both in the response body (for the SPA's bearer
 * header) and as an HttpOnly cookie. Media requests are issued by hls.js or the
 * video element and cannot carry an Authorization header — Safari's native HLS
 * cannot set headers at all — so the cookie is the only credential the gateway
 * can see.
 *
 * <p>SameSite=Lax means the browser must see one origin: the Vite dev server
 * proxies /api, /internal and /media to the backend.
 */
@Component
public class SessionCookies {

    private final JwtProperties properties;

    public SessionCookies(JwtProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie session(String token, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        return ResponseCookie.from(properties.getSessionCookieName(), token)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
    }

    public ResponseCookie clearSession() {
        return ResponseCookie.from(properties.getSessionCookieName(), "")
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    public String sessionCookieName() {
        return properties.getSessionCookieName();
    }
}

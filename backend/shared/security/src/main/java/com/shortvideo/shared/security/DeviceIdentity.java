package com.shortvideo.shared.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * An opaque identifier for a browser that is not signed in.
 *
 * <p><b>Why this exists.</b> The media gateway requires a playback cookie
 * <em>and</em> an identity that matches the token's {@code viewerId}, so a stolen
 * playback cookie is not on its own enough to fetch a segment. Signed-out viewing
 * needs an identity to bind to, and dropping the check instead would make the
 * playback cookie a bearer credential for everyone at once — a strictly weaker
 * model than the one signed-in viewers get. This keeps the shape of that
 * invariant and only changes what the identity denotes.
 *
 * <p><b>What it is not.</b> Not an account, not a login, and not a claim about a
 * person: it identifies a browser, survives only as long as its cookie, and grants
 * nothing beyond the ability to hold a playback session for public videos. It is
 * deliberately unusable as an authentication token — {@code JwtAuthenticationFilter}
 * never consults it, so no request is ever <em>authenticated</em> because of it.
 *
 * <p>Signed with the same key material as the playback token, so a forged device
 * id cannot be presented; the signature is what stops an attacker from claiming
 * to be the device a playback token was minted for.
 */
@Component
public class DeviceIdentity {

    public static final String COOKIE_NAME = "sv_device";

    /** Marks a playback token's viewer as a device rather than an account. */
    public static final String VIEWER_PREFIX = "device:";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Duration MAX_AGE = Duration.ofDays(180);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecretKeySpec key;
    private final boolean cookieSecure;

    public DeviceIdentity(TokenKeys tokenKeys, PlaybackCookieProperties properties) {
        // The playback key, not the session key: this cookie only ever gates playback.
        this.key = new SecretKeySpec(tokenKeys.playbackKey().getEncoded(), HMAC_ALGORITHM);
        this.cookieSecure = properties.isCookieSecure();
    }

    /**
     * The device id carried by this request, if it has a valid one.
     *
     * <p>An absent, malformed or badly-signed cookie all return empty rather than
     * throwing: a missing device is the normal state of a first visit, and a
     * tampered one should behave exactly like no device at all.
     */
    public Optional<String> fromRequest(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return verify(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** A fresh device id, to be handed back with {@link #cookie(String)}. */
    public String newDeviceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Root path, unlike the playback cookie: the browser has to present this on
     * both {@code /api} (to mint a session) and {@code /media} (to use it).
     * SameSite=Lax for the same reason the session cookie is — a cross-site POST
     * must not carry it.
     */
    public ResponseCookie cookie(String deviceId) {
        return ResponseCookie.from(COOKIE_NAME, sign(deviceId))
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(MAX_AGE)
                .build();
    }

    /** The value a playback token records as its viewer for this device. */
    public static String viewerId(String deviceId) {
        return VIEWER_PREFIX + deviceId;
    }

    public static boolean isDeviceViewer(String viewerId) {
        return viewerId != null && viewerId.startsWith(VIEWER_PREFIX);
    }

    private String sign(String deviceId) {
        return deviceId + "." + ENCODER.encodeToString(mac(deviceId));
    }

    private Optional<String> verify(String value) {
        if (value == null) {
            return Optional.empty();
        }
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot == value.length() - 1) {
            return Optional.empty();
        }
        String deviceId = value.substring(0, dot);
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(value.substring(dot + 1));
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        // Constant-time: a byte-by-byte comparison here leaks how much of a
        // forged signature was correct.
        if (!MessageDigest.isEqual(presented, mac(deviceId))) {
            return Optional.empty();
        }
        try {
            // Canonical UUID only, so the id that reaches a token claim cannot be
            // arbitrary attacker-chosen text.
            return Optional.of(UUID.fromString(deviceId).toString().equals(deviceId) ? deviceId : null)
                    .filter(java.util.Objects::nonNull);
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private byte[] mac(String deviceId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(deviceId.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Device cookie signing is misconfigured", e);
        }
    }
}

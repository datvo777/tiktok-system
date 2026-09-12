package com.shortvideo.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Derives one purpose-bound signing key per token type from the single configured
 * secret (brief section 12.1).
 *
 * <p>Session tokens and playback tokens previously shared the raw secret verbatim.
 * Nothing actually crossed over, but only by accident: a session token happens to
 * lack {@code videoId} so playback parsing rejected it, and a playback token
 * happened to lack {@code iss} so {@link JwtService}'s {@code requireIssuer}
 * rejected it. Both of those are properties of the claim sets, not of the
 * cryptography — adding an issuer to playback tokens for consistency would have
 * silently turned a 5-minute media cookie into a 30-minute API credential.
 *
 * <p>Separate keys make that structural: a token signed for one purpose fails
 * signature verification for the other, whatever claims it carries. Paired with
 * the {@code typ} claim each parser requires, confusion needs two independent
 * mistakes rather than one.
 *
 * <p>This is HKDF-Expand (RFC 5869) with a single output block, which is all that
 * is needed for a 32-byte key and avoids pulling in a crypto provider dependency.
 */
@Component
public class TokenKeys {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKey sessionKey;
    private final SecretKey playbackKey;

    public TokenKeys(JwtProperties properties) {
        byte[] root = JwtService.requireUsableSecret(
                        properties.getSecret(), properties.isAllowInsecureSecret())
                .getBytes(StandardCharsets.UTF_8);
        this.sessionKey = derive(root, "shortvideo/session-token/v1");
        this.playbackKey = derive(root, "shortvideo/playback-token/v1");
    }

    /** Signs and verifies the API session token issued at login. */
    public SecretKey sessionKey() {
        return sessionKey;
    }

    /** Signs and verifies the short-lived, path-scoped media playback cookie. */
    public SecretKey playbackKey() {
        return playbackKey;
    }

    private static SecretKey derive(byte[] root, String info) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(root, HMAC_ALGORITHM));
            // HKDF-Expand with L = 32, so a single block with the 0x01 counter.
            mac.update(info.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 1);
            return new SecretKeySpec(mac.doFinal(), HMAC_ALGORITHM);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required to derive token keys", e);
        }
    }
}

package com.shortvideo.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Short-lived signed playback session tokens (brief section 8).
 *
 * <p>Signed with a key derived specifically for playback (see {@link TokenKeys})
 * and stamped with a {@code typ} claim this parser requires, so a playback cookie
 * and an API session token can never be substituted for one another regardless of
 * what claims they happen to carry.
 */
@Service
public class PlaybackTokenService {

    private static final String EXPECTED_ALG = "HS256";
    private static final String VIDEO_ID_CLAIM = "videoId";
    private static final String VERSION_CLAIM = "processingVersion";
    private static final String MODE_CLAIM = "mode";
    /** Distinguishes a playback token from a session token; see {@link TokenKeys}. */
    private static final String TYPE_CLAIM = "typ";
    static final String PLAYBACK_TYPE = "playback";

    private final PlaybackCookieProperties properties;
    private final SecretKey key;

    public PlaybackTokenService(PlaybackCookieProperties properties, TokenKeys tokenKeys) {
        this.properties = properties;
        this.key = tokenKeys.playbackKey();
    }

    public IssuedPlaybackToken issue(String viewerId, String videoId, int processingVersion, String mode) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getTtl());
        String sessionId = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(sessionId)
                .subject(viewerId)
                .claim(VIDEO_ID_CLAIM, videoId)
                .claim(VERSION_CLAIM, processingVersion)
                .claim(MODE_CLAIM, mode)
                .claim(TYPE_CLAIM, PLAYBACK_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedPlaybackToken(token, sessionId, expiry);
    }

    public PlaybackClaims parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);

            String algorithm = jws.getHeader().getAlgorithm();
            if (!EXPECTED_ALG.equals(algorithm)) {
                throw new InvalidTokenException("Unexpected playback token algorithm: " + algorithm);
            }

            Claims claims = jws.getPayload();
            if (!PLAYBACK_TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                throw new InvalidTokenException("Token is not a playback token");
            }

            String subject = claims.getSubject();
            String videoId = claims.get(VIDEO_ID_CLAIM, String.class);
            Integer version = claims.get(VERSION_CLAIM, Integer.class);
            String mode = claims.get(MODE_CLAIM, String.class);
            if (subject == null || videoId == null || version == null || mode == null) {
                throw new InvalidTokenException("Playback token is missing required claims");
            }
            // Constrain the mode here rather than leaving an unknown value to be
            // caught by the authorizer's if-chain, so an unrecognised mode can
            // never reach a branch that was added later without a matching guard.
            if (!PlaybackMode.isKnown(mode)) {
                throw new InvalidTokenException("Playback token carries an unknown mode");
            }

            return new PlaybackClaims(
                    claims.getId(), subject, videoId, version, mode, claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Playback token rejected: " + e.getMessage(), e);
        }
    }

    public String cookieName() {
        return properties.getCookieName();
    }

    public boolean cookieSecure() {
        return properties.isCookieSecure();
    }

    public record IssuedPlaybackToken(String token, String sessionId, Instant expiresAt) {}
}

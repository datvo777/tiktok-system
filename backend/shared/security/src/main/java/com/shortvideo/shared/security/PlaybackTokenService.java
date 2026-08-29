package com.shortvideo.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Short-lived signed playback session tokens (brief section 8). Reuses the same
 * local HS256 secret as {@link JwtService} — this is local-MVP infrastructure, not
 * a separate trust boundary.
 */
@Service
public class PlaybackTokenService {

    private static final String EXPECTED_ALG = "HS256";
    private static final String VIDEO_ID_CLAIM = "videoId";
    private static final String VERSION_CLAIM = "processingVersion";
    private static final String MODE_CLAIM = "mode";

    private final PlaybackCookieProperties properties;
    private final SecretKey key;

    public PlaybackTokenService(JwtProperties jwtProperties, PlaybackCookieProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
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
            String subject = claims.getSubject();
            String videoId = claims.get(VIDEO_ID_CLAIM, String.class);
            Integer version = claims.get(VERSION_CLAIM, Integer.class);
            String mode = claims.get(MODE_CLAIM, String.class);
            if (subject == null || videoId == null || version == null || mode == null) {
                throw new InvalidTokenException("Playback token is missing required claims");
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

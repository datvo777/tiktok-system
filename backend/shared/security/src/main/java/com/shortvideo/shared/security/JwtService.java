package com.shortvideo.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Short-lived HS256 tokens for the local MVP (brief section 12.1).
 *
 * <p>The algorithm is server-selected and re-checked on parse, so a token
 * presenting a different alg (including "none") is rejected rather than trusted.
 */
@Service
public class JwtService {

    private static final String ROLES_CLAIM = "roles";
    private static final String EXPECTED_ALG = "HS256";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "shortvideo.jwt.secret must be at least 32 bytes for HS256. "
                            + "Set JWT_SECRET in your .env file.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issue(String accountId, Set<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getTtl());
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(accountId)
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(ROLES_CLAIM, roles.stream().sorted().toList())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiry);
    }

    public AuthenticatedAccount parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token);

            String algorithm = jws.getHeader().getAlgorithm();
            if (!EXPECTED_ALG.equals(algorithm)) {
                throw new InvalidTokenException("Unexpected token algorithm: " + algorithm);
            }

            Claims claims = jws.getPayload();
            Set<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(properties.getAudience())) {
                throw new InvalidTokenException("Token audience mismatch");
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidTokenException("Token has no subject");
            }

            return new AuthenticatedAccount(
                    subject, readRoles(claims), claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Token rejected: " + e.getMessage(), e);
        }
    }

    private Set<String> readRoles(Claims claims) {
        Object raw = claims.get(ROLES_CLAIM);
        Set<String> roles = new LinkedHashSet<>();
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value != null) {
                    roles.add(value.toString());
                }
            }
        }
        return roles;
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}

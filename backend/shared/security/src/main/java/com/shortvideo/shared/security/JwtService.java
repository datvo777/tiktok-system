package com.shortvideo.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
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

    /**
     * The value {@code .env.example} ships with. It is a valid 32+ byte string, so
     * a length check alone waves it through — which is exactly how a deployment
     * that forgets to set {@code JWT_SECRET} ends up signing tokens with a key
     * published in the repository, and anyone holding the source can mint an
     * {@code ADMIN} token. Named here so it can be refused by value.
     */
    static final String KNOWN_DEV_SECRET = "local-dev-secret-change-me-at-least-32-bytes-long";

    /** Distinguishes a session token from a playback token; see {@link TokenKeys}. */
    private static final String TYPE_CLAIM = "typ";
    static final String SESSION_TYPE = "session";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties, TokenKeys tokenKeys) {
        this.properties = properties;
        this.key = tokenKeys.sessionKey();
    }

    /**
     * @param allowInsecure set only by the {@code local} and {@code test} profiles,
     *     where a shared throwaway key is the point. Every other profile fails to
     *     start rather than run with a guessable signing key.
     */
    static String requireUsableSecret(String secret, boolean allowInsecure) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "shortvideo.jwt.secret is not set. Set JWT_SECRET to at least 32 random bytes "
                            + "(e.g. `openssl rand -base64 48`).");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "shortvideo.jwt.secret must be at least 32 bytes for HS256. "
                            + "Set JWT_SECRET to at least 32 random bytes (e.g. `openssl rand -base64 48`).");
        }
        if (KNOWN_DEV_SECRET.equals(secret) && !allowInsecure) {
            throw new IllegalStateException(
                    "shortvideo.jwt.secret is still the published development placeholder. "
                            + "This key is in the repository and anyone holding it can forge an ADMIN "
                            + "token. Set JWT_SECRET to at least 32 random bytes "
                            + "(e.g. `openssl rand -base64 48`), or set shortvideo.jwt.allow-insecure-secret=true "
                            + "if this really is a local throwaway environment.");
        }
        return secret;
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
                .claim(TYPE_CLAIM, SESSION_TYPE)
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

            // Belt to the derived-key braces: a token minted for a different
            // purpose must not be usable here even if the keys were ever merged.
            if (!SESSION_TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                throw new InvalidTokenException("Token is not a session token");
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidTokenException("Token has no subject");
            }

            String tokenId = claims.getId();
            if (tokenId == null || tokenId.isBlank()) {
                // Without a jti there is nothing for logout to revoke, so a token
                // that lacks one cannot be honoured.
                throw new InvalidTokenException("Token has no id");
            }

            return new AuthenticatedAccount(
                    subject, readRoles(claims), tokenId, claims.getExpiration().toInstant());
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

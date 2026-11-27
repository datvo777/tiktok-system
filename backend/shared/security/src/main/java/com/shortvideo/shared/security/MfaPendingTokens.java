package com.shortvideo.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * The token handed out after the password is right and before the code is: it proves the first step and
 * nothing else. Its own signing key and {@code typ}, so it cannot be presented as a session, and a session
 * cannot be presented here.
 */
@Component
public class MfaPendingTokens {

    static final String TYPE = "mfa-pending";
    private static final String TYPE_CLAIM = "typ";

    private final JwtProperties jwt;
    private final MfaProperties mfa;
    private final SecretKey key;
    // Built once. Building a parser per call runs a classpath scan that stalled the server under load
    // (see the note on JwtService's parser).
    private final JwtParser parser;

    public MfaPendingTokens(JwtProperties jwt, MfaProperties mfa, TokenKeys keys) {
        this.jwt = jwt;
        this.mfa = mfa;
        this.key = keys.mfaPendingKey();
        this.parser = Jwts.parser().verifyWith(key).requireIssuer(jwt.getIssuer()).build();
    }

    public Issued issue(String accountId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(mfa.getPendingTtl());
        String id = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(id)
                .subject(accountId)
                .issuer(jwt.getIssuer())
                .audience().add(jwt.getAudience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(TYPE_CLAIM, TYPE)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new Issued(token, id, expiry);
    }

    /** @throws InvalidTokenException for anything that is not an unexpired, correctly signed pending token */
    public Pending parse(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            if (!TYPE.equals(claims.get(TYPE_CLAIM, String.class))) {
                throw new InvalidTokenException("Not a pending second-factor token");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(jwt.getAudience())) {
                throw new InvalidTokenException("Token audience mismatch");
            }
            String subject = claims.getSubject();
            String id = claims.getId();
            if (subject == null || subject.isBlank() || id == null || id.isBlank()) {
                throw new InvalidTokenException("Token has no subject or id");
            }
            return new Pending(subject, id, claims.getExpiration().toInstant());
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Token rejected: " + e.getMessage(), e);
        }
    }

    public record Issued(String token, String id, Instant expiresAt) {}

    public record Pending(String accountId, String id, Instant expiresAt) {}
}

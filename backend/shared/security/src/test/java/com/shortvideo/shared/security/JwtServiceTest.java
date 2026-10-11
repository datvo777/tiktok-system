package com.shortvideo.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "a-local-test-secret-that-is-long-enough-32";

    private JwtProperties properties;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("short-video-local");
        properties.setAudience("short-video-web");
        properties.setTtl(Duration.ofMinutes(30));
        jwtService = new JwtService(properties, new TokenKeys(properties));
    }

    @Test
    void roundTripsSubjectAndRoles() {
        JwtService.IssuedToken issued = jwtService.issue("account-1", Set.of("USER", "ADMIN"));
        AuthenticatedAccount parsed = jwtService.parse(issued.token());

        assertThat(parsed.accountId()).isEqualTo("account-1");
        assertThat(parsed.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void rejectsAShortSecret() {
        JwtProperties weak = new JwtProperties();
        weak.setSecret("too-short");

        assertThatThrownBy(() -> new JwtService(weak, new TokenKeys(weak)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        JwtProperties other = new JwtProperties();
        other.setSecret("a-different-secret-also-long-enough-here-32");
        String foreign = new JwtService(other, new TokenKeys(other)).issue("account-1", Set.of("USER")).token();

        assertThatThrownBy(() -> jwtService.parse(foreign)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAnExpiredToken() throws InterruptedException {
        JwtProperties shortLived = new JwtProperties();
        shortLived.setSecret(SECRET);
        shortLived.setTtl(Duration.ofMillis(1));
        String token = new JwtService(shortLived, new TokenKeys(shortLived)).issue("account-1", Set.of("USER")).token();

        Thread.sleep(1100);

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAWrongIssuer() {
        JwtProperties other = new JwtProperties();
        other.setSecret(SECRET);
        other.setIssuer("someone-else");
        String token = new JwtService(other, new TokenKeys(other)).issue("account-1", Set.of("USER")).token();

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAWrongAudience() {
        JwtProperties other = new JwtProperties();
        other.setSecret(SECRET);
        other.setAudience("some-other-app");
        String token = new JwtService(other, new TokenKeys(other)).issue("account-1", Set.of("USER")).token();

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwtService.parse("not.a.jwt")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void issuesAUniqueTokenIdSoLogoutCanRevokeOneSession() {
        AuthenticatedAccount first = jwtService.parse(jwtService.issue("account-1", Set.of("USER")).token());
        AuthenticatedAccount second = jwtService.parse(jwtService.issue("account-1", Set.of("USER")).token());

        assertThat(first.tokenId()).isNotBlank();
        assertThat(first.tokenId()).isNotEqualTo(second.tokenId());
    }

    /**
     * The published placeholder is a valid 32+ byte string, so a length check alone
     * accepts it and a deployment that forgets JWT_SECRET signs with a key that is
     * in the repository.
     */
    @Test
    void refusesThePublishedDevelopmentSecret() {
        JwtProperties published = new JwtProperties();
        published.setSecret(JwtService.KNOWN_DEV_SECRET);

        assertThatThrownBy(() -> new JwtService(published, new TokenKeys(published)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development placeholder");
    }

    @Test
    void allowsThePublishedDevelopmentSecretWhenExplicitlyOptedIn() {
        JwtProperties local = new JwtProperties();
        local.setSecret(JwtService.KNOWN_DEV_SECRET);
        local.setAllowInsecureSecret(true);

        assertThat(new JwtService(local, new TokenKeys(local)).issue("account-1", Set.of("USER")).token())
                .isNotBlank();
    }

    /**
     * The two token types previously shared the raw secret and were kept apart only
     * by which claims they happened to carry. Separate derived keys make the
     * separation cryptographic instead of incidental.
     */
    @Test
    void aPlaybackTokenIsNotAcceptedAsASessionToken() {
        PlaybackCookieProperties playbackProperties = new PlaybackCookieProperties();
        PlaybackTokenService playback =
                new PlaybackTokenService(playbackProperties, new TokenKeys(properties));
        String playbackToken = playback
                .issue("account-1", "3f2504e0-4f89-41d3-9a0c-0305e82c3301", 1, PlaybackMode.PUBLIC)
                .token();

        assertThatThrownBy(() -> jwtService.parse(playbackToken)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aSessionTokenIsNotAcceptedAsAPlaybackToken() {
        PlaybackCookieProperties playbackProperties = new PlaybackCookieProperties();
        PlaybackTokenService playback =
                new PlaybackTokenService(playbackProperties, new TokenKeys(properties));
        String sessionToken = jwtService.issue("account-1", Set.of("ADMIN")).token();

        assertThatThrownBy(() -> playback.parse(sessionToken)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void derivesDistinctKeysForTheTwoTokenTypes() {
        TokenKeys keys = new TokenKeys(properties);
        assertThat(keys.sessionKey().getEncoded()).isNotEqualTo(keys.playbackKey().getEncoded());
    }

    @Test
    void derivesTheSameKeysFromTheSameSecret() {
        // Derivation must be deterministic, or a restart would invalidate every
        // outstanding token and every instance would disagree with its peers.
        assertThat(new TokenKeys(properties).sessionKey().getEncoded())
                .isEqualTo(new TokenKeys(properties).sessionKey().getEncoded());
    }

    // ------------------------------------------------------------- absolute session lifetime

    @Test
    void aLoginStampsTheSignInTimeAndARenewalKeepsIt() {
        Instant loggedIn = Instant.now().minus(Duration.ofDays(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        AuthenticatedAccount renewed =
                jwtService.parse(jwtService.issue("account-1", Set.of("USER"), loggedIn).token());
        AuthenticatedAccount fresh = jwtService.parse(jwtService.issue("account-1", Set.of("USER")).token());

        assertThat(renewed.authTime()).isEqualTo(loggedIn);
        // A new token's own issue time is later; only the sign-in time is carried.
        assertThat(renewed.issuedAt()).isAfter(renewed.authTime());
        assertThat(fresh.authTime()).isEqualTo(fresh.issuedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void aTokenIssuedBeforeTheClaimExistedCountsFromWhenItWasIssued() {
        Instant issuedAt = Instant.now().minus(Duration.ofMinutes(20));
        String legacy = io.jsonwebtoken.Jwts.builder()
                .id("legacy-1")
                .subject("account-1")
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .issuedAt(java.util.Date.from(issuedAt))
                .expiration(java.util.Date.from(issuedAt.plus(Duration.ofMinutes(30))))
                .claim("roles", java.util.List.of("USER"))
                .claim("typ", JwtService.SESSION_TYPE)
                .signWith(new TokenKeys(properties).sessionKey(), io.jsonwebtoken.Jwts.SIG.HS256)
                .compact();

        AuthenticatedAccount parsed = jwtService.parse(legacy);

        assertThat(parsed.authTime()).isEqualTo(parsed.issuedAt());
    }

    @Test
    void aSignInTimeInTheFutureIsNotBelieved() {
        String token = jwtService.issue("account-1", Set.of("USER"), Instant.now().plus(Duration.ofDays(5))).token();

        AuthenticatedAccount parsed = jwtService.parse(token);

        assertThat(parsed.authTime()).isEqualTo(parsed.issuedAt());
    }

    @Test
    void privilegedAccountsGetTheShorterLimit() {
        properties.setAbsoluteTtl(Duration.ofDays(30));
        properties.setPrivilegedAbsoluteTtl(Duration.ofHours(12));

        assertThat(jwtService.absoluteTtlFor(Set.of("USER"))).isEqualTo(Duration.ofDays(30));
        assertThat(jwtService.absoluteTtlFor(Set.of("USER", "ADMIN"))).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void theLimitIsJudgedOnTheRolesHeldNowNotTheOnesInTheToken() {
        properties.setAbsoluteTtl(Duration.ofDays(30));
        properties.setPrivilegedAbsoluteTtl(Duration.ofHours(12));
        AuthenticatedAccount thirteenHoursIn = jwtService.parse(
                jwtService.issue("account-1", Set.of("USER"), Instant.now().minus(Duration.ofHours(13))).token());

        assertThat(jwtService.exceedsAbsoluteLifetime(thirteenHoursIn, Set.of("USER"), Instant.now())).isFalse();
        // Promoted to admin since: the same session is now past the limit that applies to admins.
        assertThat(jwtService.exceedsAbsoluteLifetime(thirteenHoursIn, Set.of("USER", "ADMIN"), Instant.now())).isTrue();
    }
}

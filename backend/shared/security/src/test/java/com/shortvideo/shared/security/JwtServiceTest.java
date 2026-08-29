package com.shortvideo.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
        jwtService = new JwtService(properties);
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

        assertThatThrownBy(() -> new JwtService(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        JwtProperties other = new JwtProperties();
        other.setSecret("a-different-secret-also-long-enough-here-32");
        String foreign = new JwtService(other).issue("account-1", Set.of("USER")).token();

        assertThatThrownBy(() -> jwtService.parse(foreign)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAnExpiredToken() throws InterruptedException {
        JwtProperties shortLived = new JwtProperties();
        shortLived.setSecret(SECRET);
        shortLived.setTtl(Duration.ofMillis(1));
        String token = new JwtService(shortLived).issue("account-1", Set.of("USER")).token();

        Thread.sleep(1100);

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAWrongIssuer() {
        JwtProperties other = new JwtProperties();
        other.setSecret(SECRET);
        other.setIssuer("someone-else");
        String token = new JwtService(other).issue("account-1", Set.of("USER")).token();

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAWrongAudience() {
        JwtProperties other = new JwtProperties();
        other.setSecret(SECRET);
        other.setAudience("some-other-app");
        String token = new JwtService(other).issue("account-1", Set.of("USER")).token();

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwtService.parse("not.a.jwt")).isInstanceOf(InvalidTokenException.class);
    }
}

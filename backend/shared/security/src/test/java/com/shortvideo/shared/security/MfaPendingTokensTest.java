package com.shortvideo.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MfaPendingTokensTest {

    private static final String ACCOUNT = "11111111-1111-1111-1111-111111111111";

    private final JwtProperties jwt = jwtProperties();
    private final MfaProperties mfa = new MfaProperties();
    private final TokenKeys keys = new TokenKeys(jwt);
    private final MfaPendingTokens pending = new MfaPendingTokens(jwt, mfa, keys);
    private final JwtService sessions = new JwtService(jwt, keys);

    private static JwtProperties jwtProperties() {
        JwtProperties p = new JwtProperties();
        p.setSecret("a-local-test-secret-that-is-long-enough-32");
        p.setIssuer("short-video-local");
        p.setAudience("short-video-web");
        p.setTtl(Duration.ofMinutes(30));
        return p;
    }

    @Test
    void roundTripsTheAccountAndAnId() {
        var issued = pending.issue(ACCOUNT);
        var parsed = pending.parse(issued.token());

        assertThat(parsed.accountId()).isEqualTo(ACCOUNT);
        assertThat(parsed.id()).isEqualTo(issued.id());
        assertThat(parsed.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void everyTokenHasItsOwnId() {
        assertThat(pending.issue(ACCOUNT).id()).isNotEqualTo(pending.issue(ACCOUNT).id());
    }

    @Test
    void aPendingTokenIsNotASession() {
        String token = pending.issue(ACCOUNT).token();

        assertThatThrownBy(() -> sessions.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aSessionIsNotAPendingToken() {
        String session = sessions.issue(ACCOUNT, Set.of("ADMIN")).token();

        assertThatThrownBy(() -> pending.parse(session)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void garbageAndTamperedTokensAreRefused() {
        assertThatThrownBy(() -> pending.parse("not-a-token")).isInstanceOf(InvalidTokenException.class);
        String token = pending.issue(ACCOUNT).token();
        assertThatThrownBy(() -> pending.parse(token.substring(0, token.length() - 2) + "xx"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void anExpiredTokenIsRefused() {
        MfaProperties expired = new MfaProperties();
        expired.setPendingTtl(Duration.ofSeconds(-5));
        var tokens = new MfaPendingTokens(jwt, expired, keys);

        assertThatThrownBy(() -> tokens.parse(tokens.issue(ACCOUNT).token())).isInstanceOf(InvalidTokenException.class);
    }
}

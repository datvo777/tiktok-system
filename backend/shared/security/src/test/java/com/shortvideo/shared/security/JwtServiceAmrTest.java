package com.shortvideo.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** How the session says what the person used to sign in. */
class JwtServiceAmrTest {

    private static final String ACCOUNT = "11111111-1111-1111-1111-111111111111";

    private final JwtService jwt = service();

    private static JwtService service() {
        JwtProperties p = new JwtProperties();
        p.setSecret("a-local-test-secret-that-is-long-enough-32");
        p.setIssuer("short-video-local");
        p.setAudience("short-video-web");
        p.setTtl(Duration.ofMinutes(30));
        return new JwtService(p, new TokenKeys(p));
    }

    @Test
    void anOrdinaryLoginIsPasswordOnly() {
        AuthenticatedAccount caller = jwt.parse(jwt.issue(ACCOUNT, Set.of("ADMIN")).token());

        assertThat(caller.amr()).containsExactly("pwd");
        assertThat(caller.hasSecondFactor()).isFalse();
    }

    @Test
    void aSessionThatUsedACodeSaysSo() {
        String token = jwt.issue(ACCOUNT, Set.of("ADMIN"), Instant.now(), Set.of("pwd", "otp")).token();

        AuthenticatedAccount caller = jwt.parse(token);

        assertThat(caller.amr()).containsExactlyInAnyOrder("pwd", "otp");
        assertThat(caller.hasSecondFactor()).isTrue();
    }

    @Test
    void renewingCarriesTheMethodsOverWithoutAddingAny() {
        AuthenticatedAccount first = jwt.parse(jwt.issue(ACCOUNT, Set.of("ADMIN")).token());
        AuthenticatedAccount renewed = jwt.parse(
                jwt.issue(ACCOUNT, Set.of("ADMIN"), first.authTime(), first.amr()).token());

        assertThat(renewed.amr()).containsExactly("pwd");
        assertThat(renewed.hasSecondFactor()).isFalse();
    }

    @Test
    void theThreeArgumentIssueStaysPasswordOnly() {
        String token = jwt.issue(ACCOUNT, Set.of("USER"), Instant.now()).token();

        assertThat(jwt.parse(token).hasSecondFactor()).isFalse();
    }
}

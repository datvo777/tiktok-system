package com.shortvideo.account.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 appendix B: the SHA-1 secret, and the 8-digit codes, of which the last six digits are ours. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void producesTheCodesInRfc6238AppendixB() {
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(59L))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(1111111109L))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(1111111111L))).isEqualTo("050471");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(1234567890L))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(2000000000L))).isEqualTo("279037");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(20000000000L))).isEqualTo("353130");
    }

    @Test
    void acceptsTheCurrentStepAndOneEitherSide() {
        long now = 1_000_000L;
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now), now, 0)).isEqualTo(now);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 1), now, 0)).isEqualTo(now - 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + 1), now, 0)).isEqualTo(now + 1);
    }

    @Test
    void refusesTwoStepsAwayInEitherDirection() {
        long now = 1_000_000L;
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 2), now, 0)).isEqualTo(-1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + 2), now, 0)).isEqualTo(-1);
    }

    @Test
    void refusesACodeForAStepAlreadyUsed() {
        long now = 1_000_000L;
        String code = Totp.code(RFC_SECRET, now);

        assertThat(Totp.match(RFC_SECRET, code, now, now)).isEqualTo(-1);
        // An older step than the last one used is refused too, so going back in time does not help.
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 1), now, now)).isEqualTo(-1);
    }

    @Test
    void aLaterStepStillWorksAfterAnEarlierOneWasUsed() {
        long now = 1_000_000L;
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + 1), now, now)).isEqualTo(now + 1);
    }

    @Test
    void refusesAnythingThatIsNotSixDigits() {
        long now = 1_000_000L;
        assertThat(Totp.match(RFC_SECRET, null, now, 0)).isEqualTo(-1);
        assertThat(Totp.match(RFC_SECRET, "", now, 0)).isEqualTo(-1);
        assertThat(Totp.match(RFC_SECRET, "12345", now, 0)).isEqualTo(-1);
        assertThat(Totp.match(RFC_SECRET, "1234567", now, 0)).isEqualTo(-1);
        assertThat(Totp.match(RFC_SECRET, "12345a", now, 0)).isEqualTo(-1);
        assertThat(Totp.match(RFC_SECRET, " 12345", now, 0)).isEqualTo(-1);
    }

    @Test
    void aNewSecretIs160BitsAndDiffersEachTime() {
        byte[] a = Totp.newSecret();
        byte[] b = Totp.newSecret();
        assertThat(a).hasSize(20);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void theUriCarriesWhatAnAuthenticatorAppNeeds() {
        String uri = Totp.uri("Short", "admin@example.com", RFC_SECRET);

        assertThat(uri).startsWith("otpauth://totp/Short:admin%40example.com?");
        assertThat(uri).contains("secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(uri).contains("issuer=Short").contains("digits=6").contains("period=30").contains("algorithm=SHA1");
    }
}

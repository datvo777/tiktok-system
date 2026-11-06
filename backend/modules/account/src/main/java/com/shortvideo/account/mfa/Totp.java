package com.shortvideo.account.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 with the parameters every authenticator app supports: HMAC-SHA1,
 * six digits, a 30-second step. Other parameters are accepted by some apps and silently ignored by others,
 * which shows up as codes that never match.
 */
final class Totp {

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    static long stepAt(long epochSeconds) {
        return epochSeconds / STEP_SECONDS;
    }

    /** The code for one step, zero-padded to {@link #DIGITS}. */
    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % POWERS[DIGITS]);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is required for one-time codes", e);
        }
    }

    /**
     * Looks for {@code submitted} among the steps from one before {@code nowStep} to one after (a phone's
     * clock is rarely exact), skipping any step at or before {@code lastUsedStep} so a code that has been
     * accepted once cannot be accepted again.
     *
     * @return the step that matched, or -1 when none did
     */
    static long match(byte[] secret, String submitted, long nowStep, long lastUsedStep) {
        if (submitted == null || submitted.length() != DIGITS || !submitted.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        long matched = -1;
        // Every step is compared, in constant time, whether or not an earlier one matched.
        for (long step = nowStep - 1; step <= nowStep + 1; step++) {
            boolean equal = MessageDigest.isEqual(
                    code(secret, step).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    submitted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (equal && step > lastUsedStep && matched < 0) {
                matched = step;
            }
        }
        return matched;
    }

    /** The URI an authenticator app reads from a QR code or accepts as a link. */
    static String uri(String issuer, String accountLabel, byte[] secret) {
        String label = encode(issuer) + ":" + encode(accountLabel);
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret)
                + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}

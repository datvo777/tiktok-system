package com.shortvideo.account.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class MfaDtos {

    private MfaDtos() {}

    /** Sent instead of a session when the password was right and a code is still needed. */
    public record Challenge(boolean mfaRequired, String mfaToken, Instant expiresAt) {
        public static Challenge of(String mfaToken, Instant expiresAt) {
            return new Challenge(true, mfaToken, expiresAt);
        }
    }

    /** Exactly one of {@code code} and {@code recoveryCode}. */
    public record VerifyRequest(
            @NotBlank @Size(max = 2000) String mfaToken,
            @Size(max = 12) String code,
            @Size(max = 32) String recoveryCode) {}

    public record EnrollRequest(@NotBlank @Size(max = 200) String password) {}

    /** The secret is shown once, for typing into an app that cannot scan; the URI is what a QR code would carry. */
    public record EnrollResponse(String secret, String otpauthUri) {}

    public record ConfirmRequest(@NotBlank @Size(max = 12) String code) {}

    /** The recovery codes appear here and nowhere else, once. The new session is in the cookie. */
    public record ConfirmResponse(String accountId, String token, Instant expiresAt, List<String> recoveryCodes) {}

    public record Status(boolean enrolled, boolean signedInWithSecondFactor, boolean required) {}
}

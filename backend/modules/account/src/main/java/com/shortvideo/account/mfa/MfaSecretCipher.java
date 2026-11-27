package com.shortvideo.account.mfa;

import com.shortvideo.shared.security.MfaProperties;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals the TOTP secrets for storage (AES-256-GCM). Whoever can read the table but not the key learns nothing
 * that lets them produce a code. The application will not start with the factor enabled and no usable key.
 */
public class MfaSecretCipher {

    /** The key in the repository's local profile; refused everywhere else, like the published JWT secret. */
    static final String KNOWN_DEV_KEY = "bG9jYWwtZGV2LW1mYS1rZXktZG8tbm90LXVzZS14eXo=";

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String keyId;

    public MfaSecretCipher(String base64Key, String keyId, boolean allowInsecure) {
        this.key = parse(base64Key, allowInsecure);
        this.keyId = keyId;
    }

    static SecretKey parse(String base64Key, boolean allowInsecure) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "shortvideo.mfa.encryption-key is not set. Set MFA_ENCRYPTION_KEY to 32 random bytes, base64 "
                            + "(e.g. `openssl rand -base64 32`), or turn the second factor off (MFA_ENABLED=false).");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("shortvideo.mfa.encryption-key is not valid base64", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException(
                    "shortvideo.mfa.encryption-key must decode to exactly 32 bytes, got " + bytes.length);
        }
        if (KNOWN_DEV_KEY.equals(base64Key.trim()) && !allowInsecure) {
            throw new IllegalStateException(
                    "shortvideo.mfa.encryption-key is still the published development placeholder. Set "
                            + "MFA_ENCRYPTION_KEY (e.g. `openssl rand -base64 32`), or set "
                            + "shortvideo.jwt.allow-insecure-secret=true if this really is a local throwaway environment.");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public String keyId() {
        return keyId;
    }

    public byte[] seal(byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] out = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal the secret", e);
        }
    }

    /** @throws IllegalStateException when the key is wrong or the bytes were altered */
    public byte[] open(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            return cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Could not open the stored secret", e);
        }
    }
}

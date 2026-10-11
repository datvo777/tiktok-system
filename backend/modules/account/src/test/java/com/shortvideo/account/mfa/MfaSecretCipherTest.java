package com.shortvideo.account.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MfaSecretCipherTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void opensWhatItSealed() {
        MfaSecretCipher cipher = new MfaSecretCipher(randomKey(), "k1", false);
        byte[] secret = Totp.newSecret();

        assertThat(cipher.open(cipher.seal(secret))).isEqualTo(secret);
    }

    @Test
    void sealingTheSameSecretTwiceGivesDifferentBytes() {
        MfaSecretCipher cipher = new MfaSecretCipher(randomKey(), "k1", false);
        byte[] secret = Totp.newSecret();

        assertThat(cipher.seal(secret)).isNotEqualTo(cipher.seal(secret));
    }

    @Test
    void doesNotStoreTheSecretInTheClear() {
        MfaSecretCipher cipher = new MfaSecretCipher(randomKey(), "k1", false);
        byte[] secret = Totp.newSecret();
        byte[] sealed = cipher.seal(secret);

        // 12-byte nonce, the ciphertext, and a 16-byte tag.
        assertThat(sealed).hasSize(12 + secret.length + 16);
        assertThat(Arrays.equals(Arrays.copyOfRange(sealed, 12, 12 + secret.length), secret)).isFalse();
    }

    @Test
    void anotherKeyCannotOpenIt() {
        byte[] sealed = new MfaSecretCipher(randomKey(), "k1", false).seal(Totp.newSecret());

        assertThatThrownBy(() -> new MfaSecretCipher(randomKey(), "k1", false).open(sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void alteredBytesAreRefused() {
        MfaSecretCipher cipher = new MfaSecretCipher(randomKey(), "k1", false);
        byte[] sealed = cipher.seal(Totp.newSecret());
        sealed[sealed.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.open(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAUsableKey() {
        assertThatThrownBy(() -> new MfaSecretCipher(null, "k1", false)).hasMessageContaining("MFA_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new MfaSecretCipher("  ", "k1", false)).hasMessageContaining("MFA_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new MfaSecretCipher("not base64 !!", "k1", false)).hasMessageContaining("base64");
        assertThatThrownBy(() -> new MfaSecretCipher(Base64.getEncoder().encodeToString(new byte[16]), "k1", false))
                .hasMessageContaining("32 bytes");
    }

    @Test
    void thePublishedPlaceholderIsRefusedUnlessTheLocalSwitchIsOn() {
        assertThat(java.util.Base64.getDecoder().decode(MfaSecretCipher.KNOWN_DEV_KEY)).hasSize(32);
        assertThatThrownBy(() -> new MfaSecretCipher(MfaSecretCipher.KNOWN_DEV_KEY, "k1", false))
                .hasMessageContaining("placeholder");
        assertThat(new MfaSecretCipher(MfaSecretCipher.KNOWN_DEV_KEY, "k1", true).keyId()).isEqualTo("k1");
    }
}

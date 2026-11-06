package com.shortvideo.account.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Base32Test {

    @Test
    void matchesTheRfc4648Vectors() {
        assertThat(Base32.encode("".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(Base32.encode("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Base32.encode("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(Base32.encode("foo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6");
        assertThat(Base32.encode("foob".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YQ");
        assertThat(Base32.encode("fooba".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTB");
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void decodesWhatItEncodesForRandomBytes() {
        byte[] secret = Totp.newSecret();
        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
    }

    @Test
    void decodingIgnoresCaseSpacesAndPadding() {
        assertThat(Base32.decode("mzxw 6ytb oi==")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void refusesCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
    }
}

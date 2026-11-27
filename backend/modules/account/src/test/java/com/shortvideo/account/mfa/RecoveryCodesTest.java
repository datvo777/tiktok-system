package com.shortvideo.account.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

    @Test
    void generatesTenDistinctCodesOfTwoGroupsOfFive() {
        var codes = RecoveryCodes.generate();

        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allMatch(c -> c.matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}"));
    }

    @Test
    void caseDashesAndSpacesDoNotChangeTheHash() {
        assertThat(RecoveryCodes.hash("abcde-fghjk")).isEqualTo(RecoveryCodes.hash("ABCDEFGHJK"));
        assertThat(RecoveryCodes.hash(" abcde fghjk ")).isEqualTo(RecoveryCodes.hash("ABCDE-FGHJK"));
    }

    @Test
    void differentCodesHaveDifferentHashes() {
        assertThat(RecoveryCodes.hash("ABCDE-FGHJK")).isNotEqualTo(RecoveryCodes.hash("ABCDE-FGHJL"));
    }
}

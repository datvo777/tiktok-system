package com.shortvideo.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The handle rules. Worth testing directly because they are the platform's
 * defence against impersonation-by-lookalike, and every one of them is a
 * judgement call that a later change could quietly relax.
 */
class HandlesTest {

    @Test
    void canonicalisesToLowerCaseSoTwoAccountsCannotDifferOnlyByCase() {
        assertThat(Handles.normalise("DatVo")).isEqualTo("datvo");
    }

    @Test
    void acceptsTheSigilPeopleActuallyType() {
        assertThat(Handles.normalise("@datvo")).isEqualTo("datvo");
        assertThat(Handles.normalise("  @datvo  ")).isEqualTo("datvo");
    }

    @Test
    void acceptsLettersDigitsAndInteriorUnderscores() {
        assertThat(Handles.normalise("dat_vo_95")).isEqualTo("dat_vo_95");
    }

    /**
     * {@code @_dat} and {@code @dat_} read as {@code @dat} at a glance, which is
     * precisely the ambiguity an impersonation attempt wants.
     */
    @Test
    void rejectsLeadingAndTrailingUnderscores() {
        assertThatThrownBy(() -> Handles.normalise("_dat")).isInstanceOf(AccountExceptions.InvalidHandle.class);
        assertThatThrownBy(() -> Handles.normalise("dat_")).isInstanceOf(AccountExceptions.InvalidHandle.class);
    }

    @Test
    void rejectsCharactersThatCannotBeTypedFromMemory() {
        for (String bad : new String[] {"dat vo", "dat.vo", "dat-vo", "dät", "日本語", "dat@vo", "dat/vo"}) {
            assertThatThrownBy(() -> Handles.normalise(bad))
                    .as("should reject %s", bad)
                    .isInstanceOf(AccountExceptions.InvalidHandle.class);
        }
    }

    @Test
    void enforcesLengthBounds() {
        assertThatThrownBy(() -> Handles.normalise("ab")).isInstanceOf(AccountExceptions.InvalidHandle.class);
        assertThatThrownBy(() -> Handles.normalise("a".repeat(31)))
                .isInstanceOf(AccountExceptions.InvalidHandle.class);
        assertThat(Handles.normalise("a".repeat(30))).hasSize(30);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Handles.normalise(null)).isInstanceOf(AccountExceptions.InvalidHandle.class);
    }

    /** A URL or a label using one of these would be ambiguous with the platform's own. */
    @Test
    void refusesNamesThePlatformOwns() {
        for (String reserved : new String[] {"admin", "Support", "SETTINGS", "me", "official"}) {
            assertThatThrownBy(() -> Handles.normalise(reserved))
                    .as("should reserve %s", reserved)
                    .isInstanceOf(AccountExceptions.InvalidHandle.class);
        }
    }

    @Test
    void suggestsAUsableHandleFromADisplayName() {
        assertThat(Handles.suggestFrom("Dat Vo", "3f1a2b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b")).isEqualTo("datvo");
    }

    /** A display name that strips to nothing still has to yield something valid. */
    @Test
    void fallsBackToTheIdWhenADisplayNameStripsAway() {
        String suggested = Handles.suggestFrom("李雷", "3f1a2b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b");

        assertThat(suggested).isEqualTo("user3f1a2b");
        // Whatever it produces must itself be a legal handle.
        assertThat(Handles.normalise(suggested)).isEqualTo(suggested);
    }

    @Test
    void suggestionsAreAlwaysLegalHandles() {
        for (String name : new String[] {"__weird__", "a", "!!!", "Dat  Vo", "x".repeat(60)}) {
            String suggested = Handles.suggestFrom(name, "3f1a2b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b");
            assertThat(Handles.normalise(suggested)).as("suggestion for %s", name).isEqualTo(suggested);
        }
    }

    /** The suffix is what makes it unique, so the stem is the part that gets cut. */
    @Test
    void keepsTheSuffixWhenTrimmingToLength() {
        String result = Handles.withSuffix("a".repeat(30), 42);

        assertThat(result).hasSize(30).endsWith("42");
    }
}

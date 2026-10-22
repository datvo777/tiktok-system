package com.shortvideo.social.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MentionsTest {

    @Test
    void parsesAHandleAndLowerCasesIt() {
        assertThat(Mentions.parse("thanks @Dat for the help")).containsExactly("dat");
    }

    @Test
    void parsesSeveralDistinctHandlesInOneBody() {
        assertThat(Mentions.parse("cc @dat and @linh_99")).containsExactlyInAnyOrder("dat", "linh_99");
    }

    @Test
    void deduplicatesARepeatedMention() {
        assertThat(Mentions.parse("@dat @dat @dat")).containsExactly("dat");
    }

    @Test
    void doesNotMistakeAnEmailAddressForAMention() {
        assertThat(Mentions.parse("reach me at dat@example.com")).isEmpty();
    }

    @Test
    void ignoresATokenShorterThanTheMinimumHandleLength() {
        assertThat(Mentions.parse("hi @ab there")).isEmpty();
    }

    @Test
    void returnsNothingForBodyWithNoMention() {
        assertThat(Mentions.parse("just a regular comment")).isEmpty();
    }

    @Test
    void returnsNothingForNullBody() {
        assertThat(Mentions.parse(null)).isEmpty();
    }
}

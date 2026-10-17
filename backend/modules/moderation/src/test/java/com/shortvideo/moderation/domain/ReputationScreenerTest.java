package com.shortvideo.moderation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.moderation.domain.ModerationScreener.ScreeningSubject;
import org.junit.jupiter.api.Test;

/**
 * The automated pre-screen.
 *
 * <p>Most of these assert that something is <em>referred to a human</em>, which
 * is the boring outcome — and that is the point. The screener's entire safety
 * argument is that every signal can only push towards review, so the tests worth
 * having are the ones that pin the narrow path out of it.
 */
class ReputationScreenerTest {

    private final ModerationProperties properties = new ModerationProperties();
    private final ReputationScreener screener = new ReputationScreener(properties);

    private ScreeningSubject upload(String title, long approved, long rejected) {
        return new ScreeningSubject("v1", "c1", title, null, approved, rejected);
    }

    @Test
    void anEstablishedCreatorPostingSomethingUnremarkableSkipsTheQueue() {
        assertThat(screener.screen(upload("morning coffee", 12, 0))).isEqualTo(ScreeningVerdict.AUTO_APPROVE);
    }

    @Test
    void aBrandNewCreatorAlwaysGoesToAHuman() {
        assertThat(screener.screen(upload("morning coffee", 0, 0))).isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
    }

    @Test
    void trustBeginsExactlyAtTheConfiguredThreshold() {
        int threshold = properties.getTrustedAfterApprovals();

        assertThat(screener.screen(upload("hello", threshold - 1, 0))).isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
        assertThat(screener.screen(upload("hello", threshold, 0))).isEqualTo(ScreeningVerdict.AUTO_APPROVE);
    }

    /**
     * Not a ratio. Someone whose work a moderator has refused is precisely the
     * person whose next upload is worth looking at, and "99 good, 1 bad" is not
     * reassuring when the 1 is the reason the policy exists.
     */
    @Test
    void oneRejectionEndsAutomaticApprovalHoweverLongTheGoodRecord() {
        assertThat(screener.screen(upload("hello", 500, 1))).isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
    }

    /** The categories where a false negative is least acceptable are checked first. */
    @Test
    void reputationNeverOverridesTheAlwaysReviewTerms() {
        for (String title : new String[] {
            "underage sex tape",
            "how to kill everyone",
            "suicide compilation",
            "free money crypto giveaway",
            "buy fentanyl here"
        }) {
            assertThat(screener.screen(upload(title, 9999, 0)))
                    .as("should always review: %s", title)
                    .isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
        }
    }

    @Test
    void isNotFooledByCasing() {
        assertThat(screener.screen(upload("FREE MONEY Crypto Giveaway", 9999, 0)))
                .isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
    }

    @Test
    void aCaptionFullOfLinksReadsAsPromotion() {
        ScreeningSubject spam = new ScreeningSubject(
                "v1", "c1", "check these", "https://a.com and https://b.net and www.c.org", 50, 0);

        assertThat(screener.screen(spam)).isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
    }

    @Test
    void oneLinkIsFine() {
        ScreeningSubject ok = new ScreeningSubject("v1", "c1", "my site", "https://example.com", 50, 0);

        assertThat(screener.screen(ok)).isEqualTo(ScreeningVerdict.AUTO_APPROVE);
    }

    /** A video whose metadata row has not landed yet must not crash the screen. */
    @Test
    void toleratesMissingMetadata() {
        assertThat(screener.screen(new ScreeningSubject("v1", "c1", null, null, 50, 0)))
                .isEqualTo(ScreeningVerdict.AUTO_APPROVE);
        assertThat(screener.screen(new ScreeningSubject("v1", "c1", null, null, 0, 0)))
                .isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
    }

    /** There is no input that takes a video down; the worst outcome is human review. */
    @Test
    void neverProducesAnythingStrongerThanAReferral() {
        for (ScreeningVerdict verdict : ScreeningVerdict.values()) {
            assertThat(verdict).isIn(ScreeningVerdict.AUTO_APPROVE, ScreeningVerdict.REFER_TO_HUMAN);
        }
    }
}

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
    void aBrandNewCreatorWithNothingWrongIsHeldNotReferred() {
        assertThat(screener.screen(upload("morning coffee", 0, 0))).isEqualTo(ScreeningVerdict.HOLD_UNTIL_PUBLISH);
    }

    @Test
    void trustBeginsExactlyAtTheConfiguredThreshold() {
        int threshold = properties.getTrustedAfterApprovals();

        assertThat(screener.screen(upload("hello", threshold - 1, 0))).isEqualTo(ScreeningVerdict.HOLD_UNTIL_PUBLISH);
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

    /** The English list was the only list, so a Vietnamese caption never matched anything. */
    @Test
    void vietnameseTermsAreReviewedHoweverTrustedTheCreator() {
        for (String title : new String[] {
            "tôi muốn tự tử",
            "TỰ SÁT",
            "giết hết mọi người",
            "bán súng giá rẻ",
            "đặt mua ma túy",
            "nữ sinh khiêu dâm"
        }) {
            assertThat(screener.screen(upload(title, 9999, 0)))
                    .as("should always review: %s", title)
                    .isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
        }
    }

    /** Typed without accents, as plenty of people do; only terms that stay unambiguous without them. */
    @Test
    void unambiguousVietnameseTermsAreCaughtWithoutAccents() {
        for (String title : new String[] {"toi muon tu sat", "giet het moi nguoi", "ma tuy da", "thuoc lac"}) {
            assertThat(screener.screen(upload(title, 9999, 0)))
                    .as("should always review: %s", title)
                    .isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
        }
    }

    /** Stripping accents turns "từ từ" into "tu tu" and "bạn" into "ban"; ordinary captions must not pay for that. */
    @Test
    void everydayVietnameseIsNotMistakenForARiskyTerm() {
        for (String title : new String[] {"đi từ từ thôi", "chúc bạn một ngày vui", "mua sắm cuối tuần", "bạn sung sướng"}) {
            assertThat(screener.screen(upload(title, 9999, 0)))
                    .as("should not be flagged: %s", title)
                    .isEqualTo(ScreeningVerdict.AUTO_APPROVE);
        }
    }

    @Test
    void aKeywordSpelledToDodgeTheFilterIsStillCaught() {
        for (String title : new String[] {
            "k1ll everyone",
            "$uicide compilation",
            "s.u.i.c.i.d.e",
            "s u i c i d e",
            "suiсide", // the "с" is Cyrillic
            "ｓｕｉｃｉｄｅ" // fullwidth
        }) {
            assertThat(screener.screen(upload(title, 9999, 0)))
                    .as("should always review: %s", title)
                    .isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
        }
    }

    /** Folding is for finding terms only; it must not turn plain captions into matches. */
    @Test
    void ordinaryTextWithDigitsAndInitialsIsLeftAlone() {
        for (String title : new String[] {"my 5am routine", "top 10 views of 2024", "meet at U.S.A. border", "a b c"}) {
            assertThat(screener.screen(upload(title, 9999, 0)))
                    .as("should not be flagged: %s", title)
                    .isEqualTo(ScreeningVerdict.AUTO_APPROVE);
        }
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
                .isEqualTo(ScreeningVerdict.HOLD_UNTIL_PUBLISH);
    }

    /** A risk signal outranks the lack of a track record: a new creator's flagged upload is not merely held. */
    @Test
    void riskOutranksHolding() {
        assertThat(screener.screen(upload("free money crypto giveaway", 0, 0)))
                .isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
        assertThat(screener.screen(upload("hello", 0, 1))).isEqualTo(ScreeningVerdict.REFER_TO_HUMAN);
    }

    /** There is no input that takes a video down; the worst outcome is human review. */
    @Test
    void neverProducesAnythingStrongerThanAReferral() {
        for (ScreeningVerdict verdict : ScreeningVerdict.values()) {
            assertThat(verdict)
                    .isIn(
                            ScreeningVerdict.AUTO_APPROVE,
                            ScreeningVerdict.REFER_TO_HUMAN,
                            ScreeningVerdict.HOLD_UNTIL_PUBLISH);
        }
    }
}

package com.shortvideo.moderation.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The screener this platform ships with.
 *
 * <p><b>Be clear about what this does not do.</b> It never looks at a single
 * frame of video. It cannot tell whether a clip contains nudity, violence or a
 * dangerous act, and nothing here should be read as claiming otherwise. What it
 * has are two weak signals — the text the uploader typed, and what humans have
 * previously decided about this uploader — and it uses them only to answer one
 * narrow question: <em>is this upload boring enough to let through without a
 * person?</em>
 *
 * <p>That question is worth answering because the alternative is what the
 * platform did before: every upload, from every creator, waiting on a human.
 * A creator with a long clean record posting an unremarkable title is the
 * overwhelming majority of uploads, and sending all of them to the same queue as
 * genuinely uncertain content is what makes the queue too slow to be useful.
 *
 * <p><b>The asymmetry is deliberate.</b> Every signal here can only push
 * <em>towards</em> human review, never away from it. There is no combination of
 * inputs that takes a video down, and no threshold that can be tuned into one.
 * The failure mode is a person having to look at something unnecessarily.
 *
 * <p>Replacing this with a real classifier means implementing
 * {@link ModerationScreener} and nothing else.
 */
@Component
class ReputationScreener implements ModerationScreener {

    /**
     * How many human approvals a creator needs before their uploads stop going to
     * the queue by default.
     *
     * <p>Five is a judgement call, not a measurement. It is high enough that
     * registering an account and immediately posting does not buy a bypass, and
     * low enough that a genuine creator stops waiting after their first week.
     */
    private final int trustedAfterApprovals;

    /**
     * A single human rejection ends automatic approval for that creator,
     * permanently. Not a ratio: someone who has posted something a moderator
     * refused is exactly the person whose next upload is worth a look, and
     * "9 good, 1 bad" is not reassuring when the 1 is why the policy exists.
     */
    private final boolean anyRejectionRevokesTrust = true;

    ReputationScreener(ModerationProperties properties) {
        this.trustedAfterApprovals = properties.getTrustedAfterApprovals();
    }

    /**
     * Terms that make an upload worth a human's time regardless of who posted it.
     *
     * <p>Not a content filter and not an accusation — matching one of these only
     * cancels the fast path. The list covers the categories where a false
     * negative is least acceptable, so the reputation signal is not allowed to
     * override it.
     */
    private static final List<Pattern> ALWAYS_REVIEW = List.of(
            Pattern.compile("\\b(child|minor|underage|teen)\\b.{0,20}\\b(sex|nude|naked|porn)\\b"),
            Pattern.compile("\\b(kill|shoot|bomb|behead|murder)\\b.{0,20}\\b(you|them|him|her|everyone)\\b"),
            Pattern.compile("\\b(suicide|self.?harm|kys)\\b"),
            Pattern.compile("\\b(onlyfans|escort|cashapp|crypto.?giveaway|free.?money)\\b"),
            Pattern.compile("\\b(buy|sell|order)\\b.{0,15}\\b(gun|weapon|xanax|oxy|fentanyl|cocaine)\\b"));

    /**
     * More than one link reads as promotion rather than as a caption.
     *
     * <p>One alternation over the whole URL, not one per part. Written as
     * {@code https?://|www\.|host\.tld} it matched {@code https://example.com}
     * twice — scheme and host — so a single link counted as two and every caption
     * containing a URL was referred.
     */
    private static final Pattern LINK =
            Pattern.compile("(?:https?://|www\\.)?[a-z0-9][a-z0-9-]*\\.(?:com|net|org|io|ru|xyz)\\b");
    private static final int MAX_LINKS = 1;

    @Override
    public ScreeningVerdict screen(ScreeningSubject subject) {
        String text = ((subject.title() == null ? "" : subject.title()) + " "
                        + (subject.description() == null ? "" : subject.description()))
                .toLowerCase(Locale.ROOT);

        // Checked first and unconditionally: no amount of good standing skips it.
        for (Pattern pattern : ALWAYS_REVIEW) {
            if (pattern.matcher(text).find()) {
                return ScreeningVerdict.REFER_TO_HUMAN;
            }
        }
        if (countLinks(text) > MAX_LINKS) {
            return ScreeningVerdict.REFER_TO_HUMAN;
        }

        if (anyRejectionRevokesTrust && subject.rejectedVideos() > 0) {
            return ScreeningVerdict.REFER_TO_HUMAN;
        }
        if (subject.approvedVideos() < trustedAfterApprovals) {
            return ScreeningVerdict.REFER_TO_HUMAN;
        }
        return ScreeningVerdict.AUTO_APPROVE;
    }

    private static int countLinks(String text) {
        var matcher = LINK.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}

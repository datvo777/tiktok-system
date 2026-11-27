package com.shortvideo.moderation.domain;

import java.text.Normalizer;
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
 * person?</em> Anything it cannot vouch for but has no complaint about is held for the
 * owner's publish request rather than queued.
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
     * How many approvals a creator needs before their uploads stop going to
     * the queue by default.
     *
     * <p>Five is a judgement call, not a measurement. It is high enough that
     * registering an account and immediately posting does not buy a bypass, and
     * low enough that a genuine creator stops waiting after their first week.
     *
     * <p>The count includes videos the system approved, not just ones a person did.
     * That cannot open the gate on its own: nothing is auto-approved until the
     * threshold is reached, so the first {@code N} approvals are all human.
     */
    private final int trustedAfterApprovals;

    /**
     * A single standing rejection ends automatic approval for that creator. Not a ratio:
     * someone who has posted something a moderator refused is exactly the person whose next
     * upload is worth a look, and "9 good, 1 bad" is not reassuring when the 1 is why the policy
     * exists.
     *
     * <p>"Standing" is literal: the count is of videos currently {@code REJECTED}. A rejection
     * that was overturned (an approved appeal, or an admin reinstating the video) no longer
     * counts, because the decision was reversed; and a takedown that is not a rejection, such as
     * {@code remove}, never counted, since the owner deleting their own video takes the same path.
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

    /** Not a word boundary escape: its behaviour around non-ASCII letters has changed between JDK releases. */
    private static final String START = "(?<![\\p{L}\\p{N}])";
    private static final String END = "(?![\\p{L}\\p{N}])";

    /**
     * The same categories in Vietnamese, matched against the text with its diacritics intact.
     *
     * <p>Kept separate from the accent-stripped list because stripping makes ordinary words collide:
     * "từ từ" (slowly) becomes "tu tu", and "bạn" (you) becomes "ban" (sell). Only terms that stay
     * unambiguous without their marks go in {@link #VIETNAMESE_FOLDED}.
     */
    private static final List<Pattern> VIETNAMESE = List.of(
            Pattern.compile(START + "(tự tử|tự vẫn|tự sát)" + END),
            Pattern.compile(START + "(giết|sát hại|hãm hại|bóp cổ)" + END + ".{0,20}" + START
                    + "(mày|mi|nó|họ|chúng nó|người|mọi người|tất cả)" + END),
            Pattern.compile(START + "(bán|mua|đặt mua)" + END + ".{0,15}" + START
                    + "(súng|ma túy|thuốc lắc|heroin|cocaine|fentanyl|thuốc phiện)" + END),
            Pattern.compile(START + "(trẻ em|trẻ con|chưa thành niên|nữ sinh|nam sinh)" + END + ".{0,20}" + START
                    + "(sex|khiêu dâm|khỏa thân|porn)" + END));

    /** Vietnamese terms that remain unambiguous once typed without diacritics, as many people do. */
    private static final List<Pattern> VIETNAMESE_FOLDED = List.of(
            Pattern.compile(START + "(tu sat|tu van|giet nguoi|giet het|khieu dam|ma tuy|thuoc lac)" + END));

    /** Letters from other scripts that read as Latin ones, which diacritic stripping does not fold. */
    private static final String LOOKALIKES_FROM = "аеорсхуі";
    private static final String LOOKALIKES_TO = "aeopcxyi";

    /** {@code k1ll}, {@code $uicide}: digits and symbols standing in for letters. */
    private static final String LEET_FROM = "013457@$";
    private static final String LEET_TO = "oieastas";

    /** Four or more single letters split by punctuation or spaces, as in {@code s.u.i.c.i.d.e}. */
    private static final Pattern SPELLED_OUT = Pattern.compile("(?<![a-z])[a-z](?:[.\\-_* ]+[a-z]){3,}(?![a-z])");

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
        String text = Normalizer.normalize(
                ((subject.title() == null ? "" : subject.title()) + " "
                                + (subject.description() == null ? "" : subject.description()))
                        .toLowerCase(Locale.ROOT),
                Normalizer.Form.NFC);
        String folded = fold(text);

        // Checked first and unconditionally: no amount of good standing skips it. Matching the folded
        // text as well as the original is what keeps a keyword from being dodged by spelling it oddly.
        if (matchesAny(ALWAYS_REVIEW, text) || matchesAny(ALWAYS_REVIEW, folded)
                || matchesAny(VIETNAMESE, text) || matchesAny(VIETNAMESE_FOLDED, folded)) {
            return ScreeningVerdict.REFER_TO_HUMAN;
        }
        if (countLinks(text) > MAX_LINKS) {
            return ScreeningVerdict.REFER_TO_HUMAN;
        }

        if (anyRejectionRevokesTrust && subject.rejectedVideos() > 0) {
            return ScreeningVerdict.REFER_TO_HUMAN;
        }
        // No bad signal, only no track record: it needs a person before it is published, not before it is uploaded.
        if (subject.approvedVideos() < trustedAfterApprovals) {
            return ScreeningVerdict.HOLD_UNTIL_PUBLISH;
        }
        return ScreeningVerdict.AUTO_APPROVE;
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The text as a keyword filter should see it: accents removed, look-alike letters and leetspeak
     * mapped back to Latin letters, and spelled-out words joined. Only ever used to look for terms, so
     * a false match costs a person a look and nothing more; links are counted on the original text.
     */
    static String fold(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : Normalizer.normalize(text, Normalizer.Form.NFKD).toCharArray()) {
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            int look = LOOKALIKES_FROM.indexOf(c);
            int leet = LEET_FROM.indexOf(c);
            sb.append(c == 'đ' ? 'd' : look >= 0 ? LOOKALIKES_TO.charAt(look) : leet >= 0 ? LEET_TO.charAt(leet) : c);
        }
        return SPELLED_OUT.matcher(sb).replaceAll(m -> m.group().replaceAll("[^a-z]", ""));
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

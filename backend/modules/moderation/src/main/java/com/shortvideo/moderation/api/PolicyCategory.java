package com.shortvideo.moderation.api;

/**
 * Why a video was rejected (brief section 18). Persisted as a string, never an
 * ordinal, in line with every other enum here.
 *
 * <p>This replaces free-text as the authoritative reason. A {@code reason}
 * string still rides alongside, but only as elaboration — it is not what
 * anything counts, routes or measures on. Structure is the whole point: a
 * free-text column cannot answer "which policies do reviewers disagree about",
 * "what is the reversal rate for minor-safety calls", or "should this bypass
 * the queue", and those are the questions moderation is actually run on.
 *
 * <p>The tier is severity, and it is deliberately attached to the category
 * rather than chosen separately by the reviewer: severity is a property of the
 * policy, not a judgement call at decision time. It is what a future priority
 * scorer sorts on, and what decides whether a rejection also costs the account.
 *
 * <p>{@code UNCLASSIFIED} exists only for rows that predate this enum. Nothing
 * should write it going forward.
 */
public enum PolicyCategory {
    MINOR_SAFETY("P0"),
    SELF_HARM("P0"),
    VIOLENCE_GORE("P1"),
    ADULT_CONTENT("P1"),
    HARASSMENT("P1"),
    SPAM_DECEPTIVE("P2"),
    IP_VIOLATION("P2"),
    UNCLASSIFIED("P3");

    private final String tier;

    PolicyCategory(String tier) {
        this.tier = tier;
    }

    /** P0 is the most severe. Used for queue priority and account consequences. */
    public String tier() {
        return tier;
    }
}

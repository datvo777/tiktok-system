package com.shortvideo.report.api;

/**
 * The closed set of reasons a viewer may pick.
 *
 * <p>A fixed vocabulary rather than free text, for three reasons: a moderator
 * can triage a queue grouped by reason where they cannot triage a thousand
 * sentences, the categories map onto policy rather than onto mood, and a
 * required free-text field is how you end up storing abuse aimed at the person
 * being reported. Detail is still accepted alongside, but optional.
 */
public enum ReportReason {
    SEXUAL_CONTENT,
    VIOLENCE_OR_GORE,
    HATE_OR_HARASSMENT,
    DANGEROUS_ACTS,
    MISINFORMATION,
    SPAM_OR_SCAM,
    INTELLECTUAL_PROPERTY,
    CHILD_SAFETY,
    OTHER
}

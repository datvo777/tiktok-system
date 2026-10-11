package com.shortvideo.report.api;

/**
 * How a moderator closed a report. Recorded separately from the moderation
 * decision itself, because a report can be dismissed without the video being
 * touched, and a video can be actioned for reasons no report raised.
 */
public enum ReportResolution {
    /** The report was accurate and the subject was actioned. */
    ACTIONED,
    /** Reviewed and found not to breach policy. */
    DISMISSED,
    /** Filed in bad faith or in bulk. */
    ABUSIVE_REPORT
}

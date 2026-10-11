package com.shortvideo.report.api;

/**
 * OPEN until a moderator closes it. There is deliberately no "in progress"
 * state: with one moderator queue and no assignment, a third state would record
 * an intention nothing enforces.
 */
public enum ReportState {
    OPEN,
    RESOLVED
}

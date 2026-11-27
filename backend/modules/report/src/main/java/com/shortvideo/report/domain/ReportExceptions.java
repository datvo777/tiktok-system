package com.shortvideo.report.domain;

public final class ReportExceptions {

    /** The video, account or comment being reported does not exist or is not visible to the reporter. */
    public static class SubjectNotFound extends RuntimeException {
        public SubjectNotFound(String message) { super(message); }
    }

    public static class ReportNotFound extends RuntimeException {
        public ReportNotFound(String message) { super(message); }
    }

    /** Reporting your own content is not a moderation signal, it is a mistake. */
    public static class CannotReportSelf extends RuntimeException {
        public CannotReportSelf(String message) { super(message); }
    }

    private ReportExceptions() {}
}

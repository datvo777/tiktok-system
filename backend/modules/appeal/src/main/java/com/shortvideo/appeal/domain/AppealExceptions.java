package com.shortvideo.appeal.domain;

public final class AppealExceptions {

    public static class AppealNotFound extends RuntimeException {
        public AppealNotFound(String message) { super(message); }
    }

    public static class NotEligibleForAppeal extends RuntimeException {
        public NotEligibleForAppeal(String message) { super(message); }
    }

    public static class AppealNotPending extends RuntimeException {
        public AppealNotPending(String message) { super(message); }
    }

    /** A denied appeal was resubmitted before its cooldown ended; answered 429 with Retry-After. */
    public static class ResubmitTooSoon extends RuntimeException {
        private final java.time.Duration retryAfter;

        public ResubmitTooSoon(java.time.Duration retryAfter) {
            super("Too soon to appeal again");
            this.retryAfter = retryAfter;
        }

        public java.time.Duration retryAfter() { return retryAfter; }
    }

    private AppealExceptions() {}
}

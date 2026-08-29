package com.shortvideo.appeal.domain;

public final class AppealExceptions {

    public static class AppealNotFound extends RuntimeException {
        public AppealNotFound(String message) { super(message); }
    }

    public static class NotVideoOwner extends RuntimeException {
        public NotVideoOwner(String message) { super(message); }
    }

    public static class NotEligibleForAppeal extends RuntimeException {
        public NotEligibleForAppeal(String message) { super(message); }
    }

    public static class AppealNotPending extends RuntimeException {
        public AppealNotPending(String message) { super(message); }
    }

    private AppealExceptions() {}
}

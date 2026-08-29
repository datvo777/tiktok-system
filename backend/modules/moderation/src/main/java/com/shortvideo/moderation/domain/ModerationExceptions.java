package com.shortvideo.moderation.domain;

public final class ModerationExceptions {

    public static class ModerationRecordNotFound extends RuntimeException {
        public ModerationRecordNotFound(String message) { super(message); }
    }

    private ModerationExceptions() {}
}

package com.shortvideo.social.domain;

public final class SocialExceptions {

    public static class VideoNotEligible extends RuntimeException {
        public VideoNotEligible(String message) { super(message); }
    }

    public static class CreatorNotFound extends RuntimeException {
        public CreatorNotFound(String message) { super(message); }
    }

    public static class CannotFollowSelf extends RuntimeException {
        public CannotFollowSelf(String message) { super(message); }
    }

    public static class CommentNotFound extends RuntimeException {
        public CommentNotFound(String message) { super(message); }
    }

    private SocialExceptions() {}
}

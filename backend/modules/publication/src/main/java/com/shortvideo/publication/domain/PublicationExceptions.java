package com.shortvideo.publication.domain;

public final class PublicationExceptions {

    public static class PublicationNotFound extends RuntimeException {
        public PublicationNotFound(String message) { super(message); }
    }

    public static class NotVideoOwner extends RuntimeException {
        public NotVideoOwner(String message) { super(message); }
    }

    private PublicationExceptions() {}
}

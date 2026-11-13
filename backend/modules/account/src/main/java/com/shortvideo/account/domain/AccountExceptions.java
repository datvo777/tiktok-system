package com.shortvideo.account.domain;

public final class AccountExceptions {

    public static class EmailAlreadyRegistered extends RuntimeException {
        public EmailAlreadyRegistered(String message) { super(message); }
    }

    public static class InvalidCredentials extends RuntimeException {
        public InvalidCredentials(String message) { super(message); }
    }

    /** The handle is malformed, reserved, or already taken. */
    public static class InvalidHandle extends RuntimeException {
        public InvalidHandle(String message) { super(message); }
    }

    /** A profile field that cannot be stored as given. */
    public static class InvalidProfile extends RuntimeException {
        public InvalidProfile(String message) { super(message); }
    }

    public static class AccountNotFound extends RuntimeException {
        public AccountNotFound(String message) { super(message); }
    }

    public static class AccountNotActive extends RuntimeException {
        public AccountNotActive(String message) { super(message); }
    }

    private AccountExceptions() {}
}

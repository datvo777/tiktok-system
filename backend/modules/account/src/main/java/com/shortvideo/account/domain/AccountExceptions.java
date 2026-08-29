package com.shortvideo.account.domain;

public final class AccountExceptions {

    public static class EmailAlreadyRegistered extends RuntimeException {
        public EmailAlreadyRegistered(String message) { super(message); }
    }

    public static class InvalidCredentials extends RuntimeException {
        public InvalidCredentials(String message) { super(message); }
    }

    public static class AccountNotFound extends RuntimeException {
        public AccountNotFound(String message) { super(message); }
    }

    public static class AccountNotActive extends RuntimeException {
        public AccountNotActive(String message) { super(message); }
    }

    private AccountExceptions() {}
}

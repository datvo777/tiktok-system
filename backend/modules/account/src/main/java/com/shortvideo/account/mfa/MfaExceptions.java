package com.shortvideo.account.mfa;

/** What can go wrong at the second step. The web layer turns each into a status and a stable `code`. */
public final class MfaExceptions {

    private MfaExceptions() {}

    /** A wrong or already-used code. Worded the same for both, so a response does not say which. */
    public static class InvalidCode extends RuntimeException {
        public InvalidCode() { super("That code is not valid"); }
    }

    /** The pending sign-in is expired, spent, malformed or for an account that can no longer sign in. */
    public static class PendingSignInInvalid extends RuntimeException {
        public PendingSignInInvalid(String message) { super(message); }
    }

    /** Re-entering the password to start enrolment failed. Not a 401: the session itself is fine. */
    public static class PasswordIncorrect extends RuntimeException {
        public PasswordIncorrect() { super("The password is not correct"); }
    }

    public static class AlreadyEnrolled extends RuntimeException {
        public AlreadyEnrolled() { super("A second factor is already set up for this account"); }
    }

    public static class NoPendingEnrollment extends RuntimeException {
        public NoPendingEnrollment() { super("No enrolment is in progress; start one first"); }
    }
}

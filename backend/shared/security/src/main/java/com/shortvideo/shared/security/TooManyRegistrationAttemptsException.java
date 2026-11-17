package com.shortvideo.shared.security;

import java.time.Duration;

/**
 * Raised before the password is hashed, so a throttled attempt costs no
 * BCrypt. Answered as 429 with Retry-After.
 */
public class TooManyRegistrationAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRegistrationAttemptsException(Duration retryAfter) {
        super("Too many registration attempts");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

package com.shortvideo.shared.security;

import java.time.Duration;

/**
 * Raised before any password verification happens, so a throttled attempt costs
 * no BCrypt. Answered as 429 with Retry-After.
 */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super("Too many login attempts");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

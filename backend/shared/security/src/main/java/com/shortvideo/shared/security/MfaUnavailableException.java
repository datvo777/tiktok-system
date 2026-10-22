package com.shortvideo.shared.security;

import java.time.Duration;

/**
 * The attempt counters behind the second step could not be read or written. Unlike the login limiter, which
 * lets the attempt through when Redis is down, this refuses it: with no count, a six-digit code could be
 * tried without limit, and the password is no longer in the way at this step.
 */
public class MfaUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public MfaUnavailableException(Duration retryAfter, Throwable cause) {
        super("Second-factor attempt state is unavailable", cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

package com.shortvideo.search.domain;

/**
 * The search backend could not be reached. Distinct from a failure of this
 * service, and answered as 503 so a client knows to retry rather than treating it
 * as a permanent error.
 */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

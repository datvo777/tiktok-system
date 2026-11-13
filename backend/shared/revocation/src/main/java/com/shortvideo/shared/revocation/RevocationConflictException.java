package com.shortvideo.shared.revocation;

/**
 * A clear was refused because the block still in force is not the one the caller expected to lift.
 * An {@link IllegalStateException} so existing callers and redelivery handling see no change, but a
 * type of its own so the web layer can answer 409 instead of a 500 with no explanation.
 */
public class RevocationConflictException extends IllegalStateException {

    public RevocationConflictException(String message) {
        super(message);
    }
}

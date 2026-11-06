package com.shortvideo.worker;

/**
 * {@code failureClass} is {@code TERMINAL} (malformed/unsupported input) or {@code TRANSIENT}
 * (worker/MinIO failure). {@code reason} is optional detail the Video module uses to decide how many
 * times to retry a TRANSIENT failure; see {@code MediaEvents.MediaResultCommand}.
 */
class TranscodeFailedException extends RuntimeException {

    private final String failureClass;
    private final String reason;

    TranscodeFailedException(String failureClass, String message) {
        this(failureClass, message, null);
    }

    TranscodeFailedException(String failureClass, String message, String reason) {
        super(message);
        this.failureClass = failureClass;
        this.reason = reason;
    }

    String failureClass() {
        return failureClass;
    }

    /** {@code TIMEOUT} when the job ran out of time, otherwise null. */
    String reason() {
        return reason;
    }
}

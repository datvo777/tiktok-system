package com.shortvideo.worker;

/** {@code failureClass} is {@code TERMINAL} (malformed/unsupported input) or {@code TRANSIENT} (worker/MinIO failure). */
class TranscodeFailedException extends RuntimeException {

    private final String failureClass;

    TranscodeFailedException(String failureClass, String message) {
        super(message);
        this.failureClass = failureClass;
    }

    String failureClass() {
        return failureClass;
    }
}

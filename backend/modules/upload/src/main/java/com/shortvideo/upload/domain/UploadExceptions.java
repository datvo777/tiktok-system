package com.shortvideo.upload.domain;

public final class UploadExceptions {

    public static class UploadNotFound extends RuntimeException {
        public UploadNotFound(String message) { super(message); }
    }

    public static class NotUploadOwner extends RuntimeException {
        public NotUploadOwner(String message) { super(message); }
    }

    public static class UploadExpired extends RuntimeException {
        public UploadExpired(String message) { super(message); }
    }

    public static class UploadObjectMissing extends RuntimeException {
        public UploadObjectMissing(String message) { super(message); }
    }

    public static class UploadSizeOutOfRange extends RuntimeException {
        public UploadSizeOutOfRange(String message) { super(message); }
    }

    /**
     * One account is holding open more unfinished upload sessions than the cap
     * allows. Each open session carries a presigned policy the holder can still
     * write against, so an unbounded number of them is an unbounded write
     * allowance regardless of the per-object size cap.
     */
    public static class TooManyOpenUploads extends RuntimeException {
        public TooManyOpenUploads(String message) { super(message); }
    }

    /** The Idempotency-Key names a session that can no longer be handed back (completed or expired). */
    public static class IdempotencyKeyConflict extends RuntimeException {
        public IdempotencyKeyConflict(String message) { super(message); }
    }

    /** Could not take this account's upload lock in time; the caller should retry. */
    public static class UploadBusy extends RuntimeException {
        public UploadBusy(String message, Throwable cause) { super(message, cause); }
    }

    /** Only ACTIVE accounts may open an upload; anything else, or an unknown account, is refused (Rule 9). */
    public static class AccountNotAllowedToUpload extends RuntimeException {
        public AccountNotAllowedToUpload(String message) { super(message); }
    }

    private UploadExceptions() {}
}

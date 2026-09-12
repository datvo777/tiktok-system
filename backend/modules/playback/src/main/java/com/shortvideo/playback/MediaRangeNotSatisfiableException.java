package com.shortvideo.playback;

/**
 * A syntactically valid Range that cannot be met by this object (RFC 9110
 * §15.5.17). Carries the true size so the 416 response can include the
 * {@code Content-Range: bytes *​/size} header a client needs to retry correctly.
 */
public class MediaRangeNotSatisfiableException extends RuntimeException {

    private final long totalSize;

    public MediaRangeNotSatisfiableException(long totalSize) {
        super("Requested range is not satisfiable");
        this.totalSize = totalSize;
    }

    public long totalSize() {
        return totalSize;
    }
}

package com.shortvideo.upload.api;

import java.time.Instant;
import java.util.List;

/** Read-only view of completed uploads, for reconciling what should have followed each one. */
public interface UploadDirectory {

    /** Sorts before every real upload id. */
    String FIRST = "00000000-0000-0000-0000-000000000000";

    /**
     * Uploads completed within {@code [from, to]}, in ascending {@code uploadId} order, starting
     * after {@code afterUploadId} (pass {@link #FIRST} for the first page). Keyed on the id rather
     * than the completion time so a page boundary can never fall between two rows that share a
     * timestamp.
     */
    List<CompletedUploadView> completedBetween(Instant from, Instant to, String afterUploadId, int limit);

    record CompletedUploadView(
            String uploadId, String videoId, String accountId, String sourceObjectKey, Instant completedAt) {}
}

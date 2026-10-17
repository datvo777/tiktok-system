package com.shortvideo.moderation.domain;

/**
 * The automated first pass over a new upload.
 *
 * <p>Moderation was entirely manual: every upload sat in {@code PUBLISH_PENDING}
 * until an administrator clicked approve, so time-to-publish was bounded by how
 * fast a person was watching the queue — and the uploader's own UI had to promise
 * "usually a few minutes" with no mechanism behind it.
 *
 * <p>This interface is the seam where that changes. It exists as an interface
 * rather than a method because the implementation this ships with is deliberately
 * modest, and replacing it with something that actually inspects the video is the
 * expected next step rather than a rewrite.
 */
public interface ModerationScreener {

    ScreeningVerdict screen(ScreeningSubject subject);

    /**
     * Everything a screener is given. Deliberately a record rather than a video
     * id: an implementation that has to go and fetch its own inputs is one that
     * cannot be unit-tested without a database.
     *
     * @param title nullable — a video may have no metadata row yet.
     * @param approvedVideos how many of this creator's videos a human has
     *     approved.
     * @param rejectedVideos how many a human has rejected.
     */
    record ScreeningSubject(
            String videoId,
            String creatorId,
            String title,
            String description,
            long approvedVideos,
            long rejectedVideos) {}
}

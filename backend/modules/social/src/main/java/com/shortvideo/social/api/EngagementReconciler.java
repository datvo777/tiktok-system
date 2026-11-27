package com.shortvideo.social.api;

import java.time.Duration;

/**
 * Brings the maintained like and comment totals back in line with the source tables.
 *
 * <p>The totals are fed by events, which can be lost (an instance still on code that does not
 * emit them during a rolling deploy, a dead-lettered event) or, rarely, applied twice. This
 * recounts a page of videos from {@code social.video_like} and {@code social.comment} and
 * corrects any total that disagrees.
 *
 * <p>Covers videos that already have a stored total. A video whose every event was lost has no
 * row to find; the migration's backfill covers the videos that existed when the table did.
 */
public interface EngagementReconciler {

    /**
     * @param afterVideoId resume after this video id; null starts from the beginning
     * @param quietFor leave a total alone if an event touched it more recently than this, since
     *     one may still be in flight and a correction made now would count it twice
     */
    Sweep reconcile(String afterVideoId, int limit, Duration quietFor);

    /**
     * @param lastVideoId where the next call should resume; null once the sweep reached the end
     * @param checked how many videos were recounted
     * @param corrected how many stored totals were wrong
     */
    record Sweep(String lastVideoId, int checked, int corrected) {}
}

package com.shortvideo.appeal.api;

import java.time.Instant;
import java.util.List;

/** Read-only access to decided appeals, for the sweep that catches an approval moderation never applied. */
public interface AppealDirectory {

    /**
     * Appeals approved in {@code [decidedFrom, decidedTo]}, oldest decision first with the video id
     * breaking ties, strictly after {@code after} ({@code null} starts from the beginning). That is the
     * order of the index behind it, so a page is a short range scan however many appeals the window holds.
     */
    List<ApprovedAppealView> findApprovedPage(Instant decidedFrom, Instant decidedTo, ApprovedAppealView after, int limit);

    /**
     * @param moderationVersion the version of the rejection the appeal was filed against
     * @param decidedAt when the appeal was approved
     */
    record ApprovedAppealView(String videoId, long moderationVersion, Instant decidedAt) {}
}

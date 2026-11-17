package com.shortvideo.publication.api;

import java.util.List;
import java.util.Optional;

/** Read-only access to the current publication state (brief section 20, Milestone 5 reconciliation). */
public interface PublicationDirectory {

    Optional<PublicationStateView> findState(String videoId);

    /**
     * Ids, after {@code afterVideoId} and in id order, of videos that are waiting on a prerequisite
     * (PUBLISH_PENDING) or held by a rejection (SUSPENDED): the states a lost processing or
     * moderation event leaves a video stuck in.
     */
    List<String> findUnsettledPage(String afterVideoId, int limit);
}

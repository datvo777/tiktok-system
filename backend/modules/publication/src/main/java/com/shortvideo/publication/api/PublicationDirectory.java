package com.shortvideo.publication.api;

import java.util.Optional;

/** Read-only access to the current publication state (brief section 20, Milestone 5 reconciliation). */
public interface PublicationDirectory {

    Optional<PublicationStateView> findState(String videoId);
}

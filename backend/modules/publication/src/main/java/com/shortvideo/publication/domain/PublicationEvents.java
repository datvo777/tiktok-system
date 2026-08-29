package com.shortvideo.publication.domain;

/** Canonical absolute-state payload (brief section 10). */
public final class PublicationEvents {

    public record PublicationStateChanged(
            String videoId, String ownerAccountId, PublicationState state, boolean intent, long aggregateVersion) {}

    private PublicationEvents() {}
}

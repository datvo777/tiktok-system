package com.shortvideo.publication.api;

public record PublicationStateView(
        String videoId, String ownerAccountId, String state, boolean intent, long aggregateVersion) {}

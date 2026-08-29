package com.shortvideo.publication.domain;

public record PublicationView(String videoId, PublicationState state, boolean intent) {}

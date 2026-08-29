package com.shortvideo.publication.web;

import com.shortvideo.publication.domain.PublicationView;

public final class PublicationDtos {

    public record PublicationResponse(String videoId, String state, boolean intent) {
        public static PublicationResponse from(PublicationView view) {
            return new PublicationResponse(view.videoId(), view.state().name(), view.intent());
        }
    }

    private PublicationDtos() {}
}

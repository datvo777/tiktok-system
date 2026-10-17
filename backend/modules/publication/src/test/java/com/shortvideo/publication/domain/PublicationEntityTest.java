package com.shortvideo.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The publication state machine, which decides whether a video is in the public
 * feed. It is the most consequential piece of logic in the system and had no
 * unit coverage at all — it could only be exercised through a Testcontainers
 * integration test, so it was untested wherever Docker was not running.
 */
class PublicationEntityTest {

    private PublicationEntity newVideo() {
        return new PublicationEntity(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void startsPrivateWithNoIntent() {
        PublicationEntity video = newVideo();

        assertThat(video.getState()).isEqualTo(PublicationState.PRIVATE);
        assertThat(video.isIntent()).isFalse();
    }

    @Test
    void publishIntentAloneOnlyReachesPending() {
        PublicationEntity video = newVideo();

        assertThat(video.requestPublish()).isTrue();
        assertThat(video.getState()).isEqualTo(PublicationState.PUBLISH_PENDING);
    }

    @Test
    void publishesOnlyWhenIntentProcessingAndModerationAllAgree() {
        PublicationEntity video = newVideo();
        video.requestPublish();
        video.setProcessingReady(true);
        assertThat(video.getState()).isEqualTo(PublicationState.PUBLISH_PENDING);

        video.setModerationApproved(true);
        assertThat(video.getState()).isEqualTo(PublicationState.PUBLISHED);
    }

    @Test
    void orderOfPrerequisitesDoesNotMatter() {
        PublicationEntity video = newVideo();
        video.setModerationApproved(true);
        video.setProcessingReady(true);
        assertThat(video.getState()).isEqualTo(PublicationState.PRIVATE);

        video.requestPublish();
        assertThat(video.getState()).isEqualTo(PublicationState.PUBLISHED);
    }

    @Test
    void withdrawingPublishReturnsToPrivateAndKeepsModerationApproval() {
        PublicationEntity video = newVideo();
        video.requestPublish();
        video.setProcessingReady(true);
        video.setModerationApproved(true);

        assertThat(video.withdrawPublish()).isTrue();
        assertThat(video.getState()).isEqualTo(PublicationState.PRIVATE);
        assertThat(video.isIntent()).isFalse();
        // The point of unpublish being distinct from a takedown: re-publishing
        // must not queue the video for review a second time.
        assertThat(video.isModerationApproved()).isTrue();

        video.requestPublish();
        assertThat(video.getState()).isEqualTo(PublicationState.PUBLISHED);
    }

    @Test
    void withdrawingWhenAlreadyPrivateChangesNothing() {
        PublicationEntity video = newVideo();

        assertThat(video.withdrawPublish()).isFalse();
        assertThat(video.getState()).isEqualTo(PublicationState.PRIVATE);
    }

    @Test
    void rejectionSuspendsAndClearsModerationApproval() {
        PublicationEntity video = newVideo();
        video.requestPublish();
        video.setProcessingReady(true);
        video.setModerationApproved(true);

        assertThat(video.suspend()).isTrue();
        assertThat(video.getState()).isEqualTo(PublicationState.SUSPENDED);
        assertThat(video.isModerationApproved()).isFalse();
    }

    @Test
    void removalIsTerminalAndSurvivesEveryLaterPrerequisiteFlip() {
        PublicationEntity video = newVideo();
        video.requestPublish();
        video.setProcessingReady(true);
        video.remove();

        assertThat(video.setModerationApproved(true)).isFalse();
        assertThat(video.requestPublish()).isFalse();
        assertThat(video.withdrawPublish()).isFalse();
        assertThat(video.getState()).isEqualTo(PublicationState.REMOVED);
    }

    @Test
    void repeatedRemovalReportsNoChange() {
        PublicationEntity video = newVideo();

        assertThat(video.remove()).isTrue();
        assertThat(video.remove()).isFalse();
    }
}

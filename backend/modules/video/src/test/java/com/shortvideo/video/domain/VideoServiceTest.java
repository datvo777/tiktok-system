package com.shortvideo.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Existence non-disclosure (brief section 12.3): a non-owner polling a video
 * that exists must see exactly what they would see for a video id that does
 * not exist at all — otherwise the response itself reveals that the id is
 * real, just not theirs.
 */
class VideoServiceTest {

    @Test
    void nonOwnerAndMissingVideoProduceTheIdenticalNotFoundResponse() {
        VideoJpaRepository repository = mock(VideoJpaRepository.class);
        VideoService service = new VideoService(repository, null, null, null, null, null, null, null);

        UUID existingVideoId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String otherCaller = UUID.randomUUID().toString();
        when(repository.findById(existingVideoId))
                .thenReturn(Optional.of(new VideoEntity(existingVideoId, ownerId, "title", "desc")));

        UUID missingVideoId = UUID.randomUUID();
        when(repository.findById(missingVideoId)).thenReturn(Optional.empty());

        Throwable nonOwner =
                catchThrowable(() -> service.findForPolling(existingVideoId.toString(), otherCaller));
        Throwable notFound =
                catchThrowable(() -> service.findForPolling(missingVideoId.toString(), otherCaller));

        assertThat(nonOwner).isInstanceOf(VideoExceptions.VideoNotFound.class);
        assertThat(notFound).isInstanceOf(VideoExceptions.VideoNotFound.class);
        assertThat(nonOwner.getMessage()).isEqualTo(notFound.getMessage());
    }
}

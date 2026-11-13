package com.shortvideo.moderation.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.moderation.api.PolicyCategory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A decision that has already been made is not made again. Every approve or reject appends an event
 * with a new version and an audit row, so a double click or a client retry that re-ran one would
 * write a second copy of something that happened once, and a repeated reject would reactivate a
 * revocation already in force. The collaborators that write those are deliberately left null: a
 * repeat that reaches them fails the test by throwing.
 */
class ModerationServiceDecisionGuardTest {

    private final ModerationJpaRepository repository = mock(ModerationJpaRepository.class);
    private final ModerationService service = new ModerationService(
            repository,
            null,
            null,
            null,
            mock(ModerationScreener.class),
            new ModerationProperties(),
            mock(EligibilityDirectory.class),
            new SimpleMeterRegistry());

    private final String videoId = UUID.randomUUID().toString();

    private ModerationEntity record() {
        var record = new ModerationEntity(UUID.fromString(videoId), UUID.randomUUID());
        when(repository.findById(UUID.fromString(videoId))).thenReturn(Optional.of(record));
        return record;
    }

    @Test
    void approvingAnApprovedVideoAgainDoesNothing() {
        record().approve();

        service.approve(videoId, "admin");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void approvingAReinstatedVideoAgainDoesNothing() {
        var record = record();
        record.reject(PolicyCategory.SPAM_DECEPTIVE, "spam");
        record.approve(); // REJECTED -> REINSTATED

        service.approve(videoId, "admin");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theSameRejectionAgainDoesNothing() {
        record().reject(PolicyCategory.SPAM_DECEPTIVE, "spam");

        service.reject(videoId, PolicyCategory.SPAM_DECEPTIVE, "spam", "admin");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void closingARemovedVideoCountsOnlyTheFirstTime() {
        when(repository.close(any(), any())).thenReturn(1, 0);
        var meters = new SimpleMeterRegistry();
        var closing = new ModerationService(
                repository, null, null, null, mock(ModerationScreener.class), new ModerationProperties(),
                mock(EligibilityDirectory.class), meters);

        closing.closeForRemovedVideo(videoId);
        closing.closeForRemovedVideo(videoId);

        org.assertj.core.api.Assertions.assertThat(meters.counter("moderation.queue.closed").count()).isEqualTo(1.0);
    }
}

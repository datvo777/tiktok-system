package com.shortvideo.upload.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadSessionEntityTest {

    private static UploadSessionEntity expiringAt(Instant expiresAt) {
        return new UploadSessionEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "k", 1, 100, expiresAt, null);
    }

    @Test
    void anUploadThatFinishesJustAfterTheDeadlineCanStillComplete() {
        UploadSessionEntity session = expiringAt(Instant.now().minus(Duration.ofMinutes(3)));

        assertThat(session.canStillComplete(Instant.now())).isTrue();
    }

    @Test
    void completionStopsOnceTheGraceWindowIsOver() {
        UploadSessionEntity session =
                expiringAt(Instant.now().minus(UploadSessionEntity.COMPLETION_GRACE).minusSeconds(1));

        assertThat(session.canStillComplete(Instant.now())).isFalse();
    }
}

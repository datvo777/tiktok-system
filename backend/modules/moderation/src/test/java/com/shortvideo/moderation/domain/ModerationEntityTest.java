package com.shortvideo.moderation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.shortvideo.moderation.api.PolicyCategory;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The moderation state machine, which decides whether a video may ever be public. */
class ModerationEntityTest {

    private ModerationEntity record() {
        return new ModerationEntity(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void startsPendingAndUnqueued() {
        var record = record();

        assertThat(record.getState()).isEqualTo(ModerationState.PENDING);
        assertThat(record.getQueuedAt()).isNull();
        assertThat(record.getClosedAt()).isNull();
    }

    @Test
    void approvingAPendingVideoApprovesIt() {
        var record = record();

        boolean wasRejected = record.approve();

        assertThat(wasRejected).isFalse();
        assertThat(record.getState()).isEqualTo(ModerationState.APPROVED);
    }

    @Test
    void rejectingRecordsTheClassificationAndItsTier() {
        var record = record();

        record.reject(PolicyCategory.SPAM_DECEPTIVE, "spam");

        assertThat(record.getState()).isEqualTo(ModerationState.REJECTED);
        assertThat(record.getPolicyCategory()).isEqualTo(PolicyCategory.SPAM_DECEPTIVE);
        assertThat(record.getPolicyTier()).isEqualTo(PolicyCategory.SPAM_DECEPTIVE.tier());
        assertThat(record.getReason()).isEqualTo("spam");
    }

    /** An approved video can still be taken down: reject is not limited to PENDING. */
    @Test
    void anApprovedVideoCanStillBeRejected() {
        var record = record();
        record.approve();

        record.reject(PolicyCategory.SPAM_DECEPTIVE, null);

        assertThat(record.getState()).isEqualTo(ModerationState.REJECTED);
    }

    /** Reinstating clears the finding with the rejection, so an approved video never looks like it carries one. */
    @Test
    void approvingARejectedVideoReinstatesItAndClearsTheClassification() {
        var record = record();
        record.reject(PolicyCategory.SPAM_DECEPTIVE, "spam");

        boolean wasRejected = record.approve();

        assertThat(wasRejected).isTrue();
        assertThat(record.getState()).isEqualTo(ModerationState.REINSTATED);
        assertThat(record.getPolicyCategory()).isNull();
        assertThat(record.getPolicyTier()).isNull();
        assertThat(record.getReason()).isNull();
    }

    @Test
    void aQueuedRecordRemembersWhenAndWhy() {
        var record = record();

        record.enqueueOnCreate(QueueReason.RISK);

        assertThat(record.getQueuedAt()).isEqualTo(record.getCreatedAt());
        assertThat(record.getQueueReason()).isEqualTo(QueueReason.RISK);
    }
}

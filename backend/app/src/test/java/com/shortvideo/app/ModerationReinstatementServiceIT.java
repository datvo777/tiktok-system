package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shortvideo.moderation.api.PolicyCategory;
import com.shortvideo.moderation.domain.ModerationService;
import com.shortvideo.shared.revocation.DurableRevocationReader;
import com.shortvideo.shared.revocation.DurableRevocationWriter;
import com.shortvideo.shared.revocation.RevocationCommand;
import com.shortvideo.shared.revocation.RevocationConflictException;
import com.shortvideo.shared.revocation.RevocationSources;
import com.shortvideo.shared.revocation.RevocationSubjects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reversing a rejection against real PostgreSQL: what the audit trail calls it, that it lifts only
 * the moderation block, and that it refuses rather than report a video reinstated while the block
 * that the media gateway reads is still in force.
 */
class ModerationReinstatementServiceIT extends AbstractDatabaseIT {

    @Autowired ModerationService moderation;
    @Autowired DurableRevocationWriter writer;
    @Autowired DurableRevocationReader reader;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;

    private String rejectedVideo() {
        String video = UUID.randomUUID().toString();
        moderation.createPending(video, UUID.randomUUID().toString());
        reject(video);
        return video;
    }

    private void reject(String video) {
        // A different reason each time: the same rejection twice is a repeat and changes nothing.
        moderation.reject(video, PolicyCategory.SPAM_DECEPTIVE, UUID.randomUUID().toString(), ModerationService.SYSTEM_ACTOR);
    }

    private String state(String video) {
        return jdbc.queryForObject(
                "SELECT state FROM moderation.moderation_record WHERE video_id = ?::uuid", String.class, video);
    }

    private int auditRows(String video, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM platform.admin_action WHERE target_id = ? AND action = ?", Integer.class, video, action);
    }

    /** Putting back a rejected video is a different act from a first approval, and the trail has to say so. */
    @Test
    void reinstatingARejectedVideoIsAuditedAsAReinstatementNotAnApproval() {
        String video = rejectedVideo();

        moderation.approve(video, ModerationService.SYSTEM_ACTOR);

        assertThat(state(video)).isEqualTo("REINSTATED");
        assertThat(auditRows(video, "video.reinstated")).isEqualTo(1);
        assertThat(auditRows(video, "video.approved")).isZero();
    }

    @Test
    void reinstatingLiftsTheModerationBlockButNotAHoldPlacedBesideIt() {
        String video = rejectedVideo();
        transaction.executeWithoutResult(s -> writer.activate(
                new RevocationCommand(RevocationSubjects.VIDEO, video, RevocationSources.LIFECYCLE, 1, "quarantined")));
        assertThat(reader.activeSources(RevocationSubjects.VIDEO, video))
                .containsExactlyInAnyOrder(RevocationSources.MODERATION, RevocationSources.LIFECYCLE);

        moderation.approve(video, ModerationService.SYSTEM_ACTOR);

        assertThat(reader.activeSources(RevocationSubjects.VIDEO, video)).containsExactly(RevocationSources.LIFECYCLE);
    }

    /** The guard compares against the version the block was set at, so a second rejection must move it. */
    @Test
    void aVideoRejectedAgainAfterBeingReinstatedCanBeReinstatedAgain() {
        String video = rejectedVideo();
        moderation.approve(video, ModerationService.SYSTEM_ACTOR);
        assertThat(reader.isActive(RevocationSubjects.VIDEO, video)).isFalse();

        reject(video);
        assertThat(reader.isActive(RevocationSubjects.VIDEO, video)).isTrue();
        moderation.approve(video, ModerationService.SYSTEM_ACTOR);

        assertThat(state(video)).isEqualTo("REINSTATED");
        assertThat(reader.isActive(RevocationSubjects.VIDEO, video)).isFalse();
        assertThat(auditRows(video, "video.reinstated")).isEqualTo(2);
    }

    /**
     * Pins the assumption the version guard rests on: nothing else saves the record while it is
     * REJECTED. If something did, every reinstatement would be refused, and this is the refusal.
     */
    @Test
    void aRecordChangedSinceItWasRejectedIsRefusedAndStaysRejectedWithItsBlock() {
        String video = rejectedVideo();
        jdbc.update(
                "UPDATE moderation.moderation_record SET aggregate_version = aggregate_version + 1 WHERE video_id = ?::uuid",
                video);

        assertThatThrownBy(() -> moderation.approve(video, ModerationService.SYSTEM_ACTOR))
                .isInstanceOf(RevocationConflictException.class);

        assertThat(state(video)).isEqualTo("REJECTED");
        assertThat(reader.isActive(RevocationSubjects.VIDEO, video)).isTrue();
        assertThat(auditRows(video, "video.reinstated")).isZero();
    }
}

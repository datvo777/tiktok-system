package com.shortvideo.moderation.domain;

import com.shortvideo.moderation.api.ModerationDecisionView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.moderation.api.ModerationBackfill;
import com.shortvideo.moderation.api.ModerationDirectory;
import com.shortvideo.moderation.api.PolicyCategory;
import com.shortvideo.shared.audit.AdminAction;
import com.shortvideo.shared.audit.AdminActionRecorder;
import com.shortvideo.shared.audit.AuditActions;
import com.shortvideo.shared.audit.AuditTargets;
import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import com.shortvideo.shared.revocation.DurableRevocationWriter;
import com.shortvideo.shared.revocation.RevocationClearCommand;
import com.shortvideo.shared.revocation.RevocationCommand;
import com.shortvideo.shared.revocation.RevocationSubjects;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModerationService implements ModerationDirectory, ModerationBackfill {

    private static final String PRODUCER = "short-video-backend";
    private static final String MODULE = "moderation";

    /** Revocation source type for a moderation rejection (brief section 16). */
    public static final String REJECTION_SOURCE = "MODERATION";

    /**
     * Recorded as the actor on an automated approval, so the audit trail can
     * distinguish it from a decision a person made.
     */
    public static final String SYSTEM_ACTOR = "00000000-0000-0000-0000-000000000000";

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ModerationService.class);

    private final ModerationJpaRepository repository;
    private final OutboxWriter outboxWriter;
    private final DurableRevocationWriter revocationWriter;
    private final AdminActionRecorder auditRecorder;
    private final ModerationScreener screener;
    private final ModerationProperties properties;
    private final EligibilityDirectory eligibilityDirectory;
    private final MeterRegistry meters;

    public ModerationService(
            ModerationJpaRepository repository,
            OutboxWriter outboxWriter,
            DurableRevocationWriter revocationWriter,
            AdminActionRecorder auditRecorder,
            ModerationScreener screener,
            ModerationProperties properties,
            EligibilityDirectory eligibilityDirectory,
            MeterRegistry meters) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.revocationWriter = revocationWriter;
        this.auditRecorder = auditRecorder;
        this.screener = screener;
        this.properties = properties;
        this.eligibilityDirectory = eligibilityDirectory;
        this.meters = meters;
    }

    /**
     * Consumed from {@code video.upload.completed} (brief section 7.1: "From
     * Milestone 3, the Moderation module consumes video.upload.completed and
     * creates a PENDING moderation record in its own transaction"). Idempotent:
     * a redelivered command finds the row already present and does nothing.
     *
     * <p>PENDING is not the same as "a person has to look at this". A record is only
     * <em>queued</em> for review when the screener sees risk or cannot decide, or (in
     * {@link QueueMode#ALL}) when it is not trusted; otherwise it is held until the owner asks
     * to publish. Playback is denied while PENDING either way (Rule 9).
     */
    @Transactional
    @Override
    public boolean createPending(String videoId, String creatorId) {
        UUID id = UUID.fromString(videoId);
        if (repository.existsById(id)) {
            return false;
        }
        Intake intake = screen(videoId, creatorId);

        ModerationEntity record = new ModerationEntity(id, UUID.fromString(creatorId));
        if (intake.queueReason() != null) {
            // Before the first save, so the insert carries it and no later update moves the version.
            record.enqueueOnCreate(intake.queueReason());
            queued(intake.queueReason());
        }
        repository.saveAndFlush(record);
        // No outbox event for "pending": nothing needs to react to it — a missing
        // or pending moderation record already denies public eligibility (Rule 9).

        if (intake.autoApprove()) {
            log.debug("Auto-approving {} on the strength of creator standing", videoId);
            approve(videoId, SYSTEM_ACTOR);
            meters.counter("moderation.auto_approved").increment();
        } else if (intake.queueReason() == null) {
            meters.counter("moderation.held").increment();
        }
        return true;
    }

    /**
     * What to do with a new upload: approve it, queue it (with the reason), or hold it.
     *
     * <p>Every case without a clear conclusion is queued immediately, whatever the mode: the
     * switch off, no metadata, a screener that threw. Holding is reserved for a verdict that
     * says so, because a held record waits for the owner and nobody else would ever look at it.
     *
     * <p>Approving as {@code SYSTEM_ACTOR} rather than as a person keeps the audit trail honest:
     * a reviewer looking back can tell which approvals a human actually made.
     *
     * <p>A screener that throws is treated as a referral. An automated stage failing must never
     * be the reason something gets published. That holds for failures in the screener itself. A
     * database error from the lookups feeding it is different: it aborts the surrounding
     * transaction, so nothing is saved and the event listener's redelivery redoes the whole step.
     * Nothing is lost and nothing is auto-approved, but the outcome is a retry, not a referral.
     */
    private Intake screen(String videoId, String creatorId) {
        if (!properties.isAutoApproveEnabled()) {
            // Restores exactly the behaviour from before the pre-screen existed.
            return Intake.queue(QueueReason.UPLOAD);
        }
        UUID creator = UUID.fromString(creatorId);
        ScreeningVerdict verdict;
        try {
            var metadata = eligibilityDirectory.findVideoEligibility(videoId);
            if (metadata.isEmpty()) {
                // The title and description arrive through a different consumer than the
                // upload event that got us here, with no ordering between the two. Screening
                // without them would leave creator standing as the only signal and skip the
                // text checks entirely, so an unknown video is referred (Rule 9).
                log.warn("No metadata for {} yet; referring to a human instead of screening", videoId);
                return Intake.queue(QueueReason.UNSCREENED);
            }
            verdict = screener.screen(new ModerationScreener.ScreeningSubject(
                    videoId,
                    creatorId,
                    metadata.get().title(),
                    metadata.get().description(),
                    repository.countByCreatorIdAndState(creator, ModerationState.APPROVED),
                    repository.countByCreatorIdAndState(creator, ModerationState.REJECTED)));
        } catch (RuntimeException e) {
            // A database error here has already aborted the transaction (see the method comment).
            log.warn("Moderation screener failed for {}; leaving it for a human", videoId, e);
            return Intake.queue(QueueReason.UNSCREENED);
        }

        return switch (verdict) {
            case AUTO_APPROVE -> Intake.APPROVE;
            case REFER_TO_HUMAN -> Intake.queue(QueueReason.RISK);
            case HOLD_UNTIL_PUBLISH -> properties.getQueueMode() == QueueMode.ALL
                    ? Intake.queue(QueueReason.UPLOAD)
                    : Intake.HOLD;
        };
    }

    /** The outcome of screening an upload; {@code queueReason} is null when approved or held. */
    private record Intake(boolean autoApprove, QueueReason queueReason) {
        static final Intake APPROVE = new Intake(true, null);
        static final Intake HOLD = new Intake(false, null);

        static Intake queue(QueueReason reason) {
            return new Intake(false, reason);
        }
    }

    /**
     * The owner asked to publish a video that was being held: queue it for review now.
     *
     * <p>No record yet is an error, not a no-op. {@code video.publication.pending} can overtake
     * {@code video.upload.completed} (different keys, different partitions), and dropping it here
     * would leave the video waiting on a review nobody knows to do; throwing sends it back
     * through the listener's retry, as {@code PublicationService} does for a missing draft.
     * Anything already decided or already queued is left alone, so redelivery changes nothing.
     */
    @Transactional
    public boolean requestReview(String videoId) {
        UUID id = UUID.fromString(videoId);
        if (!repository.existsById(id)) {
            throw new IllegalStateException("No moderation record for video " + videoId + " yet");
        }
        if (repository.enqueue(id, Instant.now(), QueueReason.PUBLISH_REQUESTED.name()) == 1) {
            queued(QueueReason.PUBLISH_REQUESTED);
            return true;
        }
        return false;
    }

    /**
     * The video was removed (deleted by its owner or taken down), so there is nothing left to
     * review: drop it from the queue and the held sweep. Not an error when there is no record yet
     * or it was already decided; the first has nothing to close and the second stays as it was.
     */
    @Transactional
    public void closeForRemovedVideo(String videoId) {
        if (repository.close(UUID.fromString(videoId), Instant.now()) == 1) {
            meters.counter("moderation.queue.closed").increment();
        }
    }

    /** Held records after {@code afterVideoId}, oldest id first, for the sweep that catches a lost publish event. */
    @Override
    @Transactional(readOnly = true)
    public List<String> findHeldPage(String afterVideoId, int limit) {
        UUID after = afterVideoId == null ? new UUID(0, 0) : UUID.fromString(afterVideoId);
        return repository.findHeldAfter(after, PageRequest.of(0, limit)).stream()
                .map(UUID::toString)
                .toList();
    }

    private void queued(QueueReason reason) {
        meters.counter("moderation.queue.entered", "reason", reason.name()).increment();
    }

    /** PENDING -> APPROVED, or REJECTED -> REINSTATED, clearing only the moderation revocation field. */
    @Transactional
    public void approve(String videoId, String actorAccountId) {
        ModerationEntity record = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new ModerationExceptions.ModerationRecordNotFound("No such moderation record"));

        // A second approval (a double click, a client retry) would append another event with a new
        // version and another audit row for a decision that was already made.
        if (record.getState() == ModerationState.APPROVED || record.getState() == ModerationState.REINSTATED) {
            return;
        }
        long previousVersion = record.getAggregateVersion();
        boolean wasRejected = record.approve();
        ModerationEntity saved = repository.saveAndFlush(record);

        String eventType = wasRejected ? EventTypes.VIDEO_MODERATION_REINSTATED : EventTypes.VIDEO_MODERATION_APPROVED;
        append(saved, eventType, null, null);

        if (wasRejected) {
            revocationWriter.clear(new RevocationClearCommand(
                    RevocationSubjects.VIDEO, saved.getVideoId().toString(), REJECTION_SOURCE, previousVersion));
        }

        auditRecorder.record(AdminAction.of(
                actorAccountId, AuditActions.VIDEO_APPROVED, AuditTargets.VIDEO, saved.getVideoId().toString()));
    }

    /**
     * Reacts to an approved appeal (brief section 18, Milestone 6): reinstates
     * only if the video is still REJECTED. If moderation was already reinstated
     * or changed by another path in the meantime, this is a safe no-op — the
     * appeal-approval workflow exists specifically to reverse a REJECTED
     * decision, not to force one.
     */
    @Transactional
    public void reinstateFromAppeal(String videoId) {
        ModerationEntity record = repository.findById(UUID.fromString(videoId)).orElse(null);
        if (record == null || record.getState() != ModerationState.REJECTED) {
            return;
        }
        long previousVersion = record.getAggregateVersion();
        record.approve(); // REJECTED -> REINSTATED
        ModerationEntity saved = repository.saveAndFlush(record);
        // Reinstatement clears the classification along with the rejection, so
        // there is no policy to carry here.
        append(saved, EventTypes.VIDEO_MODERATION_REINSTATED, null, null);
        revocationWriter.clear(new RevocationClearCommand(
                RevocationSubjects.VIDEO, saved.getVideoId().toString(), REJECTION_SOURCE, previousVersion));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModerationDecisionView> findDecision(String videoId) {
        return repository
                .findById(UUID.fromString(videoId))
                .map(r -> new ModerationDecisionView(
                        r.getVideoId().toString(), r.getCreatorId().toString(), r.getState().name(), r.getAggregateVersion()));
    }

    /**
     * Keyset-paged review queue for the admin UI (a queue of thousands should
     * never be fetched in one response). {@code cursor} is an opaque string
     * previously returned as {@code nextCursor}; {@code null} starts from the
     * item that has been waiting longest.
     */
    @Transactional(readOnly = true)
    public ModerationPage listPending(String cursor, int limit) {
        PendingCursor after = PendingCursor.decode(cursor);
        // Fetch one extra row to learn whether another page follows, without a
        // separate count query.
        List<ModerationEntity> rows =
                repository.findQueuePageAfter(after.queuedAt(), after.videoId(), PageRequest.of(0, limit + 1));

        boolean hasMore = rows.size() > limit;
        List<ModerationEntity> page = hasMore ? rows.subList(0, limit) : rows;

        List<ModerationView> items = page.stream()
                .map(r -> new ModerationView(
                        r.getVideoId().toString(),
                        r.getCreatorId().toString(),
                        r.getState(),
                        r.getCreatedAt(),
                        r.getQueuedAt(),
                        r.getQueueReason()))
                .toList();

        String nextCursor = hasMore
                ? new PendingCursor(page.get(page.size() - 1).getQueuedAt(), page.get(page.size() - 1).getVideoId())
                        .encode()
                : null;

        return new ModerationPage(items, nextCursor);
    }

    /**
     * One transaction: REJECTED state, the MODERATION revocation record, and the
     * canonical outbox event (brief section 18).
     */
    @Transactional
    public void reject(String videoId, PolicyCategory policyCategory, String reason, String actorAccountId) {
        ModerationEntity record = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new ModerationExceptions.ModerationRecordNotFound("No such moderation record"));

        // The same rejection again is a repeat, not a decision: re-running it would bump the blocking
        // version and reactivate a revocation that is already in force. A different category or reason
        // is a reclassification and still goes through.
        if (record.getState() == ModerationState.REJECTED
                && record.getPolicyCategory() == policyCategory
                && java.util.Objects.equals(record.getReason(), reason)) {
            return;
        }
        record.reject(policyCategory, reason);
        ModerationEntity saved = repository.saveAndFlush(record);

        append(saved, EventTypes.VIDEO_MODERATION_REJECTED, reason, policyCategory);

        // The revocation reason stays human-readable but now leads with the
        // policy, so an operator reading the deny record sees the classification
        // rather than whatever prose happened to be typed.
        String revocationReason = reason == null || reason.isBlank()
                ? policyCategory.name()
                : policyCategory.name() + ": " + reason;

        revocationWriter.activate(new RevocationCommand(
                RevocationSubjects.VIDEO,
                saved.getVideoId().toString(),
                REJECTION_SOURCE,
                saved.getAggregateVersion(),
                revocationReason));

        auditRecorder.record(new AdminAction(
                actorAccountId,
                AuditActions.VIDEO_REJECTED,
                AuditTargets.VIDEO,
                saved.getVideoId().toString(),
                policyCategory.name(),
                reason));
    }

    private void append(
            ModerationEntity record, String eventType, String reason, PolicyCategory policyCategory) {
        var payload = new ModerationEvents.ModerationStateChanged(
                record.getVideoId().toString(),
                record.getCreatorId().toString(),
                record.getState(),
                record.getAggregateVersion(),
                reason,
                policyCategory);

        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                1,
                AggregateTypes.MODERATION,
                record.getVideoId().toString(),
                record.getAggregateVersion(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));
    }
}

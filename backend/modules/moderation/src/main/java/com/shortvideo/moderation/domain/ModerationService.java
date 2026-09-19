package com.shortvideo.moderation.domain;

import com.shortvideo.moderation.api.ModerationDecisionView;
import com.shortvideo.eligibility.api.EligibilityDirectory;
import com.shortvideo.eligibility.api.VideoEligibilityView;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModerationService implements ModerationDirectory {

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

    public ModerationService(
            ModerationJpaRepository repository,
            OutboxWriter outboxWriter,
            DurableRevocationWriter revocationWriter,
            AdminActionRecorder auditRecorder,
            ModerationScreener screener,
            ModerationProperties properties,
            EligibilityDirectory eligibilityDirectory) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.revocationWriter = revocationWriter;
        this.auditRecorder = auditRecorder;
        this.screener = screener;
        this.properties = properties;
        this.eligibilityDirectory = eligibilityDirectory;
    }

    /**
     * Consumed from {@code video.upload.completed} (brief section 7.1: "From
     * Milestone 3, the Moderation module consumes video.upload.completed and
     * creates a PENDING moderation record in its own transaction"). Idempotent:
     * a redelivered command finds the row already present and does nothing.
     */
    @Transactional
    public void createPending(String videoId, String creatorId) {
        UUID id = UUID.fromString(videoId);
        if (repository.existsById(id)) {
            return;
        }
        ModerationEntity record = repository.saveAndFlush(new ModerationEntity(id, UUID.fromString(creatorId)));
        // No outbox event for "pending": nothing needs to react to it — a missing
        // or pending moderation record already denies public eligibility (Rule 9).

        autoApproveIfScreenerAllows(record, videoId, creatorId);
    }

    /**
     * The automated first pass.
     *
     * <p>Every upload previously waited on a human, so time-to-publish was
     * bounded by how fast someone was watching the queue. The screener can only
     * ever move a video <em>out</em> of that queue, never take one down, so the
     * worst case here is the behaviour that already existed.
     *
     * <p>Approving as {@code SYSTEM_ACTOR} rather than as a person keeps the
     * audit trail honest: a reviewer looking back can tell which approvals a
     * human actually made.
     *
     * <p>A screener that throws is treated as a referral. An automated stage
     * failing must never be the reason something gets published.
     */
    private void autoApproveIfScreenerAllows(ModerationEntity record, String videoId, String creatorId) {
        if (!properties.isAutoApproveEnabled()) {
            return;
        }
        UUID creator = UUID.fromString(creatorId);
        ScreeningVerdict verdict;
        try {
            var metadata = eligibilityDirectory.findVideoEligibility(videoId);
            verdict = screener.screen(new ModerationScreener.ScreeningSubject(
                    videoId,
                    creatorId,
                    metadata.map(VideoEligibilityView::title).orElse(null),
                    metadata.map(VideoEligibilityView::description).orElse(null),
                    repository.countByCreatorIdAndState(creator, ModerationState.APPROVED),
                    repository.countByCreatorIdAndState(creator, ModerationState.REJECTED)));
        } catch (RuntimeException e) {
            log.warn("Moderation screener failed for {}; leaving it for a human", videoId, e);
            return;
        }

        if (verdict == ScreeningVerdict.AUTO_APPROVE) {
            log.debug("Auto-approving {} on the strength of creator standing", videoId);
            approve(videoId, SYSTEM_ACTOR);
        }
    }

    /** PENDING -> APPROVED, or REJECTED -> REINSTATED, clearing only the moderation revocation field. */
    @Transactional
    public void approve(String videoId, String actorAccountId) {
        ModerationEntity record = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new ModerationExceptions.ModerationRecordNotFound("No such moderation record"));

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

    @Transactional(readOnly = true)
    public List<ModerationView> listPending() {
        return repository.findByStateOrderByCreatedAtAsc(ModerationState.PENDING).stream()
                .map(r -> new ModerationView(
                        r.getVideoId().toString(), r.getCreatorId().toString(), r.getState(), r.getCreatedAt()))
                .toList();
    }

    /**
     * Keyset-paged pending queue for the admin UI (a queue of thousands should
     * never be fetched in one response). {@code cursor} is an opaque string
     * previously returned as {@code nextCursor}; {@code null} starts from the
     * oldest pending item.
     */
    @Transactional(readOnly = true)
    public ModerationPage listPending(String cursor, int limit) {
        PendingCursor after = PendingCursor.decode(cursor);
        // Fetch one extra row to learn whether another page follows, without a
        // separate count query.
        List<ModerationEntity> rows = repository.findPageAfter(
                ModerationState.PENDING, after.createdAt(), after.videoId(), PageRequest.of(0, limit + 1));

        boolean hasMore = rows.size() > limit;
        List<ModerationEntity> page = hasMore ? rows.subList(0, limit) : rows;

        List<ModerationView> items = page.stream()
                .map(r -> new ModerationView(
                        r.getVideoId().toString(), r.getCreatorId().toString(), r.getState(), r.getCreatedAt()))
                .toList();

        String nextCursor = hasMore
                ? new PendingCursor(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getVideoId())
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

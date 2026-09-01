package com.shortvideo.moderation.domain;

import com.shortvideo.moderation.api.ModerationDecisionView;
import com.shortvideo.moderation.api.ModerationDirectory;
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

    private final ModerationJpaRepository repository;
    private final OutboxWriter outboxWriter;
    private final DurableRevocationWriter revocationWriter;

    public ModerationService(
            ModerationJpaRepository repository, OutboxWriter outboxWriter, DurableRevocationWriter revocationWriter) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.revocationWriter = revocationWriter;
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
        repository.saveAndFlush(new ModerationEntity(id, UUID.fromString(creatorId)));
        // No outbox event: nothing needs to react to "pending" — a missing or
        // pending moderation record already denies public eligibility (Rule 9).
    }

    /** PENDING -> APPROVED, or REJECTED -> REINSTATED, clearing only the moderation revocation field. */
    @Transactional
    public void approve(String videoId) {
        ModerationEntity record = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new ModerationExceptions.ModerationRecordNotFound("No such moderation record"));

        long previousVersion = record.getAggregateVersion();
        boolean wasRejected = record.approve();
        ModerationEntity saved = repository.saveAndFlush(record);

        String eventType = wasRejected ? EventTypes.VIDEO_MODERATION_REINSTATED : EventTypes.VIDEO_MODERATION_APPROVED;
        append(saved, eventType, null);

        if (wasRejected) {
            revocationWriter.clear(new RevocationClearCommand(
                    RevocationSubjects.VIDEO, saved.getVideoId().toString(), REJECTION_SOURCE, previousVersion));
        }
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
        append(saved, EventTypes.VIDEO_MODERATION_REINSTATED, null);
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
    public void reject(String videoId, String reason) {
        ModerationEntity record = repository
                .findById(UUID.fromString(videoId))
                .orElseThrow(() -> new ModerationExceptions.ModerationRecordNotFound("No such moderation record"));

        record.reject(reason);
        ModerationEntity saved = repository.saveAndFlush(record);

        append(saved, EventTypes.VIDEO_MODERATION_REJECTED, reason);

        revocationWriter.activate(new RevocationCommand(
                RevocationSubjects.VIDEO,
                saved.getVideoId().toString(),
                REJECTION_SOURCE,
                saved.getAggregateVersion(),
                reason));
    }

    private void append(ModerationEntity record, String eventType, String reason) {
        var payload = new ModerationEvents.ModerationStateChanged(
                record.getVideoId().toString(),
                record.getCreatorId().toString(),
                record.getState(),
                record.getAggregateVersion(),
                reason);

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

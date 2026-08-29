package com.shortvideo.notification.domain;

import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final String PRODUCER = "short-video-backend";
    private static final String MODULE = "notification";

    private final NotificationJpaRepository repository;
    private final OutboxWriter outboxWriter;

    public NotificationService(NotificationJpaRepository repository, OutboxWriter outboxWriter) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Called by event-reaction listeners, each already guarded by its own
     * durable inbox claim before this runs (Rule 5) -- a redelivered domain
     * event never reaches this method twice, so no separate dedup key is
     * needed here for the "one user-visible effect" acceptance criterion.
     */
    @Transactional
    public void create(String recipientAccountId, String type, String message, String relatedVideoId) {
        NotificationEntity entity = new NotificationEntity(
                UUID.randomUUID(),
                UUID.fromString(recipientAccountId),
                type,
                message,
                relatedVideoId == null ? null : UUID.fromString(relatedVideoId));
        NotificationEntity saved = repository.saveAndFlush(entity);

        var payload = new NotificationEvents.NotificationCreated(
                saved.getNotificationId().toString(),
                saved.getRecipientAccountId().toString(),
                saved.getType(),
                saved.getMessage(),
                saved.getRelatedVideoId() == null ? null : saved.getRelatedVideoId().toString());

        outboxWriter.append(new EventEnvelope<>(
                UUID.randomUUID(),
                EventTypes.NOTIFICATION_CREATED,
                1,
                AggregateTypes.NOTIFICATION,
                saved.getNotificationId().toString(),
                saved.getAggregateVersion(),
                Instant.now(),
                PRODUCER,
                MODULE,
                MDC.get("correlationId"),
                null,
                payload));
    }

    @Transactional
    public void markRead(String notificationId, String callerAccountId) {
        NotificationEntity entity = repository
                .findById(UUID.fromString(notificationId))
                .orElseThrow(() -> new NotificationExceptions.NotificationNotFound("No such notification"));
        if (!entity.getRecipientAccountId().toString().equals(callerAccountId)) {
            // Same response as a missing notification: do not confirm existence to a non-recipient.
            throw new NotificationExceptions.NotificationNotFound("No such notification");
        }
        entity.markRead();
        repository.saveAndFlush(entity);
    }

    @Transactional(readOnly = true)
    public List<NotificationView> listForRecipient(String accountId) {
        return repository.findByRecipientAccountIdOrderByCreatedAtDesc(UUID.fromString(accountId)).stream()
                .map(e -> new NotificationView(
                        e.getNotificationId().toString(),
                        e.getType(),
                        e.getMessage(),
                        e.getRelatedVideoId() == null ? null : e.getRelatedVideoId().toString(),
                        e.isRead(),
                        e.getCreatedAt()))
                .toList();
    }
}

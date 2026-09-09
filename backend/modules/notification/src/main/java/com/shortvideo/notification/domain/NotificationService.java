package com.shortvideo.notification.domain;

import com.shortvideo.shared.events.AggregateTypes;
import com.shortvideo.shared.events.EventEnvelope;
import com.shortvideo.shared.events.EventTypes;
import com.shortvideo.shared.outbox.OutboxWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    /**
     * Marks every unread notification for the caller as read, in one statement.
     *
     * @return how many were actually unread, so the caller can tell "cleared 12"
     *     from "there was nothing to clear".
     */
    @Transactional
    public int markAllRead(String accountId) {
        return repository.markAllRead(UUID.fromString(accountId));
    }

    /** Default page size; the client may ask for less, never for more. */
    public static final int PAGE_SIZE = 30;
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * One page of the caller's notifications, newest first, plus the unread total
     * across all of them.
     *
     * <p>The unread count is computed over the whole set rather than over the
     * page: the badge previously counted only what the client had fetched, so an
     * account with a long history under-reported it.
     */
    @Transactional(readOnly = true)
    public NotificationPage listForRecipient(String accountId, String cursor, int limit) {
        UUID recipient = UUID.fromString(accountId);
        int size = limit <= 0 ? PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        // One extra row answers "is there another page?" without a count query.
        Pageable window = PageRequest.of(0, size + 1);

        NotificationCursors.Position position = NotificationCursors.decode(cursor);
        List<NotificationEntity> rows = position == null
                ? repository.findByRecipientAccountIdOrderByCreatedAtDescNotificationIdDesc(recipient, window)
                : repository.findPageAfter(recipient, position.createdAt(), position.id(), window);

        boolean more = rows.size() > size;
        List<NotificationEntity> page = more ? rows.subList(0, size) : rows;
        String next = more && !page.isEmpty()
                ? NotificationCursors.encode(
                        page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getNotificationId())
                : null;

        return new NotificationPage(
                page.stream()
                        .map(e -> new NotificationView(
                                e.getNotificationId().toString(),
                                e.getType(),
                                e.getMessage(),
                                e.getRelatedVideoId() == null ? null : e.getRelatedVideoId().toString(),
                                e.isRead(),
                                e.getCreatedAt()))
                        .toList(),
                next,
                repository.countByRecipientAccountIdAndReadIsFalse(recipient));
    }

    /** One page, the cursor that continues it, and the account-wide unread total. */
    public record NotificationPage(List<NotificationView> items, String nextCursor, long unreadCount) {}
}

package com.shortvideo.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationJpaRepository extends JpaRepository<NotificationEntity, UUID> {

    /** First page: newest first, bounded. */
    List<NotificationEntity> findByRecipientAccountIdOrderByCreatedAtDescNotificationIdDesc(
            UUID recipientAccountId, Pageable pageable);

    /**
     * Keyset continuation of the above. Ordered by {@code (createdAt, id)} and
     * seeking strictly before the cursor, so a notification arriving between two
     * page fetches cannot shift a row across the boundary and make it appear
     * twice or not at all.
     */
    @Query("""
            SELECT n FROM NotificationEntity n
            WHERE n.recipientAccountId = :recipientId
              AND (n.createdAt < :cursorCreatedAt
                   OR (n.createdAt = :cursorCreatedAt AND n.notificationId < :cursorId))
            ORDER BY n.createdAt DESC, n.notificationId DESC
            """)
    List<NotificationEntity> findPageAfter(
            @Param("recipientId") UUID recipientAccountId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * Marks every unread notification for one recipient as read, in one
     * statement.
     *
     * <p>The client previously looped its unread list and issued one POST per
     * row, so clearing twenty notifications was twenty round trips -- and the
     * count it looped over was whatever happened to be on screen, so anything
     * older stayed unread regardless.
     *
     * @return how many rows were actually updated.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE NotificationEntity n SET n.read = true WHERE n.recipientAccountId = :recipientId AND n.read = false")
    int markAllRead(@Param("recipientId") UUID recipientAccountId);

    long countByRecipientAccountIdAndReadIsFalse(UUID recipientAccountId);
}

package com.shortvideo.upload.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Package-private by convention: only the upload module uses it. */
interface UploadJpaRepository extends JpaRepository<UploadSessionEntity, UUID> {

    /**
     * Expired sessions the cleanup job may try now, oldest first. A session that failed to reap
     * is hidden until its {@code next_reap_at}, so repeated failures cannot hold the head of the
     * queue and starve newer sessions.
     */
    @Query("""
            SELECT s FROM UploadSessionEntity s
            WHERE s.status = :status AND s.expiresAt < :cutoff
              AND (s.nextReapAt IS NULL OR s.nextReapAt <= :now)
            ORDER BY s.expiresAt ASC
            """)
    List<UploadSessionEntity> findReapable(
            @Param("status") UploadStatus status,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable page);

    /**
     * Counts a failed reap and schedules the next try. A bulk update, so it neither loads the
     * row nor bumps its version, and it skips a session that was completed in the meantime.
     */
    @Modifying
    @Query("""
            UPDATE UploadSessionEntity s
            SET s.reapAttempts = s.reapAttempts + 1, s.nextReapAt = :nextReapAt
            WHERE s.uploadId = :uploadId AND s.status = com.shortvideo.upload.domain.UploadStatus.PENDING
            """)
    int recordReapFailure(@Param("uploadId") UUID uploadId, @Param("nextReapAt") Instant nextReapAt);

    /**
     * Sessions this account has open and still writable. Expired ones are excluded
     * because their policy can no longer be used, whether or not the cleanup job
     * has reaped them yet — otherwise a burst of abandoned sessions would lock the
     * account out until the next sweep.
     */
    long countByAccountIdAndStatusAndExpiresAtAfter(UUID accountId, UploadStatus status, Instant now);

    /**
     * Bytes this account has reserved across its open, still-writable sessions: each one's
     * policy lets the holder write up to its cap, so the sum is the account's write allowance.
     */
    @Query("""
            SELECT COALESCE(SUM(s.maxSizeBytes), 0) FROM UploadSessionEntity s
            WHERE s.accountId = :accountId AND s.status = :status AND s.expiresAt > :now
            """)
    long sumMaxSizeBytesOpen(
            @Param("accountId") UUID accountId, @Param("status") UploadStatus status, @Param("now") Instant now);

    /** Takes the row lock, so two completions of one session serialise instead of racing on its version. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UploadSessionEntity s WHERE s.uploadId = :uploadId")
    Optional<UploadSessionEntity> findForUpdate(@Param("uploadId") UUID uploadId);

    /** Reconciliation scan: completed sessions in a time window, paged by id. */
    @Query("""
            SELECT s FROM UploadSessionEntity s
            WHERE s.status = :status AND s.updatedAt BETWEEN :from AND :to AND s.uploadId > :after
            ORDER BY s.uploadId
            """)
    List<UploadSessionEntity> findInWindowAfter(
            @Param("status") UploadStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("after") UUID after,
            Pageable page);

    Optional<UploadSessionEntity> findByAccountIdAndCreateIdempotencyKey(UUID accountId, String createIdempotencyKey);
}

package com.shortvideo.upload.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private by convention: only the upload module uses it. */
interface UploadJpaRepository extends JpaRepository<UploadSessionEntity, UUID> {

    List<UploadSessionEntity> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(UploadStatus status, Instant cutoff);

    /**
     * Sessions this account has open and still writable. Expired ones are excluded
     * because their policy can no longer be used, whether or not the cleanup job
     * has reaped them yet — otherwise a burst of abandoned sessions would lock the
     * account out until the next sweep.
     */
    long countByAccountIdAndStatusAndExpiresAtAfter(UUID accountId, UploadStatus status, Instant now);
}

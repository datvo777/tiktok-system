package com.shortvideo.upload.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private by convention: only the upload module uses it. */
interface UploadJpaRepository extends JpaRepository<UploadSessionEntity, UUID> {

    List<UploadSessionEntity> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(UploadStatus status, Instant cutoff);
}

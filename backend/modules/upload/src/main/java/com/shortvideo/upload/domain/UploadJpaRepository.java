package com.shortvideo.upload.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private by convention: only the upload module uses it. */
interface UploadJpaRepository extends JpaRepository<UploadSessionEntity, UUID> {}

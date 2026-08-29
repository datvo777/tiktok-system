package com.shortvideo.video.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private by convention: only the video module uses it. */
interface VideoJpaRepository extends JpaRepository<VideoEntity, UUID> {}

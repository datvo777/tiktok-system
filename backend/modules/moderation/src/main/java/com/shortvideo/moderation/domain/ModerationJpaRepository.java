package com.shortvideo.moderation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private by convention: only the moderation module uses it. */
interface ModerationJpaRepository extends JpaRepository<ModerationEntity, UUID> {

    List<ModerationEntity> findByStateOrderByCreatedAtAsc(ModerationState state);
}

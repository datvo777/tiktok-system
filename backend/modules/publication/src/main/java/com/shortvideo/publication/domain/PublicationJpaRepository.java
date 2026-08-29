package com.shortvideo.publication.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private by convention: only the publication module uses it. */
interface PublicationJpaRepository extends JpaRepository<PublicationEntity, UUID> {}

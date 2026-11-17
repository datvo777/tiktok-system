package com.shortvideo.publication.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Package-private by convention: only the publication module uses it. */
interface PublicationJpaRepository extends JpaRepository<PublicationEntity, UUID> {

    @Query("select e.videoId from PublicationEntity e where e.state in :states and e.videoId > :afterId order by e.videoId")
    List<UUID> findIdsInStatesAfter(
            @Param("states") Collection<PublicationState> states, @Param("afterId") UUID afterId, Pageable pageable);
}

package com.shortvideo.video.domain;

import com.shortvideo.video.api.AssetLifecycleState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupersededAssetJpaRepository extends JpaRepository<SupersededAssetEntity, UUID> {

    List<SupersededAssetEntity> findByStateOrderByCreatedAtAsc(AssetLifecycleState state, Pageable page);

    /** Whether the video's source has been scheduled for purge (only source rows carry an explicit prefix). */
    boolean existsByVideoIdAndPurgePrefixIsNotNull(UUID videoId);
}

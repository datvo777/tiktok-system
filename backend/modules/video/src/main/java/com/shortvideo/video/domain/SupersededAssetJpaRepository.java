package com.shortvideo.video.domain;

import com.shortvideo.video.api.AssetLifecycleState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupersededAssetJpaRepository extends JpaRepository<SupersededAssetEntity, UUID> {

    List<SupersededAssetEntity> findByStateOrderByCreatedAtAsc(AssetLifecycleState state);
}

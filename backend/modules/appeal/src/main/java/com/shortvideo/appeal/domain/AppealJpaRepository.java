package com.shortvideo.appeal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AppealJpaRepository extends JpaRepository<AppealEntity, UUID> {

    List<AppealEntity> findByStateInOrderByUpdatedAtAsc(List<com.shortvideo.appeal.api.AppealState> states);
}

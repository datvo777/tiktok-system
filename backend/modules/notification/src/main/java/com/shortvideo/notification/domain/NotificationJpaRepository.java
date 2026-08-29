package com.shortvideo.notification.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationJpaRepository extends JpaRepository<NotificationEntity, UUID> {

    List<NotificationEntity> findByRecipientAccountIdOrderByCreatedAtDesc(UUID recipientAccountId);
}

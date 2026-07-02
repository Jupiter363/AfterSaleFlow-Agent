package com.example.dispute.notification.infrastructure.persistence.repository;

import com.example.dispute.notification.infrastructure.persistence.entity.NotificationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<NotificationEntity, String> {

    Optional<NotificationEntity> findByBusinessEventKeyAndRecipientId(
            String businessEventKey, String recipientId);

    Optional<NotificationEntity> findByIdAndRecipientId(String id, String recipientId);

    List<NotificationEntity> findAllByRecipientIdOrderByCreatedAtDesc(String recipientId);

    long countByRecipientIdAndReadAtIsNull(String recipientId);
}

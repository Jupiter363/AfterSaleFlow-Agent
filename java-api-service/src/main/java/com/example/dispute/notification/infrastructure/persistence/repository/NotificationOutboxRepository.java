package com.example.dispute.notification.infrastructure.persistence.repository;

import com.example.dispute.notification.infrastructure.persistence.entity.NotificationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationOutboxRepository
        extends JpaRepository<NotificationOutboxEntity, String> {

    boolean existsByBusinessEventKey(String businessEventKey);
}

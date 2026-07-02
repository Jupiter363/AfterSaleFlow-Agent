package com.example.dispute.notification.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.notification.domain.NotificationType;
import java.time.Instant;

public record NotificationView(
        String id,
        String caseId,
        String recipientId,
        ActorRole recipientRole,
        NotificationType notificationType,
        String title,
        String body,
        String deepLink,
        boolean read,
        Instant createdAt,
        Instant readAt) {}

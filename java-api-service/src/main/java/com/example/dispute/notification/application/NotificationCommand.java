package com.example.dispute.notification.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.notification.domain.NotificationType;
import java.util.Objects;

public record NotificationCommand(
        String caseId,
        String businessEventKey,
        String recipientId,
        ActorRole recipientRole,
        NotificationType notificationType,
        String title,
        String body,
        String deepLink,
        String payloadJson) {

    public NotificationCommand {
        requireText(caseId, "caseId");
        requireText(businessEventKey, "businessEventKey");
        requireText(recipientId, "recipientId");
        Objects.requireNonNull(recipientRole, "recipientRole must not be null");
        Objects.requireNonNull(notificationType, "notificationType must not be null");
        requireText(title, "title");
        requireText(body, "body");
        requireText(deepLink, "deepLink");
        requireText(payloadJson, "payloadJson");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

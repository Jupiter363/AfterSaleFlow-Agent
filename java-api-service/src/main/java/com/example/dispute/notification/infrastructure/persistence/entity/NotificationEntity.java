package com.example.dispute.notification.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.notification.domain.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notification")
public class NotificationEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "business_event_key", length = 128, nullable = false)
    private String businessEventKey;

    @Column(name = "recipient_id", length = 128, nullable = false)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_role", length = 32, nullable = false)
    private ActorRole recipientRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", length = 64, nullable = false)
    private NotificationType notificationType;

    @Column(name = "title", length = 256, nullable = false)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "deep_link", length = 512, nullable = false)
    private String deepLink;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected NotificationEntity() {}

    private NotificationEntity(String id) {
        super(id);
    }

    public static NotificationEntity create(
            String id,
            String caseId,
            String businessEventKey,
            String recipientId,
            ActorRole recipientRole,
            NotificationType notificationType,
            String title,
            String body,
            String deepLink,
            String payloadJson,
            Instant createdAt) {
        NotificationEntity entity = new NotificationEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.businessEventKey = required(businessEventKey, "businessEventKey");
        entity.recipientId = required(recipientId, "recipientId");
        entity.recipientRole =
                Objects.requireNonNull(recipientRole, "recipientRole must not be null");
        entity.notificationType =
                Objects.requireNonNull(notificationType, "notificationType must not be null");
        entity.title = required(title, "title");
        entity.body = required(body, "body");
        entity.deepLink = required(deepLink, "deepLink");
        entity.payloadJson = required(payloadJson, "payloadJson");
        entity.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        return entity;
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = Objects.requireNonNull(now, "now must not be null");
        }
    }

    public void dismiss(Instant now) {
        if (dismissedAt == null) {
            dismissedAt = Objects.requireNonNull(now, "now must not be null");
        }
    }

    public String getCaseId() {
        return caseId;
    }

    public String getBusinessEventKey() {
        return businessEventKey;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public ActorRole getRecipientRole() {
        return recipientRole;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getDeepLink() {
        return deepLink;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getDismissedAt() {
        return dismissedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

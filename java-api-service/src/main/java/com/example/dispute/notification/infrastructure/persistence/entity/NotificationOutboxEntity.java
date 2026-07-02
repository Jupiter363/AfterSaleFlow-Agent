package com.example.dispute.notification.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notification_outbox")
public class NotificationOutboxEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "business_event_key", length = 128, nullable = false)
    private String businessEventKey;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_payload_json", nullable = false, columnDefinition = "jsonb")
    private String eventPayloadJson;

    @Column(name = "outbox_status", length = 32, nullable = false)
    private String outboxStatus;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationOutboxEntity() {}

    private NotificationOutboxEntity(String id) {
        super(id);
    }

    public static NotificationOutboxEntity pending(
            String id,
            String caseId,
            String businessEventKey,
            String eventType,
            String payloadJson,
            Instant now) {
        NotificationOutboxEntity entity = new NotificationOutboxEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.businessEventKey = required(businessEventKey, "businessEventKey");
        entity.eventType = required(eventType, "eventType");
        entity.eventPayloadJson = required(payloadJson, "payloadJson");
        entity.outboxStatus = "PENDING";
        entity.availableAt = now;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

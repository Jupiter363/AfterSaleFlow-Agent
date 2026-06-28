package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "trace_id", length = 128, nullable = false)
    private String traceId;

    @Column(name = "request_id", length = 128, nullable = false)
    private String requestId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "role", length = 32, nullable = false)
    private String role;

    @Column(name = "service", length = 64, nullable = false)
    private String service;

    @Column(name = "action", length = 128, nullable = false)
    private String action;

    @Column(name = "resource_type", length = 64, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "outcome", length = 32, nullable = false)
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", nullable = false, columnDefinition = "jsonb")
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", nullable = false, columnDefinition = "jsonb")
    private String afterJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected AuditLogEntity() {}

    private AuditLogEntity(
            String id,
            String caseId,
            String traceId,
            String requestId,
            String userId,
            String role,
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson) {
        super(id);
        this.caseId = caseId;
        this.traceId = traceId;
        this.requestId = requestId;
        this.userId = userId;
        this.role = role;
        this.service = "java-api-service";
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.outcome = "SUCCESS";
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.metadataJson = "{}";
        this.createdBy = userId;
    }

    public static AuditLogEntity caseCreated(
            String id,
            String caseId,
            String traceId,
            String requestId,
            String userId,
            String role,
            String afterJson) {
        return new AuditLogEntity(
                id,
                caseId,
                traceId,
                requestId,
                userId,
                role,
                "CASE_CREATED",
                "FULFILLMENT_CASE",
                caseId,
                "{}",
                afterJson);
    }

    public static AuditLogEntity record(
            String id,
            String caseId,
            String traceId,
            String requestId,
            String userId,
            String role,
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson) {
        return new AuditLogEntity(
                id,
                caseId,
                traceId,
                requestId,
                userId,
                role,
                action,
                resourceType,
                resourceId,
                beforeJson,
                afterJson);
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getAction() {
        return action;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public String getService() {
        return service;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

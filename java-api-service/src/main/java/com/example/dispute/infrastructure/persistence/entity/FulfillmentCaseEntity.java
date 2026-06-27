package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fulfillment_case")
public class FulfillmentCaseEntity extends AbstractEntity {

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "after_sale_id", length = 64)
    private String afterSaleId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "merchant_id", length = 128, nullable = false)
    private String merchantId;

    @Column(name = "creation_idempotency_key", length = 128, nullable = false, unique = true)
    private String creationIdempotencyKey;

    @Column(name = "case_type", length = 64, nullable = false)
    private String caseType;

    @Column(name = "dispute_type", length = 64)
    private String disputeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_status", length = 64, nullable = false)
    private CaseStatus caseStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "route_type", length = 64)
    private RouteType routeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "current_workflow_id", length = 128)
    private String currentWorkflowId;

    @Column(name = "title", length = 256, nullable = false)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intake_result_json", nullable = false, columnDefinition = "jsonb")
    private String intakeResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected FulfillmentCaseEntity() {}

    private FulfillmentCaseEntity(
            String id,
            String orderId,
            String afterSaleId,
            String userId,
            String merchantId,
            String creationIdempotencyKey,
            String caseType,
            String title,
            String description,
            RiskLevel riskLevel,
            String actorId) {
        super(id);
        this.orderId = orderId;
        this.afterSaleId = afterSaleId;
        this.userId = required(userId, "userId");
        this.merchantId = required(merchantId, "merchantId");
        this.creationIdempotencyKey =
                required(creationIdempotencyKey, "creationIdempotencyKey");
        this.caseType = required(caseType, "caseType");
        this.title = required(title, "title");
        this.description = required(description, "description");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.caseStatus = CaseStatus.INTAKE_PENDING;
        this.intakeResultJson = "{}";
        this.metadataJson = "{}";
        this.createdBy = required(actorId, "actorId");
        this.updatedBy = actorId;
    }

    public static FulfillmentCaseEntity create(
            String id,
            String orderId,
            String afterSaleId,
            String userId,
            String merchantId,
            String creationIdempotencyKey,
            String caseType,
            String title,
            String description,
            RiskLevel riskLevel,
            String actorId) {
        return new FulfillmentCaseEntity(
                id,
                orderId,
                afterSaleId,
                userId,
                merchantId,
                creationIdempotencyKey,
                caseType,
                title,
                description,
                riskLevel,
                actorId);
    }

    public void completeIntake(
            String disputeType,
            CaseStatus status,
            RiskLevel riskLevel,
            String intakeResultJson,
            String actorId) {
        this.disputeType = disputeType;
        this.caseStatus = Objects.requireNonNull(status, "status must not be null");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.intakeResultJson = required(intakeResultJson, "intakeResultJson");
        this.updatedBy = required(actorId, "actorId");
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public CaseStatus getCaseStatus() {
        return caseStatus;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public RouteType getRouteType() {
        return routeType;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getAfterSaleId() {
        return afterSaleId;
    }

    public String getUserId() {
        return userId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getCaseType() {
        return caseType;
    }

    public String getDisputeType() {
        return disputeType;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getIntakeResultJson() {
        return intakeResultJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.casecore.domain.CaseSourceType;
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
@Table(name = "fulfillment_dispute_case")
public class FulfillmentCaseEntity extends AbstractEntity {

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "after_sales_id", length = 64)
    private String afterSaleId;

    @Column(name = "logistics_id", length = 64)
    private String logisticsId;

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
    @Column(name = "hearing_route", length = 64)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 32, nullable = false)
    private CaseSourceType sourceType;

    @Column(name = "source_system", length = 64)
    private String sourceSystem;

    @Column(name = "external_case_ref", length = 128)
    private String externalCaseRef;

    @Column(name = "current_room", length = 32)
    private String currentRoom;

    @Column(name = "current_deadline_at")
    private OffsetDateTime currentDeadlineAt;

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
        this.sourceType = CaseSourceType.INTAKE_CREATED;
        this.currentRoom = "INTAKE";
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

    public static FulfillmentCaseEntity create(
            String id,
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            String creationIdempotencyKey,
            String caseType,
            String title,
            String description,
            RiskLevel riskLevel,
            String actorId) {
        FulfillmentCaseEntity entity =
                new FulfillmentCaseEntity(
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
        entity.logisticsId = blankToNull(logisticsId);
        return entity;
    }

    public static FulfillmentCaseEntity imported(
            String id,
            String orderId,
            String afterSaleId,
            String logisticsId,
            String userId,
            String merchantId,
            String creationIdempotencyKey,
            String disputeType,
            String title,
            String description,
            RiskLevel riskLevel,
            CaseStatus caseStatus,
            String currentRoom,
            OffsetDateTime currentDeadlineAt,
            String sourceSystem,
            String externalCaseRef,
            String actorId) {
        FulfillmentCaseEntity entity =
                new FulfillmentCaseEntity(
                        id,
                        orderId,
                        afterSaleId,
                        userId,
                        merchantId,
                        creationIdempotencyKey,
                        "DISPUTE",
                        title,
                        description,
                        riskLevel,
                        actorId);
        entity.logisticsId = blankToNull(logisticsId);
        entity.disputeType = required(disputeType, "disputeType");
        entity.caseStatus = Objects.requireNonNull(caseStatus, "caseStatus must not be null");
        entity.sourceType = CaseSourceType.EXTERNAL_IMPORT;
        entity.sourceSystem = required(sourceSystem, "sourceSystem");
        entity.externalCaseRef = required(externalCaseRef, "externalCaseRef");
        entity.currentRoom = required(currentRoom, "currentRoom");
        if (isFullHearingLifecycle(caseStatus, currentRoom)) {
            entity.routeType = RouteType.FULL_HEARING;
        }
        entity.currentDeadlineAt = currentDeadlineAt;
        entity.intakeResultJson = "{\"potentialDispute\":true,\"missingSlots\":[],\"agentDegraded\":false,\"analyzedAt\":\"2026-07-03T00:00:00Z\"}";
        return entity;
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

    public void admitToEvidence(
            String disputeType,
            RiskLevel riskLevel,
            String intakeAnalysisJson,
            OffsetDateTime deadlineAt,
            String actorId) {
        assertIntakeCanBeConfirmed();
        this.disputeType = required(disputeType, "disputeType");
        this.caseType = "DISPUTE";
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.intakeResultJson = required(intakeAnalysisJson, "intakeAnalysisJson");
        this.caseStatus = CaseStatus.EVIDENCE_OPEN;
        this.currentRoom = "EVIDENCE";
        this.currentDeadlineAt =
                Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        this.updatedBy = required(actorId, "actorId");
    }

    public void rejectAsNotAdmissible(
            String disputeType,
            RiskLevel riskLevel,
            String intakeAnalysisJson,
            String actorId) {
        assertIntakeCanBeConfirmed();
        this.disputeType = required(disputeType, "disputeType");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.intakeResultJson = required(intakeAnalysisJson, "intakeAnalysisJson");
        this.caseStatus = CaseStatus.NOT_ADMISSIBLE;
        this.currentRoom = null;
        this.currentDeadlineAt = null;
        this.updatedBy = required(actorId, "actorId");
    }

    public void cancelIntake(String actorId, OffsetDateTime now) {
        assertIntakeCanBeConfirmed();
        this.caseStatus = CaseStatus.CANCELLED;
        this.currentRoom = null;
        this.currentDeadlineAt = null;
        this.closedAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedBy = required(actorId, "actorId");
    }

    private void assertIntakeCanBeConfirmed() {
        if (caseStatus != CaseStatus.INTAKE_PENDING
                && caseStatus != CaseStatus.INTAKE_IN_PROGRESS
                && caseStatus != CaseStatus.INTAKE_COMPLETED) {
            throw new IllegalStateException(
                    "intake cannot be confirmed from case status " + caseStatus);
        }
    }

    public void openHearing(OffsetDateTime deadlineAt, String actorId) {
        if (caseStatus != CaseStatus.EVIDENCE_OPEN
                && caseStatus != CaseStatus.EVIDENCE_SEALED) {
            throw new IllegalStateException("hearing cannot open from " + caseStatus);
        }
        routeType = RouteType.FULL_HEARING;
        caseStatus = CaseStatus.HEARING_OPEN;
        currentRoom = "HEARING";
        currentDeadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        updatedBy = required(actorId, "actorId");
    }

    public void attachHearingWorkflow(String workflowId, String actorId) {
        if (caseStatus != CaseStatus.HEARING_OPEN
                && caseStatus != CaseStatus.HEARING) {
            throw new IllegalStateException(
                    "hearing workflow cannot attach from " + caseStatus);
        }
        ensureFullHearingRoute(actorId);
        if (currentWorkflowId != null
                && !currentWorkflowId.equals(workflowId)) {
            throw new IllegalStateException(
                    "case is already controlled by another workflow");
        }
        currentWorkflowId = required(workflowId, "workflowId");
        caseStatus = CaseStatus.HEARING;
        updatedBy = required(actorId, "actorId");
    }

    public void ensureFullHearingRoute(String actorId) {
        if (routeType == RouteType.FULL_HEARING) {
            return;
        }
        if (routeType != null) {
            throw new IllegalStateException(
                    "case route " + routeType + " cannot be controlled by a hearing workflow");
        }
        if (!isFullHearingLifecycle(caseStatus, currentRoom)) {
            throw new IllegalStateException(
                    "full hearing route cannot be inferred from status "
                            + caseStatus
                            + " and room "
                            + currentRoom);
        }
        routeType = RouteType.FULL_HEARING;
        updatedBy = required(actorId, "actorId");
    }

    public void markDossierBuilt(String actorId) {
        if (caseStatus == CaseStatus.WAITING_SLOT_COMPLETION
                || caseStatus == CaseStatus.CLOSED
                || caseStatus == CaseStatus.CANCELLED) {
            throw new IllegalStateException(
                    "dossier cannot be built from case status " + caseStatus);
        }
        caseStatus = CaseStatus.DOSSIER_BUILT;
        updatedBy = required(actorId, "actorId");
    }

    public void applyRoute(RouteType routeType, String actorId) {
        if (caseStatus != CaseStatus.DOSSIER_BUILT) {
            throw new IllegalStateException(
                    "case cannot be routed from status " + caseStatus);
        }
        this.routeType = Objects.requireNonNull(routeType, "routeType must not be null");
        this.caseStatus = CaseStatus.ROUTED;
        this.updatedBy = required(actorId, "actorId");
    }

    public void startHearing(String workflowId, String actorId) {
        if (caseStatus != CaseStatus.ROUTED
                || routeType != RouteType.FULL_HEARING) {
            throw new IllegalStateException(
                    "hearing cannot start from status "
                            + caseStatus
                            + " and route "
                            + routeType);
        }
        this.currentWorkflowId = required(workflowId, "workflowId");
        this.caseStatus = CaseStatus.HEARING;
        this.updatedBy = required(actorId, "actorId");
    }

    public void markRemedyPlanned(String actorId) {
        if (caseStatus != CaseStatus.ROUTED && caseStatus != CaseStatus.HEARING) {
            throw new IllegalStateException(
                    "remedy cannot be planned from status " + caseStatus);
        }
        this.caseStatus = CaseStatus.REMEDY_PLANNED;
        this.updatedBy = required(actorId, "actorId");
    }

    public void waitForHumanReview(String actorId) {
        if (caseStatus != CaseStatus.REMEDY_PLANNED
                && caseStatus != CaseStatus.WAITING_EVIDENCE) {
            throw new IllegalStateException(
                    "review cannot start from status " + caseStatus);
        }
        caseStatus = CaseStatus.WAITING_HUMAN_REVIEW;
        updatedBy = required(actorId, "actorId");
    }

    public void applyReviewOutcome(
            com.example.dispute.domain.model.ApprovalDecisionType decision,
            String actorId) {
        if (caseStatus != CaseStatus.WAITING_HUMAN_REVIEW) {
            throw new IllegalStateException(
                    "review cannot complete from status " + caseStatus);
        }
        caseStatus =
                switch (decision) {
                    case APPROVE, MODIFY_AND_APPROVE ->
                            CaseStatus.APPROVED_FOR_EXECUTION;
                    case REQUEST_MORE_EVIDENCE -> CaseStatus.WAITING_EVIDENCE;
                    case REJECT, ESCALATE_MANUAL -> CaseStatus.MANUAL_HANDOFF;
                };
        updatedBy = required(actorId, "actorId");
    }

    public void beginExecution(String actorId) {
        if (caseStatus == CaseStatus.EXECUTING) {
            updatedBy = required(actorId, "actorId");
            return;
        }
        if (caseStatus != CaseStatus.APPROVED_FOR_EXECUTION) {
            throw new IllegalStateException(
                    "execution cannot start from status " + caseStatus);
        }
        caseStatus = CaseStatus.EXECUTING;
        updatedBy = required(actorId, "actorId");
    }

    public void close(String actorId) {
        if (caseStatus == CaseStatus.CLOSED) {
            return;
        }
        if (caseStatus != CaseStatus.EXECUTING) {
            throw new IllegalStateException(
                    "case cannot close from status " + caseStatus);
        }
        caseStatus = CaseStatus.CLOSED;
        closedAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedBy = required(actorId, "actorId");
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isFullHearingLifecycle(CaseStatus status, String room) {
        if ("HEARING".equals(room)) {
            return true;
        }
        return status == CaseStatus.HEARING
                || status == CaseStatus.HEARING_OPEN
                || status == CaseStatus.WAITING_EVIDENCE
                || status == CaseStatus.SETTLEMENT_PENDING
                || status == CaseStatus.DRAFT_READY
                || status == CaseStatus.DELIBERATION_RUNNING;
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

    public long getVersion() {
        return version;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getAfterSaleId() {
        return afterSaleId;
    }

    public String getLogisticsId() {
        return logisticsId;
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

    public String getCurrentWorkflowId() {
        return currentWorkflowId;
    }

    public OffsetDateTime getClosedAt() {
        return closedAt;
    }

    public CaseSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getExternalCaseRef() {
        return externalCaseRef;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public OffsetDateTime getCurrentDeadlineAt() {
        return currentDeadlineAt;
    }
}

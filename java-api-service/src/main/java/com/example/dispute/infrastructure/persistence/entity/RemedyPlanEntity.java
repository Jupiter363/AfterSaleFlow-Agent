package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "remedy_plan")
public class RemedyPlanEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "adjudication_draft_id", length = 64)
    private String adjudicationDraftId;

    @Column(name = "plan_version", nullable = false)
    private int planVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_route", length = 64, nullable = false)
    private RouteType sourceRoute;

    @Column(name = "plan_status", length = 32, nullable = false)
    private String planStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions_json", nullable = false, columnDefinition = "jsonb")
    private String actionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preconditions_json", nullable = false, columnDefinition = "jsonb")
    private String preconditionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "notification_plan_json",
            nullable = false,
            columnDefinition = "jsonb")
    private String notificationPlanJson;

    @Column(name = "requires_human_review", nullable = false)
    private boolean requiresHumanReview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected RemedyPlanEntity() {}

    private RemedyPlanEntity(String id) {
        super(id);
    }

    public static RemedyPlanEntity pendingApproval(
            String id,
            String caseId,
            String adjudicationDraftId,
            int planVersion,
            RouteType sourceRoute,
            RiskLevel riskLevel,
            String actionsJson,
            String preconditionsJson,
            String notificationPlanJson,
            String actorId) {
        RemedyPlanEntity plan = new RemedyPlanEntity(id);
        plan.caseId = caseId;
        plan.adjudicationDraftId = adjudicationDraftId;
        plan.planVersion = planVersion;
        plan.sourceRoute = sourceRoute;
        plan.planStatus = "PENDING_APPROVAL";
        plan.riskLevel = riskLevel;
        plan.totalAmount = BigDecimal.ZERO.setScale(2);
        plan.currency = "CNY";
        plan.actionsJson = actionsJson;
        plan.preconditionsJson = preconditionsJson;
        plan.notificationPlanJson = notificationPlanJson;
        plan.requiresHumanReview = true;
        plan.createdBy = actorId;
        plan.updatedBy = actorId;
        return plan;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getCaseId() { return caseId; }
    public String getAdjudicationDraftId() { return adjudicationDraftId; }
    public int getPlanVersion() { return planVersion; }
    public RouteType getSourceRoute() { return sourceRoute; }
    public String getPlanStatus() { return planStatus; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getActionsJson() { return actionsJson; }
    public String getPreconditionsJson() { return preconditionsJson; }
    public String getNotificationPlanJson() { return notificationPlanJson; }
    public boolean isRequiresHumanReview() { return requiresHumanReview; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

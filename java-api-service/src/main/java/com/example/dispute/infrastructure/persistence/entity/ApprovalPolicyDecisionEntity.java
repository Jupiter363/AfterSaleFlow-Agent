package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.review.domain.ApprovalPolicyDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "approval_policy_decision")
public class ApprovalPolicyDecisionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "plan_id", length = 64, nullable = false)
    private String planId;
    @Column(name = "policy_version", length = 64, nullable = false)
    private String policyVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;
    @Column(name = "required_reviewer_role", length = 32, nullable = false)
    private String requiredReviewerRole;
    @Column(name = "required_review_count", nullable = false)
    private int requiredReviewCount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_actions_json", nullable = false, columnDefinition = "jsonb")
    private String allowedActionsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forbidden_actions_json", nullable = false, columnDefinition = "jsonb")
    private String forbiddenActionsJson;
    @Column(name = "escalation_reason", columnDefinition = "text")
    private String escalationReason;
    @Column(name = "auto_approve", nullable = false)
    private boolean autoApprove;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected ApprovalPolicyDecisionEntity() {}

    private ApprovalPolicyDecisionEntity(String id) {
        super(id);
    }

    public static ApprovalPolicyDecisionEntity record(
            String id,
            String caseId,
            String planId,
            RiskLevel riskLevel,
            ApprovalPolicyDecision decision,
            String allowedActionsJson,
            String forbiddenActionsJson,
            String actorId) {
        ApprovalPolicyDecisionEntity entity =
                new ApprovalPolicyDecisionEntity(id);
        entity.caseId = caseId;
        entity.planId = planId;
        entity.policyVersion = decision.policyVersion();
        entity.riskLevel = riskLevel;
        entity.requiredReviewerRole = decision.requiredRole();
        entity.requiredReviewCount = decision.requiredReviewCount();
        entity.allowedActionsJson = allowedActionsJson;
        entity.forbiddenActionsJson = forbiddenActionsJson;
        entity.escalationReason =
                decision.riskFlags().isEmpty()
                        ? null
                        : String.join(",", decision.riskFlags());
        entity.autoApprove = decision.autoApprove();
        entity.createdBy = actorId;
        return entity;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getPolicyVersion() {
        return policyVersion;
    }
}

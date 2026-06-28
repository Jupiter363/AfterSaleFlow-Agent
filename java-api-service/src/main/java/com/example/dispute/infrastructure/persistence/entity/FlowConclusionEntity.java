package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "flow_conclusion")
public class FlowConclusionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, unique = true)
    private String caseId;

    @Column(name = "route_decision_id", length = 64, nullable = false, unique = true)
    private String routeDecisionId;

    @Column(name = "conclusion_type", length = 64, nullable = false)
    private String conclusionType;

    @Column(name = "conclusion_status", length = 64, nullable = false)
    private String conclusionStatus;

    @Column(name = "conclusion_code", length = 128, nullable = false)
    private String conclusionCode;

    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommended_actions_json", nullable = false, columnDefinition = "jsonb")
    private String recommendedActionsJson;

    @Column(name = "policy_rule_id", length = 64)
    private String policyRuleId;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "requires_remedy_planning", nullable = false)
    private boolean requiresRemedyPlanning;

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

    protected FlowConclusionEntity() {}

    private FlowConclusionEntity(
            String id,
            String caseId,
            String routeDecisionId,
            String conclusionType,
            String conclusionCode,
            String summary,
            String recommendedActionsJson,
            String policyRuleId,
            Integer policyVersion,
            RiskLevel riskLevel,
            String actorId) {
        super(id);
        this.caseId = caseId;
        this.routeDecisionId = routeDecisionId;
        this.conclusionType = conclusionType;
        this.conclusionStatus = "READY_FOR_REMEDY_PLANNING";
        this.conclusionCode = conclusionCode;
        this.summary = summary;
        this.recommendedActionsJson = recommendedActionsJson;
        this.policyRuleId = policyRuleId;
        this.policyVersion = policyVersion;
        this.riskLevel = riskLevel;
        this.requiresRemedyPlanning = true;
        this.requiresHumanReview = true;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public static FlowConclusionEntity readyForRemedyPlanning(
            String id,
            String caseId,
            String routeDecisionId,
            String conclusionType,
            String conclusionCode,
            String summary,
            String recommendedActionsJson,
            String policyRuleId,
            Integer policyVersion,
            RiskLevel riskLevel,
            String actorId) {
        return new FlowConclusionEntity(
                id,
                caseId,
                routeDecisionId,
                conclusionType,
                conclusionCode,
                summary,
                recommendedActionsJson,
                policyRuleId,
                policyVersion,
                riskLevel,
                actorId);
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

    public String getConclusionType() {
        return conclusionType;
    }

    public String getConclusionStatus() {
        return conclusionStatus;
    }

    public String getConclusionCode() {
        return conclusionCode;
    }

    public String getSummary() {
        return summary;
    }

    public String getRecommendedActionsJson() {
        return recommendedActionsJson;
    }

    public String getPolicyRuleId() {
        return policyRuleId;
    }

    public Integer getPolicyVersion() {
        return policyVersion;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public boolean isRequiresRemedyPlanning() {
        return requiresRemedyPlanning;
    }

    public boolean isRequiresHumanReview() {
        return requiresHumanReview;
    }
}

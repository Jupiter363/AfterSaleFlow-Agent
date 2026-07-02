package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "adjudication_draft")
public class AdjudicationDraftEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "hearing_state_id", length = 64)
    private String hearingStateId;
    @Column(name = "draft_version", nullable = false)
    private int draftVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fact_findings_json", nullable = false, columnDefinition = "jsonb")
    private String factFindingsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_assessment_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceAssessmentJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_application_json", nullable = false, columnDefinition = "jsonb")
    private String policyApplicationJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reviewer_attention_json", nullable = false, columnDefinition = "jsonb")
    private String reviewerAttentionJson;
    @Column(name = "recommended_decision", length = 128, nullable = false)
    private String recommendedDecision;
    @Column(name = "confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;
    @Column(name = "draft_text", nullable = false, columnDefinition = "text")
    private String draftText;
    @Column(name = "created_by_agent", length = 128, nullable = false)
    private String createdByAgent;
    @Column(name = "created_by_agent_run_id", length = 64)
    private String createdByAgentRunId;
    @Column(name = "draft_status", length = 32, nullable = false)
    private String draftStatus;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected AdjudicationDraftEntity() {}

    private AdjudicationDraftEntity(String id) { super(id); }

    public static AdjudicationDraftEntity create(
            String id, String caseId, String hearingStateId, int version,
            String factFindingsJson, String evidenceAssessmentJson,
            String policyApplicationJson, String reviewerAttentionJson,
            String recommendedDecision, BigDecimal confidence, String draftText,
            String agent, String draftStatus, String actorId) {
        return create(
                id,
                caseId,
                hearingStateId,
                version,
                factFindingsJson,
                evidenceAssessmentJson,
                policyApplicationJson,
                reviewerAttentionJson,
                recommendedDecision,
                confidence,
                draftText,
                agent,
                null,
                draftStatus,
                actorId);
    }

    public static AdjudicationDraftEntity create(
            String id, String caseId, String hearingStateId, int version,
            String factFindingsJson, String evidenceAssessmentJson,
            String policyApplicationJson, String reviewerAttentionJson,
            String recommendedDecision, BigDecimal confidence, String draftText,
            String agent, String agentRunId, String draftStatus, String actorId) {
        AdjudicationDraftEntity draft = new AdjudicationDraftEntity(id);
        draft.caseId = caseId;
        draft.hearingStateId = hearingStateId;
        draft.draftVersion = version;
        draft.factFindingsJson = factFindingsJson;
        draft.evidenceAssessmentJson = evidenceAssessmentJson;
        draft.policyApplicationJson = policyApplicationJson;
        draft.reviewerAttentionJson = reviewerAttentionJson;
        draft.recommendedDecision = recommendedDecision;
        draft.confidence = confidence;
        draft.draftText = draftText;
        draft.createdByAgent = agent;
        draft.createdByAgentRunId = agentRunId;
        draft.draftStatus = draftStatus;
        draft.createdBy = actorId;
        draft.updatedBy = actorId;
        return draft;
    }

    @PrePersist void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }
    @PreUpdate void preUpdate() { updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }
    public String getCaseId() { return caseId; }
    public int getDraftVersion() { return draftVersion; }
    public String getRecommendedDecision() { return recommendedDecision; }
    public BigDecimal getConfidence() { return confidence; }
    public String getDraftText() { return draftText; }
    public String getDraftStatus() { return draftStatus; }
    public String getFactFindingsJson() { return factFindingsJson; }
    public String getEvidenceAssessmentJson() { return evidenceAssessmentJson; }
    public String getPolicyApplicationJson() { return policyApplicationJson; }
    public String getReviewerAttentionJson() { return reviewerAttentionJson; }
    public String getCreatedByAgentRunId() { return createdByAgentRunId; }
}

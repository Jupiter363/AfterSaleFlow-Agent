package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.review.domain.ReviewPacketVersions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "review_packet")
public class ReviewPacketEntity extends AbstractEntity {
    @Column(name = "case_id", length = 64, nullable = false) private String caseId;
    @Column(name = "plan_id", length = 64, nullable = false) private String planId;
    @Column(name = "packet_version", nullable = false) private int packetVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "case_summary_json", nullable = false, columnDefinition = "jsonb") private String caseSummaryJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "claims_json", nullable = false, columnDefinition = "jsonb") private String claimsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "issues_json", nullable = false, columnDefinition = "jsonb") private String issuesJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_matrix_json", nullable = false, columnDefinition = "jsonb") private String evidenceMatrixJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "draft_json", nullable = false, columnDefinition = "jsonb") private String draftJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "remedy_json", nullable = false, columnDefinition = "jsonb") private String remedyJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "risk_flags_json", nullable = false, columnDefinition = "jsonb") private String riskFlagsJson;
    @Column(name = "packet_status", length = 32, nullable = false) private String packetStatus;
    @Column(name = "case_version", nullable = false) private long caseVersion;
    @Column(name = "dossier_version", nullable = false) private int dossierVersion;
    @Column(name = "issue_version", nullable = false) private int issueVersion;
    @Column(name = "adjudication_draft_version", nullable = false) private int adjudicationDraftVersion;
    @Column(name = "deliberation_report_version", nullable = false) private int deliberationReportVersion;
    @Column(name = "remedy_plan_version", nullable = false) private int remedyPlanVersion;
    @Column(name = "ruleset_version", length = 64, nullable = false) private String rulesetVersion;
    @Column(name = "prompt_version", length = 64, nullable = false) private String promptVersion;
    @Column(name = "skill_version", length = 64, nullable = false) private String skillVersion;
    @Column(name = "profile_version", length = 64, nullable = false) private String profileVersion;
    @Column(name = "action_hash", length = 128, nullable = false) private String actionHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "agent_run_refs_json", nullable = false, columnDefinition = "jsonb") private String agentRunRefsJson;
    @Column(name = "frozen", nullable = false) private boolean frozen;
    @Column(name = "frozen_at", nullable = false) private OffsetDateTime frozenAt;
    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false) private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false) private String updatedBy;

    protected ReviewPacketEntity() {}
    private ReviewPacketEntity(String id) { super(id); }
    public static ReviewPacketEntity create(
            String id, String caseId, String planId, int version,
            String caseSummaryJson, String claimsJson, String issuesJson,
            String evidenceMatrixJson, String draftJson, String remedyJson,
            String riskFlagsJson, String actorId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return createFrozen(
                id,
                caseId,
                planId,
                version,
                new ReviewPacketVersions(
                        1,
                        1,
                        1,
                        1,
                        0,
                        1,
                        "legacy-ruleset",
                        "legacy-prompt",
                        "legacy-skill",
                        "legacy-profile"),
                "LEGACY_" + id,
                now,
                now.plusDays(7),
                caseSummaryJson,
                claimsJson,
                issuesJson,
                evidenceMatrixJson,
                draftJson,
                remedyJson,
                riskFlagsJson,
                actorId);
    }

    public static ReviewPacketEntity createFrozen(
            String id,
            String caseId,
            String planId,
            int version,
            ReviewPacketVersions versions,
            String actionHash,
            OffsetDateTime frozenAt,
            OffsetDateTime expiresAt,
            String caseSummaryJson,
            String claimsJson,
            String issuesJson,
            String evidenceMatrixJson,
            String draftJson,
            String remedyJson,
            String riskFlagsJson,
            String actorId) {
        return createFrozen(
                id,
                caseId,
                planId,
                version,
                versions,
                actionHash,
                frozenAt,
                expiresAt,
                "[]",
                caseSummaryJson,
                claimsJson,
                issuesJson,
                evidenceMatrixJson,
                draftJson,
                remedyJson,
                riskFlagsJson,
                actorId);
    }

    public static ReviewPacketEntity createFrozen(
            String id,
            String caseId,
            String planId,
            int version,
            ReviewPacketVersions versions,
            String actionHash,
            OffsetDateTime frozenAt,
            OffsetDateTime expiresAt,
            String agentRunRefsJson,
            String caseSummaryJson,
            String claimsJson,
            String issuesJson,
            String evidenceMatrixJson,
            String draftJson,
            String remedyJson,
            String riskFlagsJson,
            String actorId) {
        ReviewPacketEntity packet = new ReviewPacketEntity(id);
        packet.caseId=caseId; packet.planId=planId; packet.packetVersion=version;
        packet.caseSummaryJson=caseSummaryJson; packet.claimsJson=claimsJson;
        packet.issuesJson=issuesJson; packet.evidenceMatrixJson=evidenceMatrixJson;
        packet.draftJson=draftJson; packet.remedyJson=remedyJson;
        packet.riskFlagsJson=riskFlagsJson; packet.packetStatus="FROZEN";
        packet.caseVersion=versions.caseVersion();
        packet.dossierVersion=versions.dossierVersion();
        packet.issueVersion=versions.issueVersion();
        packet.adjudicationDraftVersion=versions.adjudicationDraftVersion();
        packet.deliberationReportVersion=versions.deliberationReportVersion();
        packet.remedyPlanVersion=versions.remedyPlanVersion();
        packet.rulesetVersion=versions.rulesetVersion();
        packet.promptVersion=versions.promptVersion();
        packet.skillVersion=versions.skillVersion();
        packet.profileVersion=versions.profileVersion();
        packet.actionHash=actionHash;
        packet.agentRunRefsJson=agentRunRefsJson;
        packet.frozen=true;
        packet.frozenAt=frozenAt;
        packet.expiresAt=expiresAt;
        packet.createdBy=actorId; packet.updatedBy=actorId; return packet;
    }
    @PrePersist void prePersist(){createdAt=OffsetDateTime.now(ZoneOffset.UTC);updatedAt=createdAt;}
    @PreUpdate
    void preUpdate(){
        // A reviewer must always see the exact snapshot later executed.
        if (frozen) {
            throw new IllegalStateException("frozen review packet cannot be mutated");
        }
        updatedAt=OffsetDateTime.now(ZoneOffset.UTC);
    }
    public String getCaseId(){return caseId;} public String getPlanId(){return planId;}
    public int getPacketVersion(){return packetVersion;} public String getCaseSummaryJson(){return caseSummaryJson;}
    public String getClaimsJson(){return claimsJson;} public String getIssuesJson(){return issuesJson;}
    public String getEvidenceMatrixJson(){return evidenceMatrixJson;} public String getDraftJson(){return draftJson;}
    public String getRemedyJson(){return remedyJson;} public String getRiskFlagsJson(){return riskFlagsJson;}
    public String getPacketStatus(){return packetStatus;}
    public long getCaseVersion(){return caseVersion;}
    public int getDossierVersion(){return dossierVersion;}
    public int getIssueVersion(){return issueVersion;}
    public int getAdjudicationDraftVersion(){return adjudicationDraftVersion;}
    public int getDeliberationReportVersion(){return deliberationReportVersion;}
    public int getRemedyPlanVersion(){return remedyPlanVersion;}
    public String getRulesetVersion(){return rulesetVersion;}
    public String getPromptVersion(){return promptVersion;}
    public String getSkillVersion(){return skillVersion;}
    public String getProfileVersion(){return profileVersion;}
    public String getActionHash(){return actionHash;}
    public String getAgentRunRefsJson(){return agentRunRefsJson;}
    public boolean isFrozen(){return frozen;}
    public OffsetDateTime getFrozenAt(){return frozenAt;}
    public OffsetDateTime getExpiresAt(){return expiresAt;}
}

package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Exact immutable proposal, jury report, or V2 adjudication draft. */
@Entity
@Table(name = "hearing_flow_artifact")
public class HearingFlowArtifactEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "flow_instance_id", length = 64, nullable = false)
    private String flowInstanceId;

    @Column(name = "trial_dossier_id", length = 64, nullable = false)
    private String trialDossierId;

    @Column(name = "trial_dossier_hash", length = 64, nullable = false)
    private String trialDossierHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", length = 32, nullable = false)
    private HearingArtifactType artifactType;

    @Column(name = "schema_version", length = 64, nullable = false)
    private String schemaVersion;

    @Column(name = "proposal_id", length = 64)
    private String proposalId;

    @Column(name = "proposal_content_hash", length = 64)
    private String proposalContentHash;

    @Column(name = "report_id", length = 64)
    private String reportId;

    @Column(name = "report_content_hash", length = 64)
    private String reportContentHash;

    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "agent_run_id", length = 64, nullable = false)
    private String agentRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected HearingFlowArtifactEntity() {}

    private HearingFlowArtifactEntity(String id) {
        super(id);
    }

    public static HearingFlowArtifactEntity judgeProposal(
            String proposalId,
            String caseId,
            String flowInstanceId,
            String trialDossierId,
            String trialDossierHash,
            String contentHash,
            String payloadJson,
            String agentRunId,
            Instant now,
            String actorId) {
        return frozen(
                proposalId,
                caseId,
                flowInstanceId,
                trialDossierId,
                trialDossierHash,
                HearingArtifactType.JUDGE_PROPOSAL,
                null,
                null,
                null,
                null,
                contentHash,
                payloadJson,
                agentRunId,
                now,
                actorId);
    }

    public static HearingFlowArtifactEntity juryReviewReport(
            String reportId,
            String caseId,
            String flowInstanceId,
            String trialDossierId,
            String trialDossierHash,
            String proposalId,
            String proposalContentHash,
            String contentHash,
            String payloadJson,
            String agentRunId,
            Instant now,
            String actorId) {
        return frozen(
                reportId,
                caseId,
                flowInstanceId,
                trialDossierId,
                trialDossierHash,
                HearingArtifactType.JURY_REVIEW_REPORT,
                proposalId,
                proposalContentHash,
                null,
                null,
                contentHash,
                payloadJson,
                agentRunId,
                now,
                actorId);
    }

    public static HearingFlowArtifactEntity adjudicationDraft(
            String draftId,
            String caseId,
            String flowInstanceId,
            String trialDossierId,
            String trialDossierHash,
            String proposalId,
            String proposalContentHash,
            String reportId,
            String reportContentHash,
            String contentHash,
            String payloadJson,
            String agentRunId,
            Instant now,
            String actorId) {
        return frozen(
                draftId,
                caseId,
                flowInstanceId,
                trialDossierId,
                trialDossierHash,
                HearingArtifactType.ADJUDICATION_DRAFT,
                proposalId,
                proposalContentHash,
                reportId,
                reportContentHash,
                contentHash,
                payloadJson,
                agentRunId,
                now,
                actorId);
    }

    private static HearingFlowArtifactEntity frozen(
            String id,
            String caseId,
            String flowInstanceId,
            String trialDossierId,
            String trialDossierHash,
            HearingArtifactType artifactType,
            String proposalId,
            String proposalContentHash,
            String reportId,
            String reportContentHash,
            String contentHash,
            String payloadJson,
            String agentRunId,
            Instant now,
            String actorId) {
        requireParentShape(
                artifactType,
                proposalId,
                proposalContentHash,
                reportId,
                reportContentHash);
        HearingFlowArtifactEntity entity = new HearingFlowArtifactEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.flowInstanceId = required(flowInstanceId, "flowInstanceId");
        entity.trialDossierId = required(trialDossierId, "trialDossierId");
        entity.trialDossierHash = requiredHash(trialDossierHash, "trialDossierHash");
        entity.artifactType = artifactType;
        entity.schemaVersion = artifactType.schemaVersion();
        entity.proposalId = proposalId;
        entity.proposalContentHash = proposalContentHash;
        entity.reportId = reportId;
        entity.reportContentHash = reportContentHash;
        entity.contentHash = requiredHash(contentHash, "contentHash");
        entity.payloadJson = required(payloadJson, "payloadJson");
        entity.agentRunId = required(agentRunId, "agentRunId");
        entity.createdAt = required(now, "now");
        entity.createdBy = required(actorId, "actorId");
        return entity;
    }

    public String getCaseId() { return caseId; }
    public String getFlowInstanceId() { return flowInstanceId; }
    public String getTrialDossierId() { return trialDossierId; }
    public String getTrialDossierHash() { return trialDossierHash; }
    public HearingArtifactType getArtifactType() { return artifactType; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getProposalId() { return proposalId; }
    public String getProposalContentHash() { return proposalContentHash; }
    public String getReportId() { return reportId; }
    public String getReportContentHash() { return reportContentHash; }
    public String getContentHash() { return contentHash; }
    public String getPayloadJson() { return payloadJson; }
    public String getAgentRunId() { return agentRunId; }

    private static void requireParentShape(
            HearingArtifactType type,
            String proposalId,
            String proposalHash,
            String reportId,
            String reportHash) {
        if (type == null) {
            throw new IllegalArgumentException("artifactType must not be null");
        }
        boolean proposalBound = present(proposalId) && validHash(proposalHash);
        boolean reportBound = present(reportId) && validHash(reportHash);
        boolean valid =
                switch (type) {
                    case JUDGE_PROPOSAL -> !present(proposalId)
                            && !present(proposalHash)
                            && !present(reportId)
                            && !present(reportHash);
                    case JURY_REVIEW_REPORT -> proposalBound
                            && !present(reportId)
                            && !present(reportHash);
                    case ADJUDICATION_DRAFT -> proposalBound && reportBound;
                };
        if (!valid) {
            throw new IllegalArgumentException("artifact parent id/hash chain is incomplete");
        }
    }

    private static boolean validHash(String value) {
        return present(value) && value.matches("[0-9a-f]{64}");
    }

    private static String requiredHash(String value, String field) {
        String hash = required(value, field);
        if (!validHash(hash)) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
        return hash;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String required(String value, String field) {
        if (!present(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}

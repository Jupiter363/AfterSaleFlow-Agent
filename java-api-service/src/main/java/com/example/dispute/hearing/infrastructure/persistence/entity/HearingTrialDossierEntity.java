package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable trial_dossier.v1 snapshot consumed by every adjudication operation. */
@Entity
@Table(name = "hearing_trial_dossier")
public class HearingTrialDossierEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "flow_instance_id", length = 64, nullable = false, unique = true)
    private String flowInstanceId;
    @Column(name = "schema_version", length = 32, nullable = false)
    private String schemaVersion;
    @Column(name = "case_matrix_version", nullable = false)
    private int caseMatrixVersion;
    @Column(name = "case_matrix_hash", length = 64, nullable = false)
    private String caseMatrixHash;
    @Column(name = "evidence_matrix_version", nullable = false)
    private int evidenceMatrixVersion;
    @Column(name = "evidence_matrix_hash", length = 64, nullable = false)
    private String evidenceMatrixHash;
    @Column(name = "question_set_id", length = 64, nullable = false)
    private String questionSetId;
    @Column(name = "request_set_id", length = 64, nullable = false)
    private String requestSetId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;
    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected HearingTrialDossierEntity() {}

    private HearingTrialDossierEntity(String id) {
        super(id);
    }

    public static HearingTrialDossierEntity frozen(
            String id,
            String caseId,
            String flowInstanceId,
            int caseMatrixVersion,
            String caseMatrixHash,
            int evidenceMatrixVersion,
            String evidenceMatrixHash,
            String questionSetId,
            String requestSetId,
            String payloadJson,
            String contentHash,
            Instant now,
            String actorId) {
        if (caseMatrixVersion < 1 || evidenceMatrixVersion < 1) {
            throw new IllegalArgumentException("matrix versions must be positive");
        }
        HearingTrialDossierEntity entity = new HearingTrialDossierEntity(id);
        entity.caseId = required(caseId, "caseId");
        entity.flowInstanceId = required(flowInstanceId, "flowInstanceId");
        entity.schemaVersion = "trial_dossier.v1";
        entity.caseMatrixVersion = caseMatrixVersion;
        entity.caseMatrixHash = requiredHash(caseMatrixHash, "caseMatrixHash");
        entity.evidenceMatrixVersion = evidenceMatrixVersion;
        entity.evidenceMatrixHash = requiredHash(evidenceMatrixHash, "evidenceMatrixHash");
        entity.questionSetId = required(questionSetId, "questionSetId");
        entity.requestSetId = required(requestSetId, "requestSetId");
        entity.payloadJson = required(payloadJson, "payloadJson");
        entity.contentHash = requiredHash(contentHash, "contentHash");
        entity.frozenAt = now;
        entity.createdAt = now;
        entity.createdBy = actorId;
        return entity;
    }

    public String getCaseId() { return caseId; }
    public String getFlowInstanceId() { return flowInstanceId; }
    public String getSchemaVersion() { return schemaVersion; }
    public int getCaseMatrixVersion() { return caseMatrixVersion; }
    public String getCaseMatrixHash() { return caseMatrixHash; }
    public int getEvidenceMatrixVersion() { return evidenceMatrixVersion; }
    public String getEvidenceMatrixHash() { return evidenceMatrixHash; }
    public String getQuestionSetId() { return questionSetId; }
    public String getRequestSetId() { return requestSetId; }
    public String getPayloadJson() { return payloadJson; }
    public String getContentHash() { return contentHash; }
    public Instant getFrozenAt() { return frozenAt; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requiredHash(String value, String field) {
        String hash = required(value, field);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
        return hash;
    }
}

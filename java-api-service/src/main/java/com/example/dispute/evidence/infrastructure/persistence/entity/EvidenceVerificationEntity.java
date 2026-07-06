package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "evidence_verification")
public class EvidenceVerificationEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "evidence_id", length = 64, nullable = false)
    private String evidenceId;
    @Column(name = "verification_version", nullable = false)
    private int verificationVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 32, nullable = false)
    private EvidenceVerificationStatus verificationStatus;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deterministic_checks_json", nullable = false, columnDefinition = "jsonb")
    private String deterministicChecksJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_findings_json", nullable = false, columnDefinition = "jsonb")
    private String agentFindingsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons_json", nullable = false, columnDefinition = "jsonb")
    private String reasonsJson;
    @Column(name = "requires_human_review", nullable = false)
    private boolean requiresHumanReview;
    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;
    @Column(name = "verified_by", length = 128, nullable = false)
    private String verifiedBy;
    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "trace_id", length = 128)
    private String traceId;

    protected EvidenceVerificationEntity() {}
    private EvidenceVerificationEntity(String id) { super(id); }

    public static EvidenceVerificationEntity create(
            String id, String caseId, String evidenceId, int version,
            EvidenceVerificationStatus status, String checksJson, String agentJson,
            String reasonsJson, boolean requiresHumanReview, Instant verifiedAt,
            String verifiedBy, String traceId) {
        EvidenceVerificationEntity entity = new EvidenceVerificationEntity(id);
        entity.caseId = caseId;
        entity.evidenceId = evidenceId;
        entity.verificationVersion = version;
        entity.verificationStatus = Objects.requireNonNull(status);
        entity.deterministicChecksJson = checksJson;
        entity.agentFindingsJson = agentJson;
        entity.reasonsJson = reasonsJson;
        entity.requiresHumanReview = requiresHumanReview;
        entity.verifiedAt = verifiedAt;
        entity.verifiedBy = verifiedBy;
        entity.createdAt = verifiedAt;
        entity.traceId = traceId;
        return entity;
    }

    public String getEvidenceId() { return evidenceId; }
    public int getVerificationVersion() { return verificationVersion; }
    public EvidenceVerificationStatus getVerificationStatus() { return verificationStatus; }
    public String getDeterministicChecksJson() { return deterministicChecksJson; }
    public String getAgentFindingsJson() { return agentFindingsJson; }
    public String getReasonsJson() { return reasonsJson; }
    public boolean isRequiresHumanReview() { return requiresHumanReview; }
    public Instant getVerifiedAt() { return verifiedAt; }
}

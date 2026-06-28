package com.example.dispute.infrastructure.persistence.entity;

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
        ReviewPacketEntity packet = new ReviewPacketEntity(id);
        packet.caseId=caseId; packet.planId=planId; packet.packetVersion=version;
        packet.caseSummaryJson=caseSummaryJson; packet.claimsJson=claimsJson;
        packet.issuesJson=issuesJson; packet.evidenceMatrixJson=evidenceMatrixJson;
        packet.draftJson=draftJson; packet.remedyJson=remedyJson;
        packet.riskFlagsJson=riskFlagsJson; packet.packetStatus="READY";
        packet.createdBy=actorId; packet.updatedBy=actorId; return packet;
    }
    @PrePersist void prePersist(){createdAt=OffsetDateTime.now(ZoneOffset.UTC);updatedAt=createdAt;}
    @PreUpdate void preUpdate(){updatedAt=OffsetDateTime.now(ZoneOffset.UTC);}
    public String getCaseId(){return caseId;} public String getPlanId(){return planId;}
    public int getPacketVersion(){return packetVersion;} public String getCaseSummaryJson(){return caseSummaryJson;}
    public String getClaimsJson(){return claimsJson;} public String getIssuesJson(){return issuesJson;}
    public String getEvidenceMatrixJson(){return evidenceMatrixJson;} public String getDraftJson(){return draftJson;}
    public String getRemedyJson(){return remedyJson;} public String getRiskFlagsJson(){return riskFlagsJson;}
    public String getPacketStatus(){return packetStatus;}
}

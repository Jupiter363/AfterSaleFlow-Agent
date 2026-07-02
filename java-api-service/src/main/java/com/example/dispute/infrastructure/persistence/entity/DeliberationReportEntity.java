package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "deliberation_report")
public class DeliberationReportEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "report_version", nullable = false)
    private int reportVersion;

    @Column(name = "draft_id", length = 64, nullable = false)
    private String draftId;

    @Column(name = "frozen_dossier_version", nullable = false)
    private int frozenDossierVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "panel_result_json", nullable = false, columnDefinition = "jsonb")
    private String panelResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "major_risks_json", nullable = false, columnDefinition = "jsonb")
    private String majorRisksJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consensus_json", nullable = false, columnDefinition = "jsonb")
    private String consensusJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disagreements_json", nullable = false, columnDefinition = "jsonb")
    private String disagreementsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "recommended_revision_json",
            nullable = false,
            columnDefinition = "jsonb")
    private String recommendedRevisionJson;

    @Column(name = "trace_id", length = 128, nullable = false)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected DeliberationReportEntity() {}

    private DeliberationReportEntity(String id) {
        super(id);
    }

    public static DeliberationReportEntity record(
            String id,
            String caseId,
            int version,
            String draftId,
            int dossierVersion,
            String panelResultJson,
            String majorRisksJson,
            String consensusJson,
            String disagreementsJson,
            String recommendedRevisionJson,
            String traceId,
            String actorId) {
        DeliberationReportEntity report = new DeliberationReportEntity(id);
        report.caseId = caseId;
        report.reportVersion = version;
        report.draftId = draftId;
        report.frozenDossierVersion = dossierVersion;
        report.panelResultJson = panelResultJson;
        report.majorRisksJson = majorRisksJson;
        report.consensusJson = consensusJson;
        report.disagreementsJson = disagreementsJson;
        report.recommendedRevisionJson = recommendedRevisionJson;
        report.traceId = traceId;
        report.createdBy = actorId;
        return report;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public int getReportVersion() {
        return reportVersion;
    }
}

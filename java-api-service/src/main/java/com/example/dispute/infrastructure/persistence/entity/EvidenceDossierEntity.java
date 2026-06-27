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
@Table(name = "evidence_dossier")
public class EvidenceDossierEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, unique = true)
    private String caseId;

    @Column(name = "dossier_status", length = 32, nullable = false)
    private String dossierStatus;

    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    private String summaryJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "timeline_json", nullable = false, columnDefinition = "jsonb")
    private String timelineJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matrix_summary_json", nullable = false, columnDefinition = "jsonb")
    private String matrixSummaryJson;

    @Column(name = "built_at")
    private OffsetDateTime builtAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected EvidenceDossierEntity() {}

    private EvidenceDossierEntity(
            String id,
            String caseId,
            String actorId,
            String summaryJson,
            String timelineJson,
            String matrixSummaryJson) {
        super(id);
        this.caseId = caseId;
        this.dossierStatus = "BUILT";
        this.dossierVersion = 1;
        this.summaryJson = summaryJson;
        this.timelineJson = timelineJson;
        this.matrixSummaryJson = matrixSummaryJson;
        this.builtAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    public static EvidenceDossierEntity firstBuild(
            String id,
            String caseId,
            String actorId,
            String summaryJson,
            String timelineJson,
            String matrixSummaryJson) {
        return new EvidenceDossierEntity(
                id,
                caseId,
                actorId,
                summaryJson,
                timelineJson,
                matrixSummaryJson);
    }

    public static EvidenceDossierEntity collecting(
            String id, String caseId, String actorId) {
        EvidenceDossierEntity entity =
                new EvidenceDossierEntity(id, caseId, actorId, "{}", "[]", "[]");
        entity.dossierStatus = "COLLECTING";
        entity.dossierVersion = 0;
        entity.builtAt = null;
        return entity;
    }

    public void rebuild(
            String actorId,
            String summaryJson,
            String timelineJson,
            String matrixSummaryJson) {
        dossierVersion += 1;
        dossierStatus = "BUILT";
        this.summaryJson = summaryJson;
        this.timelineJson = timelineJson;
        this.matrixSummaryJson = matrixSummaryJson;
        builtAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedBy = actorId;
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

    public String getCaseId() {
        return caseId;
    }

    public int getDossierVersion() {
        return dossierVersion;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public String getTimelineJson() {
        return timelineJson;
    }

    public String getMatrixSummaryJson() {
        return matrixSummaryJson;
    }
}

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
@Table(name = "evaluation_record")
public class EvaluationTraceEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "evaluation_version", nullable = false)
    private int evaluationVersion;

    @Column(name = "evaluation_status", length = 32, nullable = false)
    private String evaluationStatus;

    @Column(name = "evaluator_model", length = 128)
    private String evaluatorModel;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String inputSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metric_scores_json", nullable = false, columnDefinition = "jsonb")
    private String metricScoresJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings_json", nullable = false, columnDefinition = "jsonb")
    private String findingsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", nullable = false, columnDefinition = "jsonb")
    private String reportJson;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected EvaluationTraceEntity() {}

    private EvaluationTraceEntity(String id) {
        super(id);
    }

    public static EvaluationTraceEntity pending(
            String id,
            String caseId,
            int version,
            String inputSnapshotJson,
            String actorId) {
        EvaluationTraceEntity trace = new EvaluationTraceEntity(id);
        trace.caseId = caseId;
        trace.evaluationVersion = version;
        trace.evaluationStatus = "PENDING";
        trace.inputSnapshotJson = inputSnapshotJson;
        trace.metricScoresJson = "{}";
        trace.findingsJson = "[]";
        trace.reportJson = "{}";
        trace.createdBy = actorId;
        trace.updatedBy = actorId;
        return trace;
    }

    public void retry(String inputSnapshotJson, String actorId) {
        if ("COMPLETED".equals(evaluationStatus)) {
            throw new IllegalStateException(
                    "completed evaluation cannot be retried");
        }
        evaluationStatus = "PENDING";
        this.inputSnapshotJson = inputSnapshotJson;
        evaluatorModel = null;
        promptVersion = null;
        metricScoresJson = "{}";
        findingsJson = "[]";
        reportJson = "{}";
        latencyMs = null;
        tokenUsage = null;
        completedAt = null;
        updatedBy = actorId;
    }

    public void complete(
            String evaluatorModel,
            String promptVersion,
            String metricScoresJson,
            String findingsJson,
            String reportJson,
            long latencyMs,
            int tokenUsage,
            String actorId) {
        this.evaluationStatus = "COMPLETED";
        this.evaluatorModel = evaluatorModel;
        this.promptVersion = promptVersion;
        this.metricScoresJson = metricScoresJson;
        this.findingsJson = findingsJson;
        this.reportJson = reportJson;
        this.latencyMs = latencyMs;
        this.tokenUsage = tokenUsage;
        this.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedBy = actorId;
    }

    public void fail(String reportJson, String actorId) {
        evaluationStatus = "FAILED";
        this.reportJson = reportJson;
        completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedBy = actorId;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getCaseId() {
        return caseId;
    }

    public int getEvaluationVersion() {
        return evaluationVersion;
    }

    public String getEvaluationStatus() {
        return evaluationStatus;
    }

    public String getEvaluatorModel() {
        return evaluatorModel;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getInputSnapshotJson() {
        return inputSnapshotJson;
    }

    public String getMetricScoresJson() {
        return metricScoresJson;
    }

    public String getFindingsJson() {
        return findingsJson;
    }

    public String getReportJson() {
        return reportJson;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Integer getTokenUsage() {
        return tokenUsage;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

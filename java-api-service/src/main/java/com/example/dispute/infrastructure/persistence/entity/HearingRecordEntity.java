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
@Table(name = "hearing_stage_record")
public class HearingRecordEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "hearing_state_id", length = 64, nullable = false)
    private String hearingStateId;
    @Column(name = "workflow_id", length = 128, nullable = false)
    private String workflowId;
    @Column(name = "node_name", length = 64, nullable = false)
    private String nodeName;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Column(name = "record_type", length = 64, nullable = false)
    private String recordType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", nullable = false, columnDefinition = "jsonb")
    private String inputJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", nullable = false, columnDefinition = "jsonb")
    private String outputJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;
    @Column(name = "prompt_version", length = 64)
    private String promptVersion;
    @Column(name = "model", length = 128)
    private String model;
    @Column(name = "latency_ms")
    private Long latencyMs;
    @Column(name = "token_usage")
    private Integer tokenUsage;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected HearingRecordEntity() {}

    private HearingRecordEntity(String id) { super(id); }

    public static HearingRecordEntity record(
            String id, String caseId, String hearingStateId, String workflowId,
            String nodeName, int roundNo, String recordType, String inputJson,
            String outputJson, String metadataJson, String promptVersion,
            String model, Long latencyMs, Integer tokenUsage, String actorId) {
        HearingRecordEntity record = new HearingRecordEntity(id);
        record.caseId = caseId;
        record.hearingStateId = hearingStateId;
        record.workflowId = workflowId;
        record.nodeName = nodeName;
        record.roundNo = roundNo;
        record.recordType = recordType;
        record.inputJson = inputJson;
        record.outputJson = outputJson;
        record.metadataJson = metadataJson;
        record.promptVersion = promptVersion;
        record.model = model;
        record.latencyMs = latencyMs;
        record.tokenUsage = tokenUsage;
        record.createdBy = actorId;
        return record;
    }

    @PrePersist
    void prePersist() { createdAt = OffsetDateTime.now(ZoneOffset.UTC); }

    public String getNodeName() { return nodeName; }
    public int getRoundNo() { return roundNo; }
    public String getRecordType() { return recordType; }
    public String getOutputJson() { return outputJson; }
}

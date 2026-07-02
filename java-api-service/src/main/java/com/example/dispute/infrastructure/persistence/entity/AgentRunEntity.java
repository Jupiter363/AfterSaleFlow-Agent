package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_run")
public class AgentRunEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "workflow_id", length = 128)
    private String workflowId;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    @Column(name = "agent_role", length = 128, nullable = false)
    private String agentRole;

    @Column(name = "profile_version", length = 64, nullable = false)
    private String profileVersion;

    @Column(name = "prompt_version", length = 64, nullable = false)
    private String promptVersion;

    @Column(name = "skill_version", length = 64, nullable = false)
    private String skillVersion;

    @Column(name = "ruleset_version", length = 64, nullable = false)
    private String rulesetVersion;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "run_status", length = 32, nullable = false)
    private String runStatus;

    @Column(name = "stop_reason", length = 64)
    private String stopReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_refs_json", nullable = false, columnDefinition = "jsonb")
    private String inputRefsJson;

    @Column(name = "output_ref", length = 128)
    private String outputRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_json", nullable = false, columnDefinition = "jsonb")
    private String validationJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_flags_json", nullable = false, columnDefinition = "jsonb")
    private String riskFlagsJson;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "cost_amount", precision = 18, scale = 6)
    private BigDecimal costAmount;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "trace_id", length = 128, nullable = false)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected AgentRunEntity() {}

    private AgentRunEntity(String id) {
        super(id);
    }

    public static AgentRunEntity completed(
            String id,
            String caseId,
            String workflowId,
            String agentId,
            String agentRole,
            String profileVersion,
            String promptVersion,
            String skillVersion,
            String rulesetVersion,
            String model,
            String inputRefsJson,
            String outputRef,
            String validationJson,
            String riskFlagsJson,
            Integer tokenUsage,
            Long latencyMs,
            BigDecimal costAmount,
            OffsetDateTime startedAt,
            String traceId,
            String actorId) {
        AgentRunEntity run = new AgentRunEntity(id);
        run.caseId = caseId;
        run.workflowId = workflowId;
        run.agentId = agentId;
        run.agentRole = agentRole;
        run.profileVersion = profileVersion;
        run.promptVersion = promptVersion;
        run.skillVersion = skillVersion;
        run.rulesetVersion = rulesetVersion;
        run.model = model;
        run.runStatus = "COMPLETED";
        run.stopReason = "OUTPUT_VALIDATED";
        run.inputRefsJson = inputRefsJson;
        run.outputRef = outputRef;
        run.validationJson = validationJson;
        run.riskFlagsJson = riskFlagsJson;
        run.tokenUsage = tokenUsage;
        run.latencyMs = latencyMs;
        run.costAmount = costAmount;
        run.startedAt = startedAt;
        run.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        run.traceId = traceId;
        run.createdBy = actorId;
        return run;
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void attachOutput(String outputRef) {
        if (!"COMPLETED".equals(runStatus)) {
            throw new IllegalStateException("only completed Agent Runs can reference output");
        }
        this.outputRef = outputRef;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public String getInputRefsJson() {
        return inputRefsJson;
    }

    public String getOutputRef() {
        return outputRef;
    }

    public Integer getTokenUsage() {
        return tokenUsage;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public String getTraceId() {
        return traceId;
    }
}

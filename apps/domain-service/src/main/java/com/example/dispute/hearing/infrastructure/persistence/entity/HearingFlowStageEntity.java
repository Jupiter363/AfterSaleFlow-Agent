package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowStageStatus;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable lifecycle and input/output envelope for one hearing_flow.v2 stage. */
@Entity
@Table(name = "hearing_flow_stage")
public class HearingFlowStageEntity extends AbstractEntity {

    @Column(name = "flow_instance_id", length = 64, nullable = false)
    private String flowInstanceId;

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_code", length = 64, nullable = false)
    private HearingFlowStage stageCode;

    @Column(name = "stage_sequence", nullable = false)
    private int stageSequence;

    @Column(name = "processor_role", length = 64, nullable = false)
    private String processorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_status", length = 32, nullable = false)
    private HearingFlowStageStatus stageStatus;

    @Column(name = "shared_deadline_at")
    private Instant sharedDeadlineAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", nullable = false, columnDefinition = "jsonb")
    private String inputJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", nullable = false, columnDefinition = "jsonb")
    private String outputJson;

    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected HearingFlowStageEntity() {}

    private HearingFlowStageEntity(String id) {
        super(id);
    }

    public static HearingFlowStageEntity open(
            String id,
            String flowInstanceId,
            String caseId,
            HearingFlowStage stageCode,
            int stageSequence,
            String processorRole,
            HearingFlowStageStatus status,
            Instant sharedDeadlineAt,
            String inputJson,
            Instant now,
            String actorId) {
        if (stageSequence < 1) {
            throw new IllegalArgumentException("stageSequence must be positive");
        }
        if (stageCode.hasSharedPartyDeadline() != (sharedDeadlineAt != null)) {
            throw new IllegalArgumentException(
                    "shared deadline is required only for party-open hearing stages");
        }
        HearingFlowStageEntity entity = new HearingFlowStageEntity(required(id, "id"));
        entity.flowInstanceId = required(flowInstanceId, "flowInstanceId");
        entity.caseId = required(caseId, "caseId");
        entity.stageCode = required(stageCode, "stageCode");
        entity.stageSequence = stageSequence;
        entity.processorRole = required(processorRole, "processorRole");
        entity.stageStatus = required(status, "status");
        entity.sharedDeadlineAt = sharedDeadlineAt;
        entity.inputJson = jsonOrEmpty(inputJson);
        entity.outputJson = "{}";
        entity.startedAt = required(now, "now");
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.createdBy = required(actorId, "actorId");
        entity.updatedBy = actorId;
        return entity;
    }

    public void attachAgentRun(String agentRunId, Instant now, String actorId) {
        String requiredRunId = required(agentRunId, "agentRunId");
        if (this.agentRunId != null && !this.agentRunId.equals(requiredRunId)) {
            throw new IllegalStateException("hearing stage already belongs to another AgentRun");
        }
        this.agentRunId = requiredRunId;
        this.stageStatus = HearingFlowStageStatus.RUNNING;
        touch(now, actorId);
    }

    public void complete(String outputJson, Instant now, String actorId) {
        if (stageStatus == HearingFlowStageStatus.COMPLETED) {
            throw new IllegalStateException("hearing stage is already complete");
        }
        this.outputJson = jsonOrEmpty(outputJson);
        this.stageStatus = HearingFlowStageStatus.COMPLETED;
        this.completedAt = required(now, "now");
        touch(now, actorId);
    }

    public void fail(String outputJson, Instant now, String actorId) {
        if (stageStatus == HearingFlowStageStatus.COMPLETED) {
            throw new IllegalStateException("completed hearing stage cannot fail");
        }
        this.outputJson = jsonOrEmpty(outputJson);
        this.stageStatus = HearingFlowStageStatus.FAILED;
        this.completedAt = required(now, "now");
        touch(now, actorId);
    }

    public void shortenSharedDeadline(Instant deadline, Instant now, String actorId) {
        if (!stageCode.hasSharedPartyDeadline() || sharedDeadlineAt == null) {
            throw new IllegalStateException("hearing stage has no shared deadline");
        }
        Instant requiredDeadline = required(deadline, "deadline");
        if (!requiredDeadline.isBefore(sharedDeadlineAt)) {
            return;
        }
        this.sharedDeadlineAt = requiredDeadline;
        touch(now, actorId);
    }

    public void replaceFailedAgentRun(
            String failedAgentRunId,
            String retryAgentRunId,
            Instant now,
            String actorId) {
        if (stageStatus != HearingFlowStageStatus.RUNNING) {
            throw new IllegalStateException("only a running hearing stage can replace an AgentRun");
        }
        if (!required(failedAgentRunId, "failedAgentRunId").equals(agentRunId)) {
            throw new IllegalStateException("failed AgentRun does not own the hearing stage");
        }
        String requiredRetryId = required(retryAgentRunId, "retryAgentRunId");
        if (failedAgentRunId.equals(requiredRetryId)) {
            throw new IllegalArgumentException("retry AgentRun must differ from failed AgentRun");
        }
        this.agentRunId = requiredRetryId;
        touch(now, actorId);
    }

    public void retryFailedAgentRun(
            String failedAgentRunId,
            String retryAgentRunId,
            Instant now,
            String actorId) {
        if (stageStatus != HearingFlowStageStatus.FAILED) {
            throw new IllegalStateException("only a failed hearing stage can restart an AgentRun");
        }
        if (!required(failedAgentRunId, "failedAgentRunId").equals(agentRunId)) {
            throw new IllegalStateException("failed AgentRun does not own the hearing stage");
        }
        String requiredRetryId = required(retryAgentRunId, "retryAgentRunId");
        if (failedAgentRunId.equals(requiredRetryId)) {
            throw new IllegalArgumentException("retry AgentRun must differ from failed AgentRun");
        }
        this.agentRunId = requiredRetryId;
        this.stageStatus = HearingFlowStageStatus.RUNNING;
        this.outputJson = "{}";
        this.completedAt = null;
        touch(now, actorId);
    }

    public String getFlowInstanceId() { return flowInstanceId; }
    public String getCaseId() { return caseId; }
    public HearingFlowStage getStageCode() { return stageCode; }
    public int getStageSequence() { return stageSequence; }
    public String getProcessorRole() { return processorRole; }
    public HearingFlowStageStatus getStageStatus() { return stageStatus; }
    public Instant getSharedDeadlineAt() { return sharedDeadlineAt; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public String getAgentRunId() { return agentRunId; }
    public Instant getCompletedAt() { return completedAt; }

    private void touch(Instant now, String actorId) {
        this.updatedAt = required(now, "now");
        this.updatedBy = required(actorId, "actorId");
    }

    private static String jsonOrEmpty(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
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

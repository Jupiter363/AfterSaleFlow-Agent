package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.PendingState;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProcessProjection(
        String schemaVersion,
        String tenantSurrogate,
        String caseId,
        String workflowId,
        String workflowRunId,
        String workflowBuildId,
        WriterMode writerMode,
        String macroPhase,
        RoomType roomType,
        String roomPhase,
        long roomEpoch,
        long processRevision,
        long roomRevision,
        long fencingToken,
        long sourceEventSequence,
        PendingState pendingState,
        Instant projectedAt) {

    public ProcessProjection {
        schemaVersion = version(schemaVersion, "process-projection.v1");
        required(tenantSurrogate, "tenantSurrogate");
        required(caseId, "caseId");
        required(workflowId, "workflowId");
        required(workflowRunId, "workflowRunId");
        required(workflowBuildId, "workflowBuildId");
        required(writerMode, "writerMode");
        required(macroPhase, "macroPhase");
        required(roomType, "roomType");
        required(roomPhase, "roomPhase");
        required(pendingState, "pendingState");
        required(projectedAt, "projectedAt");
    }
}

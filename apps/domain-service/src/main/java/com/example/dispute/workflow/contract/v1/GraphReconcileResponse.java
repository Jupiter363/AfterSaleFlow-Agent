package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GraphReconcileResponse(
        String schemaVersion,
        Disposition disposition,
        String threadId,
        String commandId,
        String requestHash,
        String logicalRunId,
        String attemptId,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String checkpointNs,
        String checkpointId,
        String resultRef,
        String resultHash,
        String registryBindingHash,
        String toolPolicyVersion,
        RoomGraphResult result) {

    public GraphReconcileResponse {
        schemaVersion = version(schemaVersion, "graph-reconcile-response.v1");
        required(disposition, "disposition");
        required(threadId, "threadId");
        required(commandId, "commandId");
        required(requestHash, "requestHash");
        required(logicalRunId, "logicalRunId");
        required(attemptId, "attemptId");
        required(graphKey, "graphKey");
        required(graphVersion, "graphVersion");
        required(checkpointSchemaVersion, "checkpointSchemaVersion");
        required(checkpointNs, "checkpointNs");
        required(checkpointId, "checkpointId");
        required(resultRef, "resultRef");
        required(resultHash, "resultHash");
        required(registryBindingHash, "registryBindingHash");
        required(toolPolicyVersion, "toolPolicyVersion");
        required(result, "result");
        if (!commandId.equals(result.commandId())
                || !logicalRunId.equals(result.logicalRunId())
                || !attemptId.equals(result.attemptId())
                || !graphKey.equals(result.graphKey())
                || !graphVersion.equals(result.graphVersion())
                || !checkpointId.equals(result.checkpointId())
                || !resultHash.equals(result.outputHash())) {
            throw new IllegalArgumentException(
                    "reconciliation response conflicts with its nested result");
        }
    }

    public enum Disposition {
        RETURN_CACHED,
        RECONCILED_TERMINAL
    }
}

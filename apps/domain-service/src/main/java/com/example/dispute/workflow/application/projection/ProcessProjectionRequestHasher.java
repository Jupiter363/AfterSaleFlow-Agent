package com.example.dispute.workflow.application.projection;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

final class ProcessProjectionRequestHasher {

    private ProcessProjectionRequestHasher() {}

    static String hash(ApplyProjectionCommand command) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("schema_version", command.schemaVersion());
        root.put("operation_key", command.operationKey());
        root.put("tenant_surrogate", command.tenantSurrogate());
        root.put("case_id", command.caseId());
        root.put("command_id", command.commandId());
        root.put("command_request_hash", command.commandRequestHash());
        root.put("room_type", command.roomType().name());
        root.put("room_epoch", command.roomEpoch());
        root.put("fencing_token", command.fencingToken());
        root.put("expected_process_revision", command.expectedProcessRevision());
        root.put("new_process_revision", command.newProcessRevision());
        root.put("expected_room_revision", command.expectedRoomRevision());
        root.put("new_room_revision", command.newRoomRevision());
        root.put("macro_phase", command.macroPhase());
        root.put("current_room", command.currentRoom());
        root.put("room_phase", command.roomPhase());
        root.put("last_command_sequence", command.lastCommandSequence());
        root.put("last_case_event_sequence", command.lastCaseEventSequence());
        putNullable(
                root,
                "projected_deadline_at",
                command.projectedDeadlineAt() == null
                        ? null
                        : command.projectedDeadlineAt().toString());
        root.put("temporal_workflow_id", command.temporalWorkflowId());
        root.put("expected_temporal_run_id", command.expectedTemporalRunId());
        root.put("temporal_run_id", command.temporalRunId());
        root.put("temporal_build_id", command.temporalBuildId());
        putNullable(root, "projection_ref", command.projectionRef());
        putNullable(root, "projection_sha256", command.projectionSha256());
        return ContractJson.sha256Hex(root);
    }

    private static void putNullable(
            com.fasterxml.jackson.databind.node.ObjectNode root,
            String field,
            String value) {
        if (value == null) {
            root.putNull(field);
        } else {
            root.put(field, value);
        }
    }
}

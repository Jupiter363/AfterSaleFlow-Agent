package com.example.dispute.workflow.application.authority.epoch;

import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import java.util.Objects;

/** Immutable Java-owned selection pins persisted before Intake bootstrap delivery. */
public record EpochSelectionBinding(
        String epochId,
        String tenantSurrogate,
        String caseId,
        RoomType roomType,
        long roomEpoch,
        long fencingToken,
        String selectionHash,
        WriterMode writerMode,
        String caseWorkflowType,
        String caseWorkflowBuildId,
        String roomWorkflowType,
        String roomWorkflowBuildId,
        String processContractVersion,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String stateSchemaVersion,
        String streamProtocol,
        String promptVersion,
        String modelProfileId,
        String outputSchemaVersion,
        String policyVersion,
        String guardrailVersion,
        String toolPolicyVersion,
        String cohortPolicyVersion,
        String agentKey,
        String agentSessionProfileVersion,
        String memoryPolicyId) {

    public EpochSelectionBinding {
        required(epochId, "epochId");
        required(tenantSurrogate, "tenantSurrogate");
        required(caseId, "caseId");
        Objects.requireNonNull(roomType, "roomType must not be null");
        if (roomType != RoomType.INTAKE) {
            throw new IllegalArgumentException("authority epoch roomType must be INTAKE");
        }
        if (roomEpoch < 0 || fencingToken <= 0) {
            throw new IllegalArgumentException("roomEpoch must be non-negative and fencingToken positive");
        }
        requireSha256(selectionHash, "selectionHash");
        Objects.requireNonNull(writerMode, "writerMode must not be null");
        if (writerMode != WriterMode.SHADOW) {
            throw new IllegalArgumentException("R1.5 authority selection must use SHADOW writer mode");
        }
        required(caseWorkflowType, "caseWorkflowType");
        required(caseWorkflowBuildId, "caseWorkflowBuildId");
        required(roomWorkflowType, "roomWorkflowType");
        required(roomWorkflowBuildId, "roomWorkflowBuildId");
        required(processContractVersion, "processContractVersion");
        if (!"intake.v2".equals(graphKey)) {
            throw new IllegalArgumentException("graphKey must be intake.v2");
        }
        required(graphVersion, "graphVersion");
        required(checkpointSchemaVersion, "checkpointSchemaVersion");
        if (!"intake-graph-state.v2".equals(stateSchemaVersion)) {
            throw new IllegalArgumentException("stateSchemaVersion must be intake-graph-state.v2");
        }
        required(streamProtocol, "streamProtocol");
        required(promptVersion, "promptVersion");
        required(modelProfileId, "modelProfileId");
        if (!"intake-turn-proposal.v2".equals(outputSchemaVersion)) {
            throw new IllegalArgumentException("outputSchemaVersion must be intake-turn-proposal.v2");
        }
        required(policyVersion, "policyVersion");
        required(guardrailVersion, "guardrailVersion");
        required(toolPolicyVersion, "toolPolicyVersion");
        required(cohortPolicyVersion, "cohortPolicyVersion");
        if (!"DISPUTE_INTAKE_OFFICER".equals(agentKey)) {
            throw new IllegalArgumentException("agentKey must be DISPUTE_INTAKE_OFFICER");
        }
        if (!"agent-session-profile.v1".equals(agentSessionProfileVersion)) {
            throw new IllegalArgumentException(
                    "agentSessionProfileVersion must be agent-session-profile.v1");
        }
        if (!"GRAPH_PRIVATE_NO_MEMORY_FRAME_V1".equals(memoryPolicyId)) {
            throw new IllegalArgumentException(
                    "memoryPolicyId must be GRAPH_PRIVATE_NO_MEMORY_FRAME_V1");
        }
        String expectedHash = EpochSelectionHasher.hash(new EpochSelectionHasher.SelectionHashInput(
                "room-epoch-selection.v2",
                roomType,
                writerMode,
                caseWorkflowType,
                caseWorkflowBuildId,
                roomWorkflowType,
                roomWorkflowBuildId,
                processContractVersion,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                stateSchemaVersion,
                streamProtocol,
                promptVersion,
                modelProfileId,
                outputSchemaVersion,
                policyVersion,
                guardrailVersion,
                toolPolicyVersion,
                cohortPolicyVersion));
        if (!selectionHash.equals(expectedHash)) {
            throw new IllegalArgumentException("selectionHash does not match room-epoch-selection.v2");
        }
    }

    public EpochSelectionBinding(
            String epochId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String selectionHash,
            String processContractVersion,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol,
            String promptVersion,
            String modelProfileId,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion,
            String cohortPolicyVersion,
            String caseWorkflowBuildId,
            String roomWorkflowBuildId) {
        this(
                epochId,
                tenantSurrogate,
                caseId,
                RoomType.INTAKE,
                roomEpoch,
                fencingToken,
                selectionHash,
                WriterMode.SHADOW,
                "CaseProcessWorkflow",
                caseWorkflowBuildId,
                "IntakeRoomWorkflow",
                roomWorkflowBuildId,
                processContractVersion,
                "intake.v2",
                graphVersion,
                checkpointSchemaVersion,
                "intake-graph-state.v2",
                streamProtocol,
                promptVersion,
                modelProfileId,
                "intake-turn-proposal.v2",
                policyVersion,
                guardrailVersion,
                toolPolicyVersion,
                cohortPolicyVersion,
                "DISPUTE_INTAKE_OFFICER",
                "agent-session-profile.v1",
                "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1");
    }

    public static EpochSelectionBinding from(
            String epochId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            RoomEpochSelection selection,
            String promptVersion,
            String modelProfileId,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion,
            String cohortPolicyVersion,
            String selectionHash) {
        Objects.requireNonNull(selection, "selection must not be null");
        return new EpochSelectionBinding(
                epochId,
                tenantSurrogate,
                caseId,
                RoomType.INTAKE,
                roomEpoch,
                fencingToken,
                selectionHash,
                selection.writerMode(),
                selection.caseWorkflowType(),
                selection.caseWorkflowBuildId(),
                selection.roomWorkflowType(),
                selection.roomWorkflowBuildId(),
                selection.processContractVersion(),
                selection.graphKey(),
                selection.graphVersion(),
                selection.checkpointSchemaVersion(),
                "intake-graph-state.v2",
                selection.streamProtocol(),
                promptVersion,
                modelProfileId,
                "intake-turn-proposal.v2",
                policyVersion,
                guardrailVersion,
                toolPolicyVersion,
                cohortPolicyVersion,
                "DISPUTE_INTAKE_OFFICER",
                "agent-session-profile.v1",
                "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}

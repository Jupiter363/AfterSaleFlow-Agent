package com.example.dispute.workflow.application.epoch;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import java.util.Objects;

public record RoomEpochSelection(
        WriterMode writerMode,
        String selectionSchemaVersion,
        String processContractVersion,
        String caseWorkflowType,
        String caseWorkflowBuildId,
        String roomWorkflowType,
        String roomWorkflowBuildId,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String streamProtocol) {

    public static final String V1 = "room-epoch-selection.v1";
    public static final String V2 = "room-epoch-selection.v2";

    public RoomEpochSelection(
            WriterMode writerMode,
            String selectionSchemaVersion,
            String processContractVersion,
            String workflowType,
            String buildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol) {
        this(
                writerMode,
                selectionSchemaVersion,
                processContractVersion,
                workflowType,
                buildId,
                null,
                null,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                streamProtocol);
    }

    public RoomEpochSelection {
        Objects.requireNonNull(writerMode, "writerMode must not be null");
        requireText(selectionSchemaVersion, "selectionSchemaVersion");
        requireText(processContractVersion, "processContractVersion");
        requireText(caseWorkflowType, "caseWorkflowType");
        requireText(caseWorkflowBuildId, "caseWorkflowBuildId");
        requireText(graphKey, "graphKey");
        requireText(graphVersion, "graphVersion");
        requireText(checkpointSchemaVersion, "checkpointSchemaVersion");
        requireText(streamProtocol, "streamProtocol");
        if (V1.equals(selectionSchemaVersion)) {
            if (roomWorkflowType != null || roomWorkflowBuildId != null) {
                throw new IllegalArgumentException(
                        "v1 selection cannot contain a room Workflow binding");
            }
        } else if (V2.equals(selectionSchemaVersion)) {
            requireText(roomWorkflowType, "roomWorkflowType");
            requireText(roomWorkflowBuildId, "roomWorkflowBuildId");
            if (!"CaseProcessWorkflow".equals(caseWorkflowType)) {
                throw new IllegalArgumentException(
                        "v2 selection requires the CaseProcessWorkflow case binding");
            }
            if (writerMode != WriterMode.LEGACY
                    && !"IntakeRoomWorkflow".equals(roomWorkflowType)) {
                throw new IllegalArgumentException(
                        "non-LEGACY v2 selection requires the IntakeRoomWorkflow binding");
            }
        } else {
            throw new IllegalArgumentException("unsupported selectionSchemaVersion");
        }
    }

    public String workflowType() {
        return caseWorkflowType;
    }

    public String buildId() {
        return caseWorkflowBuildId;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

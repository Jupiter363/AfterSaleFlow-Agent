package com.example.dispute.workflow.application.epoch;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import java.util.Objects;

public record RoomEpochSelection(
        WriterMode writerMode,
        String selectionSchemaVersion,
        String processContractVersion,
        String workflowType,
        String buildId,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String streamProtocol) {

    public RoomEpochSelection {
        Objects.requireNonNull(writerMode, "writerMode must not be null");
        requireText(selectionSchemaVersion, "selectionSchemaVersion");
        requireText(processContractVersion, "processContractVersion");
        requireText(workflowType, "workflowType");
        requireText(buildId, "buildId");
        requireText(graphKey, "graphKey");
        requireText(graphVersion, "graphVersion");
        requireText(checkpointSchemaVersion, "checkpointSchemaVersion");
        requireText(streamProtocol, "streamProtocol");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

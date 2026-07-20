package com.example.dispute.workflow.application.epoch;

import com.example.dispute.workflow.config.OrchestrationCutoverProperties;
import com.example.dispute.workflow.config.TemporalWorkerProperties;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.springframework.stereotype.Component;

@Component
public final class ConfiguredRoomEpochSelector implements RoomEpochSelector {

    public static final String SELECTION_SCHEMA_VERSION = "room-epoch-selection.v1";
    public static final String PROCESS_CONTRACT_VERSION = "case-process-contract.v1";
    public static final String GRAPH_VERSION = "1.0.0";
    public static final String CHECKPOINT_SCHEMA_VERSION = "checkpoint.v1";
    public static final String STREAM_PROTOCOL = "agent-stream.v2";
    public static final String LEGACY_BUILD_ID = "legacy-java.v1";
    public static final String LEGACY_WORKFLOW_TYPE = "LegacyJavaRoomState";

    private final OrchestrationCutoverProperties cutoverProperties;
    private final TemporalWorkerProperties workerProperties;

    public ConfiguredRoomEpochSelector(
            OrchestrationCutoverProperties cutoverProperties,
            TemporalWorkerProperties workerProperties) {
        this.cutoverProperties = cutoverProperties;
        this.workerProperties = workerProperties;
    }

    @Override
    public RoomEpochSelection selectForNewEpoch(RoomType roomType) {
        WriterMode writerMode = cutoverProperties.newEpochMode();
        requireAllocationEnabled(writerMode);
        boolean legacy = writerMode == WriterMode.LEGACY;
        return new RoomEpochSelection(
                writerMode,
                SELECTION_SCHEMA_VERSION,
                PROCESS_CONTRACT_VERSION,
                legacy
                        ? LEGACY_WORKFLOW_TYPE
                        : CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                legacy ? LEGACY_BUILD_ID : workerProperties.legacyBuildId(),
                graphKey(roomType),
                GRAPH_VERSION,
                CHECKPOINT_SCHEMA_VERSION,
                STREAM_PROTOCOL);
    }

    private void requireAllocationEnabled(WriterMode writerMode) {
        if (writerMode != WriterMode.LEGACY
                && !cutoverProperties.nonLegacyEpochAllocationEnabled()) {
            throw new IllegalStateException(
                    "non-LEGACY room epoch allocation is disabled");
        }
        if (writerMode == WriterMode.TEMPORAL
                && !cutoverProperties.temporalWriterEnabled()) {
            throw new IllegalStateException("TEMPORAL room writer activation is disabled");
        }
    }

    public static RoomEpochSelection terminalLegacySelection(RoomType roomType) {
        return new RoomEpochSelection(
                WriterMode.LEGACY,
                SELECTION_SCHEMA_VERSION,
                PROCESS_CONTRACT_VERSION,
                LEGACY_WORKFLOW_TYPE,
                LEGACY_BUILD_ID,
                graphKey(roomType),
                GRAPH_VERSION,
                CHECKPOINT_SCHEMA_VERSION,
                STREAM_PROTOCOL);
    }

    private static String graphKey(RoomType roomType) {
        return switch (roomType) {
            case INTAKE -> "intake.v2";
            case EVIDENCE -> "evidence.v2";
            case HEARING -> "hearing.v2";
            case REVIEW -> "review.v1";
        };
    }
}

package com.example.dispute.workflow.projection.intake;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Public, sanitized process metadata for the Intake status endpoint. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntakeProcessProjectionView(
        String schemaVersion,
        String projectionState,
        String writerMode,
        long roomEpoch,
        long processRevision,
        long roomRevision,
        long fencingToken,
        String roomPhase,
        String pendingState,
        String commandAdmissionState,
        String activeLogicalRunId,
        String activeAttemptId,
        String activeRunStatus,
        String streamCursor,
        VersionPins versionPins,
        OffsetDateTime projectedAt) {

    public static final String SCHEMA_VERSION = "intake-process-projection.v1";
    public static final String CURRENT = "CURRENT";
    public static final String PROCESSING = "PROCESSING";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String COMMAND_ADMISSION_READY = "READY";
    public static final String COMMAND_ADMISSION_PENDING = "PENDING";

    /** Source-compatible constructor before command admission state became visible. */
    public IntakeProcessProjectionView(
            String schemaVersion,
            String projectionState,
            String writerMode,
            long roomEpoch,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String roomPhase,
            String pendingState,
            String activeLogicalRunId,
            String activeAttemptId,
            String activeRunStatus,
            String streamCursor,
            VersionPins versionPins,
            OffsetDateTime projectedAt) {
        this(
                schemaVersion,
                projectionState,
                writerMode,
                roomEpoch,
                processRevision,
                roomRevision,
                fencingToken,
                roomPhase,
                pendingState,
                COMMAND_ADMISSION_READY,
                activeLogicalRunId,
                activeAttemptId,
                activeRunStatus,
                streamCursor,
                versionPins,
                projectedAt);
    }

    public static IntakeProcessProjectionView legacyUnavailable(OffsetDateTime projectedAt) {
        return new IntakeProcessProjectionView(
                SCHEMA_VERSION,
                UNAVAILABLE,
                "LEGACY",
                0,
                0,
                0,
                0,
                "LEGACY",
                "NONE",
                COMMAND_ADMISSION_READY,
                null,
                null,
                null,
                null,
                VersionPins.unavailable(),
                projectedAt);
    }

    public static IntakeProcessProjectionView processingUnavailable() {
        return new IntakeProcessProjectionView(
                SCHEMA_VERSION,
                PROCESSING,
                "UNKNOWN",
                0,
                0,
                0,
                0,
                PROCESSING,
                PROCESSING,
                COMMAND_ADMISSION_PENDING,
                null,
                null,
                null,
                null,
                VersionPins.unavailable(),
                null);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VersionPins(
            String processContractVersion,
            String selectionSchemaVersion,
            String streamProtocol,
            String temporalBuildId,
            String roomWorkflowBuildId,
            String graphVersion,
            String checkpointSchemaVersion) {

        public static VersionPins unavailable() {
            return new VersionPins(null, null, null, null, null, null, null);
        }
    }
}

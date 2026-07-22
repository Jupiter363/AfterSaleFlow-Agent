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
                null,
                null,
                null,
                null,
                VersionPins.unavailable(),
                projectedAt);
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

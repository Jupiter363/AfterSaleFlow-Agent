package com.example.dispute.workflow.projection.evidence;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Public, actor-sanitized process metadata for the Evidence room. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EvidenceProcessProjectionView(
        String schemaVersion,
        String projectionState,
        String writerMode,
        long roomEpoch,
        long processRevision,
        long roomRevision,
        long fencingToken,
        String roomPhase,
        String pendingState,
        boolean historyMode,
        String activeLogicalRunId,
        String activeAttemptId,
        String activeRunStatus,
        String streamCursor,
        VersionPins versionPins,
        OffsetDateTime projectedAt) {

    public static final String SCHEMA_VERSION = "evidence-process-projection.v1";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String PROCESSING = "PROCESSING";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public static EvidenceProcessProjectionView legacyUnavailable(
            OffsetDateTime projectedAt, boolean historyMode) {
        return new EvidenceProcessProjectionView(
                SCHEMA_VERSION,
                UNAVAILABLE,
                "LEGACY",
                0,
                0,
                0,
                0,
                "LEGACY",
                "NONE",
                historyMode,
                null,
                null,
                null,
                null,
                VersionPins.unavailable(),
                projectedAt);
    }

    public static EvidenceProcessProjectionView processingUnavailable(boolean historyMode) {
        return new EvidenceProcessProjectionView(
                SCHEMA_VERSION,
                PROCESSING,
                "UNKNOWN",
                0,
                0,
                0,
                0,
                PROCESSING,
                PROCESSING,
                historyMode,
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

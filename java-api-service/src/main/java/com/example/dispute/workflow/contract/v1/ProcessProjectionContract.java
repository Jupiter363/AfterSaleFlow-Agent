package com.example.dispute.workflow.contract.v1;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ProcessProjectionContract {

    private static final Pattern KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern STATE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private ProcessProjectionContract() {}

    public enum ApplyProjectionOutcome {
        APPLIED,
        IDEMPOTENT_REPLAY
    }

    public record ApplyProjectionCommand(
            String schemaVersion,
            String operationKey,
            String tenantSurrogate,
            String caseId,
            String commandId,
            String commandRequestHash,
            RoomType roomType,
            long roomEpoch,
            long fencingToken,
            long expectedProcessRevision,
            long newProcessRevision,
            long expectedRoomRevision,
            long newRoomRevision,
            String macroPhase,
            String currentRoom,
            String roomPhase,
            long lastCommandSequence,
            long lastCaseEventSequence,
            Instant projectedDeadlineAt,
            String temporalWorkflowId,
            String expectedTemporalRunId,
            String temporalRunId,
            String temporalBuildId,
            String projectionRef,
            String projectionSha256) {

        public ApplyProjectionCommand {
            if (!"apply-process-projection.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be apply-process-projection.v1");
            }
            requireKey(operationKey, "operationKey");
            requireText(tenantSurrogate, 128, "tenantSurrogate");
            requireText(caseId, 64, "caseId");
            requireKey(commandId, "commandId");
            requireHash(commandRequestHash, "commandRequestHash");
            Objects.requireNonNull(roomType, "roomType must not be null");
            if (roomEpoch < 0 || fencingToken < 1) {
                throw new IllegalArgumentException("room epoch or fencing token is invalid");
            }
            if (expectedProcessRevision < 0
                    || newProcessRevision <= expectedProcessRevision) {
                throw new IllegalArgumentException("process revision transition is invalid");
            }
            if (expectedRoomRevision < 0 || newRoomRevision < expectedRoomRevision) {
                throw new IllegalArgumentException("room revision transition is invalid");
            }
            requireState(macroPhase, "macroPhase");
            requireState(currentRoom, "currentRoom");
            requireState(roomPhase, "roomPhase");
            if (lastCommandSequence < 1 || lastCaseEventSequence < 0) {
                throw new IllegalArgumentException("projection sequence is invalid");
            }
            if (projectedDeadlineAt != null) {
                projectedDeadlineAt = projectedDeadlineAt.truncatedTo(ChronoUnit.MICROS);
            }
            requireText(temporalWorkflowId, 128, "temporalWorkflowId");
            requireText(expectedTemporalRunId, 128, "expectedTemporalRunId");
            requireText(temporalRunId, 128, "temporalRunId");
            if (!expectedTemporalRunId.equals(temporalRunId)) {
                throw new IllegalArgumentException(
                        "temporalRunId must preserve the first-execution run binding");
            }
            requireText(temporalBuildId, 128, "temporalBuildId");
            requireReference(projectionRef, projectionSha256);
        }
    }

    public record ApplyProjectionResult(
            String schemaVersion,
            String operationKey,
            ApplyProjectionOutcome outcome,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalWorkflowId,
            String temporalRunId,
            String resultRef,
            String resultSha256,
            Instant appliedAt) {

        public ApplyProjectionResult {
            if (!"apply-process-projection-result.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be apply-process-projection-result.v1");
            }
            requireKey(operationKey, "operationKey");
            Objects.requireNonNull(outcome, "outcome must not be null");
            if (processRevision < 1 || roomRevision < 0 || fencingToken < 1) {
                throw new IllegalArgumentException("projection result revision is invalid");
            }
            requireText(temporalWorkflowId, 128, "temporalWorkflowId");
            requireText(temporalRunId, 128, "temporalRunId");
            requireReference(resultRef, resultSha256);
            Objects.requireNonNull(appliedAt, "appliedAt must not be null");
        }
    }

    private static void requireReference(String uri, String hash) {
        if (uri == null && hash == null) {
            return;
        }
        if (uri == null
                || uri.length() > 1024
                || !(uri.startsWith("s3:")
                        || uri.startsWith("minio:")
                        || uri.startsWith("urn:"))) {
            throw new IllegalArgumentException("projection reference uri is invalid");
        }
        requireHash(hash, "projectionSha256");
    }

    private static void requireKey(String value, String field) {
        if (value == null || !KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireState(String value, String field) {
        if (value == null || !STATE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}

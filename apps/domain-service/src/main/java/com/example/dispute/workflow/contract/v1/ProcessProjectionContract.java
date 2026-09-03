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

    public enum CompleteConsumedIntakeProjectionOutcome {
        APPLIED,
        IDEMPOTENT_REPLAY
    }

    public record CompleteConsumedIntakeProjectionCommand(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            String eventId,
            long caseEventSequence,
            String eventType,
            long lastCommandSequence,
            long roomEpoch,
            long fencingToken,
            long processRevision,
            long roomRevision,
            String temporalWorkflowId,
            String firstExecutionRunId,
            String activeChildRunId) {

        public CompleteConsumedIntakeProjectionCommand {
            if (!"complete-consumed-intake-projection.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be complete-consumed-intake-projection.v1");
            }
            requireText(tenantSurrogate, 128, "tenantSurrogate");
            requireText(caseId, 64, "caseId");
            requireKey(eventId, "eventId");
            if (caseEventSequence < 1 || lastCommandSequence < 1) {
                throw new IllegalArgumentException("consumed Intake sequence is invalid");
            }
            if (!isIntakeFormalEventType(eventType)) {
                throw new IllegalArgumentException("eventType is not a formal Intake turn event");
            }
            if (roomEpoch < 0 || fencingToken < 1) {
                throw new IllegalArgumentException("room epoch or fencing token is invalid");
            }
            if (processRevision < 1 || roomRevision < 1) {
                throw new IllegalArgumentException("consumed Intake revision is invalid");
            }
            requireText(temporalWorkflowId, 128, "temporalWorkflowId");
            requireText(firstExecutionRunId, 128, "firstExecutionRunId");
            requireText(activeChildRunId, 128, "activeChildRunId");
        }
    }

    public record CompleteConsumedIntakeProjectionResult(
            String schemaVersion,
            String eventId,
            long caseEventSequence,
            CompleteConsumedIntakeProjectionOutcome outcome,
            long lastCommandSequence,
            long processRevision,
            long roomRevision,
            long roomEpoch,
            long fencingToken,
            String temporalWorkflowId,
            String firstExecutionRunId,
            String activeChildRunId,
            String resultRef,
            String resultSha256,
            Instant completedAt,
            String readyEventId,
            Long readyEventSequence) {

        public CompleteConsumedIntakeProjectionResult(
                String schemaVersion,
                String eventId,
                long caseEventSequence,
                CompleteConsumedIntakeProjectionOutcome outcome,
                long lastCommandSequence,
                long processRevision,
                long roomRevision,
                long roomEpoch,
                long fencingToken,
                String temporalWorkflowId,
                String firstExecutionRunId,
                String activeChildRunId,
                String resultRef,
                String resultSha256,
                Instant completedAt) {
            this(
                    schemaVersion,
                    eventId,
                    caseEventSequence,
                    outcome,
                    lastCommandSequence,
                    processRevision,
                    roomRevision,
                    roomEpoch,
                    fencingToken,
                    temporalWorkflowId,
                    firstExecutionRunId,
                    activeChildRunId,
                    resultRef,
                    resultSha256,
                    completedAt,
                    null,
                    null);
        }

        public CompleteConsumedIntakeProjectionResult {
            if (!"complete-consumed-intake-projection-result.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be complete-consumed-intake-projection-result.v1");
            }
            requireKey(eventId, "eventId");
            Objects.requireNonNull(outcome, "outcome must not be null");
            if (caseEventSequence < 1 || lastCommandSequence < 1) {
                throw new IllegalArgumentException("completed Intake sequence is invalid");
            }
            if (processRevision < 1 || roomRevision < 1) {
                throw new IllegalArgumentException("completed Intake revision is invalid");
            }
            if (roomEpoch < 0 || fencingToken < 1) {
                throw new IllegalArgumentException("room epoch or fencing token is invalid");
            }
            requireText(temporalWorkflowId, 128, "temporalWorkflowId");
            requireText(firstExecutionRunId, 128, "firstExecutionRunId");
            requireText(activeChildRunId, 128, "activeChildRunId");
            if (resultRef == null || resultSha256 == null) {
                throw new IllegalArgumentException("completion result reference is required");
            }
            requireReference(resultRef, resultSha256);
            Objects.requireNonNull(completedAt, "completedAt must not be null");
            completedAt = completedAt.truncatedTo(ChronoUnit.MICROS);
            if ((readyEventId == null) != (readyEventSequence == null)) {
                throw new IllegalArgumentException(
                        "ready event id and sequence must both be absent or present");
            }
            if (readyEventId != null) {
                requireKey(readyEventId, "readyEventId");
                if (readyEventSequence <= caseEventSequence) {
                    throw new IllegalArgumentException(
                            "ready event sequence must follow the consumed Intake event");
                }
            }
        }
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

    private static boolean isIntakeFormalEventType(String eventType) {
        return "TURN_NEEDS_INPUT".equals(eventType)
                || "INTAKE_TURN_NEEDS_INPUT".equals(eventType)
                || "TURN_READY_TO_CONFIRM".equals(eventType)
                || "INTAKE_TURN_READY_TO_CONFIRM".equals(eventType);
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

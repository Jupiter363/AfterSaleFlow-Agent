package com.example.dispute.workflow.application.projection;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public interface AuthoritativeProcessStateReader {

    ReadResult read(ReconciliationTarget target);

    record ReconciliationTarget(
            String tenantSurrogate, String caseId, String temporalWorkflowId) {

        public ReconciliationTarget {
            requireText(tenantSurrogate, 128, "tenantSurrogate");
            requireText(caseId, 64, "caseId");
            requireText(temporalWorkflowId, 128, "temporalWorkflowId");
        }
    }

    sealed interface ReadResult permits Verified, Incomplete, Unavailable {}

    record Verified(AuthoritativeProcessState state, String verificationRef)
            implements ReadResult {

        public Verified {
            Objects.requireNonNull(state, "state must not be null");
            requireText(verificationRef, 512, "verificationRef");
            if (!verificationRef.startsWith("temporal:")) {
                throw new IllegalArgumentException(
                        "verificationRef must identify Temporal verification evidence");
            }
        }
    }

    record Incomplete(AuthoritativeProcessObservation observation, String reasonCode)
            implements ReadResult {

        public Incomplete {
            Objects.requireNonNull(observation, "observation must not be null");
            requireCode(reasonCode, "reasonCode");
        }
    }

    record Unavailable(String reasonCode) implements ReadResult {

        public Unavailable {
            requireCode(reasonCode, "reasonCode");
        }
    }

    record AuthoritativeProcessObservation(
            String tenantSurrogate,
            String caseId,
            String temporalWorkflowId,
            String temporalRunId,
            String macroPhase,
            RoomType activeRoomType,
            long activeRoomEpoch,
            long processRevision,
            long lastCommandSequence,
            long lastCaseEventSequence) {

        public AuthoritativeProcessObservation {
            requireText(tenantSurrogate, 128, "tenantSurrogate");
            requireText(caseId, 64, "caseId");
            requireText(temporalWorkflowId, 128, "temporalWorkflowId");
            requireText(temporalRunId, 128, "temporalRunId");
            requireState(macroPhase, "macroPhase");
            if (activeRoomEpoch < 0
                    || processRevision < 0
                    || lastCommandSequence < 0
                    || lastCaseEventSequence < 0) {
                throw new IllegalArgumentException(
                        "authoritative observation revisions must not be negative");
            }
        }
    }

    record AuthoritativeProcessState(
            String tenantSurrogate,
            String caseId,
            String macroPhase,
            String currentRoom,
            String roomPhase,
            RoomType roomType,
            long roomEpoch,
            long processRevision,
            long roomRevision,
            long fencingToken,
            long lastCommandSequence,
            long lastCaseEventSequence,
            Instant projectedDeadlineAt,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            String projectionRef,
            String projectionSha256) {

        public AuthoritativeProcessState {
            requireText(tenantSurrogate, 128, "tenantSurrogate");
            requireText(caseId, 64, "caseId");
            requireState(macroPhase, "macroPhase");
            if (currentRoom == null
                    || !switch (currentRoom) {
                        case "INTAKE", "EVIDENCE", "HEARING", "DRAFT", "REVIEW", "OUTCOME" ->
                                true;
                        default -> false;
                    }) {
                throw new IllegalArgumentException("currentRoom is invalid");
            }
            requireState(roomPhase, "roomPhase");
            Objects.requireNonNull(roomType, "roomType must not be null");
            if (roomEpoch < 0
                    || processRevision < 0
                    || roomRevision < 0
                    || fencingToken < 1
                    || lastCommandSequence < 0
                    || lastCaseEventSequence < 0) {
                throw new IllegalArgumentException(
                        "authoritative process state revisions are invalid");
            }
            if (projectedDeadlineAt != null) {
                projectedDeadlineAt = projectedDeadlineAt.truncatedTo(ChronoUnit.MICROS);
            }
            requireText(temporalWorkflowId, 128, "temporalWorkflowId");
            requireText(temporalRunId, 128, "temporalRunId");
            requireText(temporalBuildId, 128, "temporalBuildId");
            requireReference(projectionRef, projectionSha256);
        }
    }

    private static void requireReference(String reference, String sha256) {
        if (reference == null && sha256 == null) {
            return;
        }
        if (reference == null
                || reference.length() > 1024
                || !(reference.startsWith("s3:")
                        || reference.startsWith("minio:")
                        || reference.startsWith("urn:"))) {
            throw new IllegalArgumentException("projectionRef is invalid");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("projectionSha256 is invalid");
        }
    }

    private static void requireCode(String value, String field) {
        requireText(value, 64, field);
        if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireState(String value, String field) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}

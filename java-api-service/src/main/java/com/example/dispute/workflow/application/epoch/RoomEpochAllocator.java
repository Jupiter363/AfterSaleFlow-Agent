package com.example.dispute.workflow.application.epoch;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import java.time.OffsetDateTime;
import java.util.Objects;

public interface RoomEpochAllocator {

    RoomEpochAllocation activate(ActivateRoomEpoch command);

    RoomEpochAllocation transition(TransitionRoomEpoch command);

    RoomEpochAllocation terminate(TerminateRoomEpoch command);

    RoomEpochAllocation recordTerminal(TerminalRoomEpoch command);

    record ActivateRoomEpoch(
            String caseId,
            String roomId,
            RoomType roomType,
            String macroPhase,
            String roomPhase,
            OffsetDateTime projectedDeadlineAt,
            OffsetDateTime occurredAt) {

        public ActivateRoomEpoch {
            requireText(caseId, "caseId");
            requireText(roomId, "roomId");
            Objects.requireNonNull(roomType, "roomType must not be null");
            requireText(macroPhase, "macroPhase");
            requireText(roomPhase, "roomPhase");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record TransitionRoomEpoch(
            String caseId,
            RoomType expectedRoomType,
            String nextRoomId,
            RoomType nextRoomType,
            String macroPhase,
            String roomPhase,
            OffsetDateTime projectedDeadlineAt,
            OffsetDateTime occurredAt) {

        public TransitionRoomEpoch {
            requireText(caseId, "caseId");
            Objects.requireNonNull(expectedRoomType, "expectedRoomType must not be null");
            requireText(nextRoomId, "nextRoomId");
            Objects.requireNonNull(nextRoomType, "nextRoomType must not be null");
            if (expectedRoomType == nextRoomType) {
                throw new IllegalArgumentException("an epoch transition must change room type");
            }
            requireText(macroPhase, "macroPhase");
            requireText(roomPhase, "roomPhase");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record TerminateRoomEpoch(
            String caseId,
            RoomType expectedRoomType,
            String macroPhase,
            String roomPhase,
            OffsetDateTime occurredAt) {

        public TerminateRoomEpoch {
            requireText(caseId, "caseId");
            Objects.requireNonNull(expectedRoomType, "expectedRoomType must not be null");
            requireText(macroPhase, "macroPhase");
            requireText(roomPhase, "roomPhase");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record TerminalRoomEpoch(
            String caseId,
            String roomId,
            RoomType roomType,
            String macroPhase,
            String roomPhase,
            OffsetDateTime occurredAt) {

        public TerminalRoomEpoch {
            requireText(caseId, "caseId");
            requireText(roomId, "roomId");
            Objects.requireNonNull(roomType, "roomType must not be null");
            requireText(macroPhase, "macroPhase");
            requireText(roomPhase, "roomPhase");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record RoomEpochAllocation(
            String epochId,
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            long processRevision,
            long roomRevision,
            long fencingToken,
            WriterMode writerMode,
            EpochLifecycleStatus lifecycleStatus,
            String temporalWorkflowId,
            String temporalRunId,
            RoomEpochSelection selection) {}

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

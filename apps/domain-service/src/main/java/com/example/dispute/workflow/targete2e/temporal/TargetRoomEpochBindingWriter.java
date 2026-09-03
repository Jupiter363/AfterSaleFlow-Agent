package com.example.dispute.workflow.targete2e.temporal;

import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Objects;

/** Target-only transaction participant that persists the activation-bound epoch selection. */
public interface TargetRoomEpochBindingWriter {

    default RoomEpochSelection selectSuccessor(SuccessorContext context) {
        throw new IllegalStateException(
                "TEMPORAL room epoch selection requires exact target activation authority");
    }

    void persist(BindingContext context);

    record SuccessorContext(
            String sourceEpochId,
            String tenantSurrogate,
            String caseId,
            RoomType sourceRoomType,
            long sourceRoomEpoch,
            long sourceFencingToken,
            long sourceProcessRevision,
            String sourceTemporalWorkflowId,
            RoomType nextRoomType,
            RoomEpochSelection sourceSelection) {

        public SuccessorContext {
            requireText(sourceEpochId, "sourceEpochId");
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            Objects.requireNonNull(sourceRoomType, "sourceRoomType must not be null");
            if (sourceRoomEpoch < 0 || sourceFencingToken < 1 || sourceProcessRevision < 0) {
                throw new IllegalArgumentException("source epoch coordinates are invalid");
            }
            requireText(sourceTemporalWorkflowId, "sourceTemporalWorkflowId");
            Objects.requireNonNull(nextRoomType, "nextRoomType must not be null");
            if (sourceRoomType == nextRoomType) {
                throw new IllegalArgumentException("successor room type must advance");
            }
            Objects.requireNonNull(sourceSelection, "sourceSelection must not be null");
            if (sourceSelection.writerMode()
                    != com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.TEMPORAL) {
                throw new IllegalArgumentException("source selection must be TEMPORAL");
            }
        }
    }

    record BindingContext(
            String epochId,
            String tenantSurrogate,
            String caseId,
            RoomType roomType,
            long roomEpoch,
            long fencingToken,
            RoomEpochSelection selection) {

        public BindingContext {
            requireText(epochId, "epochId");
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            Objects.requireNonNull(roomType, "roomType must not be null");
            if (roomEpoch < 0 || fencingToken < 1) {
                throw new IllegalArgumentException("room epoch or fencing token is invalid");
            }
            Objects.requireNonNull(selection, "selection must not be null");
            Objects.requireNonNull(
                    selection.targetActivationBinding(),
                    "targetActivationBinding must not be null");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

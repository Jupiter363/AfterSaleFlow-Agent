package com.example.dispute.workflow.targete2e.temporal;

import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Objects;

/** Target-only transaction participant that persists the activation-bound epoch selection. */
public interface TargetRoomEpochBindingWriter {

    void persist(BindingContext context);

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

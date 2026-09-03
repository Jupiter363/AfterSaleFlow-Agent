package com.example.dispute.workflow.targete2e.temporal;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Objects;

/** Immutable execution choice for authenticated Intake ROOM_MESSAGE commands within one epoch. */
public enum IntakeRoomMessageExecutionProfile {
    MONOLITHIC_V3,
    PARALLEL_FRAMES_V1;

    public static IntakeRoomMessageExecutionProfile forNewTargetEpoch(RoomType roomType) {
        Objects.requireNonNull(roomType, "roomType must not be null");
        return roomType == RoomType.INTAKE ? PARALLEL_FRAMES_V1 : MONOLITHIC_V3;
    }

    public static IntakeRoomMessageExecutionProfile parse(String value) {
        try {
            return valueOf(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "roomMessageExecutionProfileId is invalid", failure);
        }
    }
}

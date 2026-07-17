package com.example.dispute.workflow.temporal.room.common;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.List;

public record RoomControlSnapshot(
        String schemaVersion,
        String tenantSurrogate,
        String caseId,
        RoomType roomType,
        long roomEpoch,
        long processedCommandCount,
        long processedEventCount,
        int pendingCommandCount,
        int pendingEventCount,
        List<String> recentCommandIds,
        List<String> recentEventIds,
        boolean closeRequested,
        String closeReason,
        String protocolErrorCode) {

    public RoomControlSnapshot {
        if (!"room-control-snapshot.v1".equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be room-control-snapshot.v1");
        }
        recentCommandIds = List.copyOf(recentCommandIds);
        recentEventIds = List.copyOf(recentEventIds);
    }
}

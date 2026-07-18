package com.example.dispute.workflow.temporal.room.common;

import java.util.List;

public record RoomControlCarryState(
        String schemaVersion,
        long processedCommandCount,
        long processedEventCount,
        List<String> recentCommandIds,
        List<String> recentEventIds,
        int runGeneration,
        String protocolErrorCode) {

    static final int MAX_RECENT_IDS = 256;

    public RoomControlCarryState {
        if (!"room-control-carry-state.v1".equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be room-control-carry-state.v1");
        }
        if (processedCommandCount < 0
                || processedEventCount < 0
                || runGeneration < 0) {
            throw new IllegalArgumentException("room carry counters must not be negative");
        }
        recentCommandIds = List.copyOf(recentCommandIds);
        recentEventIds = List.copyOf(recentEventIds);
        if (recentCommandIds.size() > MAX_RECENT_IDS
                || recentEventIds.size() > MAX_RECENT_IDS) {
            throw new IllegalArgumentException("room carry recent ids exceed the bound");
        }
    }

    static RoomControlCarryState initial() {
        return new RoomControlCarryState(
                "room-control-carry-state.v1", 0, 0, List.of(), List.of(), 0, null);
    }
}

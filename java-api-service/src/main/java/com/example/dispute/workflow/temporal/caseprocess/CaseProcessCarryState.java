package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.List;

public record CaseProcessCarryState(
        String schemaVersion,
        String tenantSurrogate,
        String caseId,
        RoomType activeRoomType,
        long activeRoomEpoch,
        String activeChildWorkflowId,
        long observedProcessRevision,
        long nextCommandSequence,
        long nextCaseEventSequence,
        long processedCommandCount,
        long processedEventCount,
        List<ProcessedCommandIdentity> recentCommands,
        List<CaseDomainEventRef> bufferedEvents,
        long highestObservedEventSequence,
        int runGeneration,
        int commandRecoveryAttempts,
        int eventRecoveryAttempts,
        boolean commandManualRecoveryRequired,
        boolean eventManualRecoveryRequired,
        String protocolErrorCode) {

    public static final int MAX_RECENT_COMMANDS = 256;
    public static final int MAX_BUFFERED_EVENTS = 128;

    public CaseProcessCarryState {
        if (!"case-process-carry-state.v1".equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be case-process-carry-state.v1");
        }
        if ((tenantSurrogate == null) != (caseId == null)) {
            throw new IllegalArgumentException("tenant and case identity must be bound together");
        }
        if ((activeRoomType == null) != (activeChildWorkflowId == null)) {
            throw new IllegalArgumentException("active room and child identity must be bound together");
        }
        if (activeRoomType == null && activeRoomEpoch != -1) {
            throw new IllegalArgumentException("inactive room epoch must be -1");
        }
        if (activeRoomType != null && activeRoomEpoch < 0) {
            throw new IllegalArgumentException("active room epoch must not be negative");
        }
        if (observedProcessRevision < 0
                || nextCommandSequence < 1
                || nextCaseEventSequence < 1
                || processedCommandCount < 0
                || processedEventCount < 0
                || highestObservedEventSequence < 0
                || runGeneration < 0
                || commandRecoveryAttempts < 0
                || eventRecoveryAttempts < 0) {
            throw new IllegalArgumentException("case process counters are invalid");
        }
        recentCommands = List.copyOf(recentCommands);
        bufferedEvents = List.copyOf(bufferedEvents);
        if (recentCommands.size() > MAX_RECENT_COMMANDS) {
            throw new IllegalArgumentException("recent command cache is too large");
        }
        if (bufferedEvents.size() > MAX_BUFFERED_EVENTS) {
            throw new IllegalArgumentException("buffered event cache is too large");
        }
    }

    public static CaseProcessCarryState initial() {
        return new CaseProcessCarryState(
                "case-process-carry-state.v1",
                null,
                null,
                null,
                -1,
                null,
                0,
                1,
                1,
                0,
                0,
                List.of(),
                List.of(),
                0,
                0,
                0,
                0,
                false,
                false,
                null);
    }
}

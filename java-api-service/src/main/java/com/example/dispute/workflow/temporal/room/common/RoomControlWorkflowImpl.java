package com.example.dispute.workflow.temporal.room.common;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowQueue;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class RoomControlWorkflowImpl implements RoomControlWorkflow {

    private static final int INBOX_CAPACITY = 128;
    private static final int RECENT_ID_CAPACITY = 256;

    private final WorkflowQueue<CaseCommandRef> commandInbox =
            Workflow.newQueue(INBOX_CAPACITY);
    private final WorkflowQueue<CaseDomainEventRef> eventInbox =
            Workflow.newQueue(INBOX_CAPACITY);
    private final Set<String> recentCommandIds = new LinkedHashSet<>();
    private final Set<String> recentEventIds = new LinkedHashSet<>();

    private RoomControlStart start;
    private int pendingCommandCount;
    private int pendingEventCount;
    private long processedCommandCount;
    private long processedEventCount;
    private boolean closeRequested;
    private String closeReason;
    private String protocolErrorCode;

    @Override
    public void run(RoomControlStart start) {
        this.start = start;
        while (true) {
            if (closeRequested && pendingCommandCount == 0 && pendingEventCount == 0) {
                Workflow.await(Workflow::isEveryHandlerFinished);
                if (pendingCommandCount == 0 && pendingEventCount == 0) {
                    return;
                }
            }
            Workflow.await(
                    () ->
                            pendingCommandCount > 0
                                    || pendingEventCount > 0
                                    || closeRequested);
            drainOneCommand();
            drainOneEvent();
        }
    }

    @Override
    public void commandAccepted(CaseCommandRef command) {
        commandInbox.put(command);
        pendingCommandCount++;
    }

    @Override
    public void domainEventCommitted(CaseDomainEventRef event) {
        eventInbox.put(event);
        pendingEventCount++;
    }

    @Override
    public void close(String reasonCode) {
        closeRequested = true;
        closeReason = reasonCode == null || reasonCode.isBlank() ? "CLOSED" : reasonCode;
    }

    @Override
    public RoomControlSnapshot state() {
        return new RoomControlSnapshot(
                "room-control-snapshot.v1",
                start == null ? null : start.tenantSurrogate(),
                start == null ? null : start.caseId(),
                start == null ? null : start.roomType(),
                start == null ? 0 : start.roomEpoch(),
                processedCommandCount,
                processedEventCount,
                pendingCommandCount,
                pendingEventCount,
                new ArrayList<>(recentCommandIds),
                new ArrayList<>(recentEventIds),
                closeRequested,
                closeReason,
                protocolErrorCode);
    }

    private void drainOneCommand() {
        if (pendingCommandCount == 0) {
            return;
        }
        CaseCommandRef command = commandInbox.poll();
        if (command == null) {
            return;
        }
        pendingCommandCount--;
        if (!matches(command.tenantSurrogate(), command.caseId(), command.roomType(), command.roomEpoch())) {
            protocolErrorCode = "ROOM_COMMAND_SCOPE_MISMATCH";
            return;
        }
        if (recentCommandIds.add(command.commandId())) {
            trim(recentCommandIds);
            processedCommandCount++;
        }
    }

    private void drainOneEvent() {
        if (pendingEventCount == 0) {
            return;
        }
        CaseDomainEventRef event = eventInbox.poll();
        if (event == null) {
            return;
        }
        pendingEventCount--;
        if (!matches(event.tenantSurrogate(), event.caseId(), event.roomType(), event.roomEpoch())) {
            protocolErrorCode = "ROOM_EVENT_SCOPE_MISMATCH";
            return;
        }
        if (recentEventIds.add(event.eventId())) {
            trim(recentEventIds);
            processedEventCount++;
        }
    }

    private boolean matches(
            String tenantSurrogate,
            String caseId,
            RoomType roomType,
            long roomEpoch) {
        return start != null
                && start.tenantSurrogate().equals(tenantSurrogate)
                && start.caseId().equals(caseId)
                && start.roomType().equals(roomType)
                && start.roomEpoch() == roomEpoch;
    }

    private static void trim(Set<String> values) {
        if (values.size() <= RECENT_ID_CAPACITY) {
            return;
        }
        String first = values.iterator().next();
        values.remove(first);
    }
}

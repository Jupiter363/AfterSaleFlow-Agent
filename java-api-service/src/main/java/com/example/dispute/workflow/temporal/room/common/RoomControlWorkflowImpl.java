package com.example.dispute.workflow.temporal.room.common;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowQueue;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class RoomControlWorkflowImpl implements RoomControlWorkflow {

  private static final int INBOX_CAPACITY = 128;
  private static final int RECENT_ID_CAPACITY = 256;
  private static final String CARRY_STATE_MEMO_KEY = "room_control_carry_state_v1";
  private static final String ROOM_ROLLOVER_CHANGE_ID = "room-control-rollover-v1";
  private static final Duration RUN_MAX_AGE = Duration.ofHours(24);
  private static final long HISTORY_EVENT_LIMIT = 2_000;

  private final WorkflowQueue<CaseCommandRef> commandInbox = Workflow.newQueue(INBOX_CAPACITY);
  private final WorkflowQueue<CaseDomainEventRef> eventInbox = Workflow.newQueue(INBOX_CAPACITY);
  private final Set<String> recentCommandIds = new LinkedHashSet<>();
  private final Set<String> recentEventIds = new LinkedHashSet<>();

  private RoomControlStart start;
  private int pendingCommandCount;
  private int pendingEventCount;
  private long processedCommandCount;
  private long processedEventCount;
  private int runGeneration;
  private boolean closeRequested;
  private String closeReason;
  private String protocolErrorCode;
  private Promise<Void> runMaxAgeTimer;
  private boolean rolloverEnabled;

  @Override
  public void run(RoomControlStart start) {
    this.start = start;
    restoreCarryState();
    rolloverEnabled =
        Workflow.getVersion(ROOM_ROLLOVER_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
    if (rolloverEnabled) {
      runMaxAgeTimer = Workflow.newTimer(RUN_MAX_AGE);
    }
    while (true) {
      if (closeRequested && pendingCommandCount == 0 && pendingEventCount == 0) {
        Workflow.await(Workflow::isEveryHandlerFinished);
        if (pendingCommandCount == 0 && pendingEventCount == 0) {
          return;
        }
      }
      if (shouldContinueAsNew() && canContinueAsNew()) {
        continueAsNew();
        return;
      }
      Workflow.await(
          () ->
              pendingCommandCount > 0
                  || pendingEventCount > 0
                  || closeRequested
                  || (shouldContinueAsNew() && canContinueAsNew()));
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
        Workflow.getInfo().getRunId(),
        runGeneration,
        processedCommandCount,
        processedEventCount,
        pendingCommandCount,
        pendingEventCount,
        new ArrayList<>(recentCommandIds),
        new ArrayList<>(recentEventIds),
        closeRequested,
        closeReason,
        protocolErrorCode,
        Workflow.getInfo().getWorkflowId(),
        start == null ? 0 : start.fencingToken(),
        start == null ? null : start.writerMode(),
        start == null ? null : start.selectionSchemaVersion(),
        start == null ? null : start.processContractVersion(),
        start == null ? null : start.workflowType(),
        start == null ? null : start.temporalBuildId(),
        start == null ? null : start.graphKey(),
        start == null ? null : start.graphVersion(),
        start == null ? null : start.checkpointSchemaVersion(),
        start == null ? null : start.streamProtocol(),
        start == null ? null : start.parentWorkflowId(),
        start == null ? null : start.parentWorkflowRunId(),
        start == null ? null : start.provisioningSha256(),
        start == null ? null : start.epochId(),
        start == null ? null : start.roomId(),
        start == null ? 0 : start.initialProcessRevision(),
        start == null ? 0 : start.initialRoomRevision(),
        start == null ? null : start.macroPhase(),
        start == null ? null : start.currentRoom(),
        start == null ? null : start.roomPhase(),
        start == null ? null : start.projectedDeadlineAt(),
        start == null ? 0 : start.lastCommandSequence(),
        start == null ? 0 : start.lastCaseEventSequence(),
        start == null ? 1 : start.firstCommandSequence(),
        start == null ? 1 : start.firstCaseEventSequence(),
        start == null ? null : start.projectionRef(),
        start == null ? null : start.projectionSha256(),
        start == null ? null : start.requestedAt());
  }

  @Override
  public ProvisionRoomEpochReceipt provisioningReceipt() {
    if (start == null || start.fencingToken() < 1 || start.provisioningSha256() == null) {
      return null;
    }
    return new ProvisionRoomEpochReceipt(
        "provision-room-epoch-receipt.v1",
        start.epochId(),
        start.tenantSurrogate(),
        start.caseId(),
        start.roomId(),
        start.roomType(),
        start.roomEpoch(),
        start.fencingToken(),
        start.initialProcessRevision(),
        start.initialRoomRevision(),
        start.macroPhase(),
        start.currentRoom(),
        start.roomPhase(),
        start.projectedDeadlineAt(),
        start.writerMode(),
        start.selectionSchemaVersion(),
        start.processContractVersion(),
        start.workflowType(),
        start.temporalBuildId(),
        start.graphKey(),
        start.graphVersion(),
        start.checkpointSchemaVersion(),
        start.streamProtocol(),
        start.lastCommandSequence(),
        start.lastCaseEventSequence(),
        start.firstCommandSequence(),
        start.firstCaseEventSequence(),
        start.projectionRef(),
        start.projectionSha256(),
        start.requestedAt(),
        start.parentWorkflowId(),
        start.parentWorkflowRunId(),
        Workflow.getInfo().getWorkflowId(),
        Workflow.getInfo().getFirstExecutionRunId(),
        start.provisioningSha256());
  }

  private void restoreCarryState() {
    RoomControlCarryState carry = RoomControlCarryState.initial();
    if (Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
      carry = start.carryState();
      if (carry == null) {
        carry =
            (RoomControlCarryState)
                Workflow.getMemo(CARRY_STATE_MEMO_KEY, RoomControlCarryState.class);
      }
      if (carry == null) {
        throw new IllegalStateException("continued room workflow is missing carry state");
      }
    }
    processedCommandCount = carry.processedCommandCount();
    processedEventCount = carry.processedEventCount();
    recentCommandIds.addAll(carry.recentCommandIds());
    recentEventIds.addAll(carry.recentEventIds());
    runGeneration = carry.runGeneration();
    protocolErrorCode = carry.protocolErrorCode();
  }

  private boolean shouldContinueAsNew() {
    return rolloverEnabled
        && ((runMaxAgeTimer != null && runMaxAgeTimer.isCompleted())
            || Workflow.getInfo().isContinueAsNewSuggested()
            || Workflow.getInfo().getHistoryLength() >= HISTORY_EVENT_LIMIT);
  }

  private boolean canContinueAsNew() {
    return !closeRequested
        && pendingCommandCount == 0
        && pendingEventCount == 0
        && Workflow.isEveryHandlerFinished();
  }

  private void continueAsNew() {
    Workflow.await(Workflow::isEveryHandlerFinished);
    RoomControlCarryState carry =
        new RoomControlCarryState(
            "room-control-carry-state.v1",
            processedCommandCount,
            processedEventCount,
            new ArrayList<>(recentCommandIds),
            new ArrayList<>(recentEventIds),
            runGeneration + 1,
            protocolErrorCode);
    ContinueAsNewOptions options =
        ContinueAsNewOptions.newBuilder().setMemo(Map.of(CARRY_STATE_MEMO_KEY, carry)).build();
    Workflow.continueAsNew(options, start.withCarryState(carry));
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
    if (!matches(
        command.tenantSurrogate(), command.caseId(), command.roomType(), command.roomEpoch())) {
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
      String tenantSurrogate, String caseId, RoomType roomType, long roomEpoch) {
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

package com.example.dispute.workflow.temporal.room.intake;

import io.temporal.workflow.Workflow;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class IntakeRoomWorkflowImpl implements IntakeRoomWorkflow {

  private static final int RECENT_CAPACITY = 256;

  private final ArrayDeque<InboxItem> inbox = new ArrayDeque<>();
  private final Map<String, CommandObservation> commandObservations = new LinkedHashMap<>();
  private final Map<String, EventObservation> eventObservations = new LinkedHashMap<>();

  private IntakeRoomStart start;
  private IntakeRoomPhase roomPhase = IntakeRoomPhase.OPEN;
  private IntakeParty activeParty = IntakeParty.INITIATOR;
  private long nextCommandSequence;
  private long nextEventSequence;
  private long processedCommandCount;
  private long processedEventCount;
  private boolean initiatorComplete;
  private boolean respondentUnlocked;
  private boolean respondentComplete;
  private IntakeParty readinessParty;
  private IntakePendingCommand pendingCommand;
  private String lastEventId;
  private String lastEventRef;
  private String lastEventHash;
  private IntakeAgentRunRef lastAgentRunRef;
  private IntakeGraphExecutionRef lastGraphExecutionRef;
  private IntakeTerminalReason terminalReason;
  private long processRevision;
  private long roomRevision;
  private String protocolErrorCode;
  private IntakeCommandDecision lastDecision;

  @Override
  public IntakeRoomSnapshot run(IntakeRoomStart start) {
    this.start = Objects.requireNonNull(start, "start must not be null");
    nextCommandSequence = start.firstCommandSequence();
    nextEventSequence = start.firstEventSequence();
    processRevision = start.initialProcessRevision();
    roomRevision = start.initialRoomRevision();

    while (true) {
      if (!inbox.isEmpty()) {
        processNextInput(inbox.removeFirst());
        continue;
      }
      if (roomPhase == IntakeRoomPhase.COMPLETED) {
        Workflow.await(Workflow::isEveryHandlerFinished);
        if (inbox.isEmpty()) {
          return state();
        }
        continue;
      }
      Workflow.await(() -> !inbox.isEmpty());
    }
  }

  @Override
  public void commandAccepted(IntakeWorkflowCommand command) {
    inbox.addLast(new CommandInput(Objects.requireNonNull(command, "command must not be null")));
  }

  @Override
  public void domainEventCommitted(IntakeDomainEventRef event) {
    inbox.addLast(new EventInput(Objects.requireNonNull(event, "event must not be null")));
  }

  @Override
  public IntakeRoomSnapshot state() {
    return new IntakeRoomSnapshot(
        "intake-room-snapshot.v1",
        start == null ? null : start.tenantSurrogate(),
        start == null ? null : start.caseId(),
        start == null ? 0 : start.roomEpoch(),
        start == null ? 0 : start.fencingToken(),
        start == null ? null : start.initiatorActorScopeHash(),
        start == null ? null : start.respondentActorScopeHash(),
        roomPhase,
        activeParty,
        nextCommandSequence,
        nextEventSequence,
        processedCommandCount,
        processedEventCount,
        initiatorComplete,
        respondentUnlocked,
        respondentComplete,
        readinessParty,
        pendingCommand,
        lastEventId,
        lastEventRef,
        lastEventHash,
        lastAgentRunRef,
        lastGraphExecutionRef,
        terminalReason,
        processRevision,
        roomRevision,
        protocolErrorCode);
  }

  @Override
  public IntakeCommandDecision lastCommandDecision() {
    return lastDecision;
  }

  private void processNextInput(InboxItem input) {
    if (input instanceof CommandInput commandInput) {
      processCommand(commandInput.command());
      return;
    }
    processEvent(((EventInput) input).event());
  }

  private void processCommand(IntakeWorkflowCommand command) {
    CommandObservation observed = commandObservations.get(command.commandId());
    if (observed != null) {
      if (!observed.command().equals(command)) {
        rejectCommand(command, "COMMAND_ID_REUSE_CONFLICT", false);
        return;
      }
      if (observed.decision() != null) {
        replayCommand(observed);
        return;
      }
    } else {
      observed = new CommandObservation(command, null);
      commandObservations.put(command.commandId(), observed);
      trim(commandObservations);
    }

    if (command.sequence() < nextCommandSequence) {
      rejectCommand(command, "COMMAND_SEQUENCE_REPLAY_UNKNOWN", true);
      return;
    }
    if (command.sequence() > nextCommandSequence) {
      rejectCommand(command, "COMMAND_SEQUENCE_GAP", false);
      return;
    }
    if (!matchesEnvelope(command)) {
      rejectCommand(command, "COMMAND_SCOPE_MISMATCH", true);
      return;
    }
    if (!matchesPartyScope(command.party(), command.actorScopeHash())) {
      rejectCommand(command, "COMMAND_ACTOR_SCOPE_MISMATCH", true);
      return;
    }
    String rejection = businessRejection(command);
    if (rejection != null) {
      rejectCommand(command, rejection, true);
      return;
    }

    nextCommandSequence++;
    processedCommandCount++;
    protocolErrorCode = null;
    pendingCommand = IntakePendingCommand.from(command);
    activeParty = command.party();
    if (command.commandType() == IntakeCommandType.INTAKE_MESSAGE) {
      roomPhase = IntakeRoomPhase.AGENT_RUNNING;
      readinessParty = null;
    }
    IntakeCommandDecision accepted =
        decision(command, "ACCEPTED", null);
    lastDecision = accepted;
    commandObservations.put(command.commandId(), new CommandObservation(command, accepted));
  }

  private void processEvent(IntakeDomainEventRef event) {
    EventObservation observed = eventObservations.get(event.eventId());
    if (observed != null) {
      if (!observed.event().equals(event)) {
        protocolErrorCode = "EVENT_ID_REUSE_CONFLICT";
        return;
      }
      if (observed.applied()) {
        protocolErrorCode = null;
        return;
      }
    } else {
      observed = new EventObservation(event, false);
      eventObservations.put(event.eventId(), observed);
      trim(eventObservations);
    }

    if (event.eventSequence() < nextEventSequence) {
      protocolErrorCode = "EVENT_SEQUENCE_REPLAY_UNKNOWN";
      return;
    }
    if (event.eventSequence() > nextEventSequence) {
      protocolErrorCode = "EVENT_SEQUENCE_GAP";
      return;
    }
    if (!matchesEnvelope(event)) {
      protocolErrorCode = "EVENT_SCOPE_MISMATCH";
      return;
    }
    if (!matchesPartyScope(event.party(), event.actorScopeHash())) {
      protocolErrorCode = "EVENT_ACTOR_SCOPE_MISMATCH";
      return;
    }
    if (roomPhase == IntakeRoomPhase.COMPLETED) {
      protocolErrorCode = "EVENT_AFTER_COMPLETION";
      return;
    }
    if (pendingCommand == null) {
      protocolErrorCode = "EVENT_WITHOUT_PENDING_COMMAND";
      return;
    }
    if (!pendingCommand.commandId().equals(event.commandId())) {
      protocolErrorCode = "EVENT_COMMAND_MISMATCH";
      return;
    }
    if (!pendingCommand.operationKey().equals(event.operationKey())) {
      protocolErrorCode = "EVENT_OPERATION_KEY_MISMATCH";
      return;
    }
    if (!pendingCommand.requestHash().equals(event.requestHash())) {
      protocolErrorCode = "EVENT_REQUEST_HASH_MISMATCH";
      return;
    }
    if (pendingCommand.party() != event.party()
        || !pendingCommand.actorScopeHash().equals(event.actorScopeHash())) {
      protocolErrorCode = "EVENT_PENDING_SCOPE_MISMATCH";
      return;
    }
    if (!eventAllowedForPendingCommand(event.eventType())) {
      protocolErrorCode = "EVENT_TYPE_NOT_ALLOWED_FOR_COMMAND";
      return;
    }
    if (event.processRevision() < processRevision || event.roomRevision() < roomRevision) {
      protocolErrorCode = "EVENT_STALE_REVISION";
      return;
    }
    if (event.graphExecutionRef() != null
        && !start.graphVersion().equals(event.graphExecutionRef().graphVersion())) {
      protocolErrorCode = "EVENT_GRAPH_VERSION_MISMATCH";
      return;
    }
    if (event.graphExecutionRef() != null
        && !pendingCommand.commandId().equals(event.graphExecutionRef().graphCommandId())) {
      protocolErrorCode = "EVENT_GRAPH_COMMAND_MISMATCH";
      return;
    }
    if (!applyEvent(event)) {
      return;
    }

    eventObservations.put(event.eventId(), new EventObservation(event, true));
    nextEventSequence++;
    processedEventCount++;
    processRevision = event.processRevision();
    roomRevision = event.roomRevision();
    lastEventId = event.eventId();
    lastEventRef = event.eventRef();
    lastEventHash = event.eventHash();
    if (event.agentRunRef() != null) {
      lastAgentRunRef = event.agentRunRef();
      lastGraphExecutionRef = event.graphExecutionRef();
    }
    pendingCommand = null;
    protocolErrorCode = null;
  }

  private void replayCommand(CommandObservation observed) {
    IntakeCommandDecision previous = observed.decision();
    lastDecision =
        new IntakeCommandDecision(
            "intake-command-decision.v1",
            previous.commandId(),
            previous.sequence(),
            "DUPLICATE",
            previous.reasonCode(),
            roomPhase,
            previous.requestHash());
    protocolErrorCode = previous.reasonCode();
  }

  private String businessRejection(IntakeWorkflowCommand command) {
    if (roomPhase == IntakeRoomPhase.COMPLETED) {
      return "INTAKE_ALREADY_COMPLETED";
    }
    if (command.commandType() == IntakeCommandType.INTAKE_CANCEL
        && command.party() == IntakeParty.RESPONDENT) {
      return "RESPONDENT_CANCEL_FORBIDDEN";
    }
    if (command.party() == IntakeParty.RESPONDENT && !respondentUnlocked) {
      return "RESPONDENT_LOCKED";
    }
    if (pendingCommand != null) {
      return "INTAKE_OPERATION_PENDING";
    }
    if (command.commandType() == IntakeCommandType.INTAKE_CANCEL) {
      return null;
    }
    if (command.commandType() == IntakeCommandType.INTAKE_MESSAGE) {
      if (command.party() == IntakeParty.INITIATOR && initiatorComplete) {
        return "INITIATOR_ALREADY_COMPLETE";
      }
      if (command.party() == IntakeParty.RESPONDENT && respondentComplete) {
        return "RESPONDENT_ALREADY_COMPLETE";
      }
      return null;
    }
    if (command.commandType() == IntakeCommandType.INTAKE_CONFIRM) {
      if (roomPhase != IntakeRoomPhase.READY_TO_CONFIRM || readinessParty != command.party()) {
        return "PARTY_NOT_READY_TO_CONFIRM";
      }
      return null;
    }
    return "COMMAND_TYPE_UNSUPPORTED";
  }

  private boolean eventAllowedForPendingCommand(IntakeDomainEventType eventType) {
    return switch (pendingCommand.commandType()) {
      case INTAKE_MESSAGE ->
          eventType == IntakeDomainEventType.TURN_NEEDS_INPUT
              || eventType == IntakeDomainEventType.TURN_READY_TO_CONFIRM;
      case INTAKE_CONFIRM ->
          pendingCommand.party() == IntakeParty.INITIATOR
              ? eventType == IntakeDomainEventType.INITIATOR_ACCEPTED
                  || eventType == IntakeDomainEventType.NOT_ADMISSIBLE
              : eventType == IntakeDomainEventType.RESPONDENT_CONFIRMED;
      case INTAKE_CANCEL ->
          pendingCommand.party() == IntakeParty.INITIATOR
              && eventType == IntakeDomainEventType.CANCELLED;
    };
  }

  private boolean applyEvent(IntakeDomainEventRef event) {
    switch (event.eventType()) {
      case TURN_NEEDS_INPUT -> {
        roomPhase = IntakeRoomPhase.WAITING_PARTY;
        activeParty = event.party();
        readinessParty = null;
      }
      case TURN_READY_TO_CONFIRM -> {
        roomPhase = IntakeRoomPhase.READY_TO_CONFIRM;
        activeParty = event.party();
        readinessParty = event.party();
      }
      case INITIATOR_ACCEPTED -> {
        if (roomPhase != IntakeRoomPhase.READY_TO_CONFIRM
            || readinessParty != IntakeParty.INITIATOR) {
          protocolErrorCode = "INITIATOR_ACCEPT_EVENT_INVALID";
          return false;
        }
        initiatorComplete = true;
        respondentUnlocked = true;
        activeParty = IntakeParty.RESPONDENT;
        readinessParty = null;
        roomPhase = IntakeRoomPhase.WAITING_PARTY;
      }
      case NOT_ADMISSIBLE -> {
        if (roomPhase != IntakeRoomPhase.READY_TO_CONFIRM
            || readinessParty != IntakeParty.INITIATOR) {
          protocolErrorCode = "NOT_ADMISSIBLE_EVENT_INVALID";
          return false;
        }
        activeParty = IntakeParty.INITIATOR;
        terminalReason = IntakeTerminalReason.NOT_ADMISSIBLE;
        roomPhase = IntakeRoomPhase.COMPLETED;
      }
      case CANCELLED -> {
        activeParty = IntakeParty.INITIATOR;
        terminalReason = IntakeTerminalReason.CANCELLED;
        roomPhase = IntakeRoomPhase.COMPLETED;
      }
      case RESPONDENT_CONFIRMED -> {
        if (!initiatorComplete
            || roomPhase != IntakeRoomPhase.READY_TO_CONFIRM
            || readinessParty != IntakeParty.RESPONDENT) {
          protocolErrorCode = "RESPONDENT_CONFIRM_EVENT_INVALID";
          return false;
        }
        respondentComplete = true;
        activeParty = IntakeParty.RESPONDENT;
        terminalReason = IntakeTerminalReason.ADMITTED;
        roomPhase = IntakeRoomPhase.COMPLETED;
      }
    }
    return true;
  }

  private void rejectCommand(
      IntakeWorkflowCommand command, String code, boolean rememberDecision) {
    protocolErrorCode = code;
    IntakeCommandDecision rejected = decision(command, "REJECTED", code);
    lastDecision = rejected;
    if (rememberDecision) {
      CommandObservation observed = commandObservations.get(command.commandId());
      if (observed != null && observed.command().equals(command)) {
        commandObservations.put(command.commandId(), new CommandObservation(command, rejected));
      }
    }
  }

  private IntakeCommandDecision decision(
      IntakeWorkflowCommand command, String status, String reasonCode) {
    return new IntakeCommandDecision(
        "intake-command-decision.v1",
        command.commandId(),
        command.sequence(),
        status,
        reasonCode,
        roomPhase,
        command.requestHash());
  }

  private boolean matchesEnvelope(IntakeWorkflowCommand command) {
    return start.tenantSurrogate().equals(command.tenantSurrogate())
        && start.caseId().equals(command.caseId())
        && start.roomEpoch() == command.roomEpoch()
        && start.fencingToken() == command.fencingToken();
  }

  private boolean matchesEnvelope(IntakeDomainEventRef event) {
    return start.tenantSurrogate().equals(event.tenantSurrogate())
        && start.caseId().equals(event.caseId())
        && start.roomEpoch() == event.roomEpoch()
        && start.fencingToken() == event.fencingToken();
  }

  private boolean matchesPartyScope(IntakeParty party, String actorScopeHash) {
    return expectedActorScope(party).equals(actorScopeHash);
  }

  private String expectedActorScope(IntakeParty party) {
    return party == IntakeParty.INITIATOR
        ? start.initiatorActorScopeHash()
        : start.respondentActorScopeHash();
  }

  private static void trim(Map<String, ?> values) {
    if (values.size() > RECENT_CAPACITY) {
      values.remove(values.keySet().iterator().next());
    }
  }

  private sealed interface InboxItem permits CommandInput, EventInput {}

  private record CommandInput(IntakeWorkflowCommand command) implements InboxItem {}

  private record EventInput(IntakeDomainEventRef event) implements InboxItem {}

  private record CommandObservation(
      IntakeWorkflowCommand command, IntakeCommandDecision decision) {}

  private record EventObservation(IntakeDomainEventRef event, boolean applied) {}
}

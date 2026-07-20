package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class IntakeRoomWorkflowImpl implements IntakeRoomWorkflow {

  private static final int RECENT_CAPACITY = 256;
  private static final String CARRY_STATE_MEMO_KEY = "intake_room_carry_state_v1";
  private static final String ROLLOVER_CHANGE_ID = "intake-room-rollover-v1";
  private static final long HISTORY_EVENT_LIMIT = 2_000;
  private static final Duration RUN_MAX_AGE = Duration.ofHours(24);

  private final ArrayDeque<InboxItem> inbox = new ArrayDeque<>();
  private final Map<String, CommandObservation> commandObservations = new LinkedHashMap<>();
  private final Map<String, EventObservation> eventObservations = new LinkedHashMap<>();
  private final Map<IntakeParty, IntakeThreadInitialization> threadInitializations =
      new EnumMap<>(IntakeParty.class);

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
  private IntakeActivityExecutionState activityExecution;
  private int runGeneration;
  private io.temporal.workflow.Promise<Void> runMaxAgeTimer;
  private boolean rolloverEnabled;

  @Override
  public IntakeRoomSnapshot run(IntakeRoomStart start) {
    this.start = Objects.requireNonNull(start, "start must not be null");
    restoreCarryState(start.carryState());
    rolloverEnabled =
        Workflow.getVersion(ROLLOVER_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
    if (rolloverEnabled) {
      runMaxAgeTimer = Workflow.newTimer(RUN_MAX_AGE);
    }

    while (true) {
      if (shouldContinueAsNew() && canContinueAsNew()) {
        continueAsNew();
        return null;
      }
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
      Workflow.await(
          () ->
              !inbox.isEmpty()
                  || (shouldContinueAsNew() && canContinueAsNew()));
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
        protocolErrorCode,
        runGeneration,
        activityExecution);
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
        if (pendingCommand != null
            && pendingCommand.commandId().equals(command.commandId())
            && command.executionContext() != null) {
          orchestrate(command);
        }
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
    if (command.executionContext() != null) {
      if (command.executionContext().deadlineEpochMillis() <= Workflow.currentTimeMillis()) {
        rejectCommand(command, "COMMAND_DEADLINE_EXPIRED", true);
        return;
      }
      if (!bindsThread(command)) {
        rejectCommand(command, "COMMAND_THREAD_BINDING_MISMATCH", true);
        return;
      }
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
    if (command.executionContext() != null) {
      orchestrate(command);
    }
  }

  private void processEvent(IntakeDomainEventRef event) {
    processEvent(event, null);
  }

  private void processEvent(
      IntakeDomainEventRef event, String expectedActivityOperationKey) {
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
    boolean rootOperation = pendingCommand.operationKey().equals(event.operationKey());
    boolean stageOperation =
        (expectedActivityOperationKey != null
                && expectedActivityOperationKey.equals(event.operationKey()))
            || (activityExecution != null
                && activityExecution.stageOperationKey().equals(event.operationKey()));
    if (!rootOperation && !stageOperation) {
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
    activityExecution = null;
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

  /** Runs only immutable-reference Activities; all formal state still arrives as a committed ref. */
  private void orchestrate(IntakeWorkflowCommand command) {
    IntakeCommandExecutionContext context = command.executionContext();
    try {
      IntakeRoomActivities activities =
          Workflow.newActivityStub(
              IntakeRoomActivities.class, IntakeActivityTemporalPolicy.options(context.retryBudget()));
      ActivityEnvelope envelope = activityEnvelope(command, context);
      if (command.commandType() == IntakeCommandType.INTAKE_MESSAGE) {
        ensureSnapshot(activities, envelope, command, context);
        GraphExecutionReceipt graph = executeGraph(activities, envelope, command, context);
        TurnFinalizationReceipt finalization =
            finalizeTurn(activities, envelope, command, context, graph);
        processEvent(finalization.committedEvent(), finalization.operation().operationKey());
        return;
      }
      BranchCommitReceipt branch = executeBranch(activities, envelope, command, context);
      processEvent(branch.committedEvent(), branch.operation().operationKey());
    } catch (ActivityFailure failure) {
      if (failure.getCause() instanceof CanceledFailure) {
        throw failure;
      }
      protocolErrorCode = activityFailureCode(failure);
    } catch (RuntimeException failure) {
      // Receipt validation failures are fail-closed and leave the command pending for recovery.
      protocolErrorCode = "INTAKE_ACTIVITY_RECEIPT_INVALID";
    }
  }

  private void ensureSnapshot(
      IntakeRoomActivities activities,
      ActivityEnvelope envelope,
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context) {
    IntakeThreadInitialization existing = threadInitializations.get(command.party());
    if (existing != null) {
      requireThreadBinding(existing, command, context);
      return;
    }
    long domainRevision = processRevision;
    String operationKey =
        IntakeOperationKeys.snapshotPublish(
            command.caseId(), command.roomEpoch(), command.actorScopeHash(), domainRevision);
    setActivityStage(command, context, IntakeActivityStage.SNAPSHOT_PUBLICATION, operationKey);
    SnapshotPublicationRequest request =
        new SnapshotPublicationRequest(
            "intake-snapshot-publication-request.v1",
            envelope,
            context.threadId(),
            context.agentSessionId(),
            domainRevision,
            operationKey,
            command.requestHash());
    SnapshotPublicationReceipt receipt = activities.publishSnapshot(request);
    requireOperation(receipt.operation(), operationKey, command.requestHash());
    if (receipt.domainRevision() != domainRevision) {
      throw new IllegalArgumentException("snapshot receipt revision does not match the request");
    }
    threadInitializations.put(
        command.party(),
        new IntakeThreadInitialization(
            "intake-thread-initialization.v1",
            command.party(),
            command.actorScopeHash(),
            context.threadId(),
            context.agentSessionId(),
            domainRevision,
            receipt));
  }

  private GraphExecutionReceipt executeGraph(
      IntakeRoomActivities activities,
      ActivityEnvelope envelope,
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context) {
    String operationKey =
        IntakeOperationKeys.graphExecute(
            command.caseId(), command.roomEpoch(), context.threadId(), command.commandId());
    setActivityStage(command, context, IntakeActivityStage.GRAPH_EXECUTION, operationKey);
    GraphExecutionReceipt receipt =
        activities.executeGraph(
            new GraphExecutionRequest(
                "intake-graph-execution-request.v1",
                envelope,
                context.threadId(),
                context.agentSessionId(),
                operationKey,
                command.requestHash()));
    requireOperation(receipt.operation(), operationKey, command.requestHash());
    return receipt;
  }

  private TurnFinalizationReceipt finalizeTurn(
      IntakeRoomActivities activities,
      ActivityEnvelope envelope,
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context,
      GraphExecutionReceipt graph) {
    String operationKey =
        IntakeOperationKeys.turnFinalize(
            command.caseId(),
            command.roomEpoch(),
            context.threadId(),
            command.commandId(),
            graph.operation().resultHash());
    setActivityStage(command, context, IntakeActivityStage.TURN_FINALIZATION, operationKey);
    TurnFinalizationReceipt receipt =
        activities.finalizeTurn(
            new TurnFinalizationRequest(
                "intake-turn-finalization-request.v1",
                envelope,
                context.threadId(),
                context.agentSessionId(),
                graph,
                operationKey,
                command.requestHash()));
    requireOperation(receipt.operation(), operationKey, command.requestHash());
    if (!graph.operation().resultHash().equals(receipt.operation().resultHash())) {
      throw new IllegalArgumentException("finalization result does not match graph result");
    }
    return receipt;
  }

  private BranchCommitReceipt executeBranch(
      IntakeRoomActivities activities,
      ActivityEnvelope envelope,
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context) {
    BranchOperation operation = context.branchOperation();
    IntakeActivityStage stage =
        switch (operation) {
          case INITIATOR_ACCEPT -> IntakeActivityStage.INITIATOR_ACCEPTANCE;
          case INITIATOR_REJECT -> IntakeActivityStage.INITIATOR_REJECTION;
          case CANCEL -> IntakeActivityStage.CANCELLATION;
          case RESPONDENT_CONFIRM -> IntakeActivityStage.RESPONDENT_CONFIRMATION;
        };
    String operationKey = branchOperationKey(operation, command);
    setActivityStage(command, context, stage, operationKey);
    BranchCommitRequest request =
        new BranchCommitRequest(
            "intake-branch-commit-request.v1",
            envelope,
            operation,
            operationKey,
            command.requestHash());
    BranchCommitReceipt receipt =
        switch (operation) {
      case INITIATOR_ACCEPT -> activities.acceptInitiator(request);
      case INITIATOR_REJECT -> activities.rejectInitiator(request);
      case CANCEL -> activities.cancelIntake(request);
      case RESPONDENT_CONFIRM -> activities.confirmRespondent(request);
    };
    requireOperation(receipt.operation(), operationKey, command.requestHash());
    return receipt;
  }

  private ActivityEnvelope activityEnvelope(
      IntakeWorkflowCommand command, IntakeCommandExecutionContext context) {
    PinnedVersions versions =
        new PinnedVersions(
            "intake-pinned-versions.v1",
            start.workflowBuildId(),
            start.graphVersion(),
            start.checkpointSchemaVersion(),
            start.promptVersion(),
            start.modelProfileId(),
            start.outputSchemaVersion(),
            start.policyVersion(),
            start.guardrailVersion(),
            start.toolPolicyVersion());
    return new ActivityEnvelope(
        "intake-activity-envelope.v1",
        command.tenantSurrogate(),
        command.caseId(),
        command.roomEpoch(),
        command.fencingToken(),
        command.commandId(),
        command.sequence(),
        command.commandType(),
        command.party(),
        command.actorScopeHash(),
        command.payloadRef(),
        command.payloadHash(),
        processRevision,
        roomRevision,
        context.deadlineEpochMillis(),
        context.retryBudget(),
        versions);
  }

  private void setActivityStage(
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context,
      IntakeActivityStage stage,
      String operationKey) {
    activityExecution =
        new IntakeActivityExecutionState(
            "intake-activity-execution-state.v1",
            command.commandId(),
            command.operationKey(),
            stage,
            operationKey,
            command.requestHash(),
            context.threadId(),
            context.agentSessionId(),
            context.deadlineEpochMillis(),
            context.retryBudget());
  }

  private static void requireOperation(
      OperationReceipt receipt, String expectedOperationKey, String expectedRequestHash) {
    if (receipt == null
        || !expectedOperationKey.equals(receipt.operationKey())
        || !expectedRequestHash.equals(receipt.requestHash())) {
      throw new IllegalArgumentException("Activity receipt does not match its request");
    }
  }

  private static String branchOperationKey(
      BranchOperation operation, IntakeWorkflowCommand command) {
    return switch (operation) {
      case INITIATOR_ACCEPT ->
          IntakeOperationKeys.initiatorAccept(
              command.caseId(), command.roomEpoch(), command.commandId());
      case INITIATOR_REJECT ->
          IntakeOperationKeys.initiatorReject(
              command.caseId(), command.roomEpoch(), command.commandId());
      case CANCEL ->
          IntakeOperationKeys.cancel(command.caseId(), command.roomEpoch(), command.commandId());
      case RESPONDENT_CONFIRM ->
          IntakeOperationKeys.respondentConfirm(
              command.caseId(), command.roomEpoch(), command.commandId());
    };
  }

  private boolean bindsThread(
      IntakeWorkflowCommand command) {
    IntakeCommandExecutionContext context = command.executionContext();
    IntakeThreadInitialization existing = threadInitializations.get(command.party());
    if (existing != null) {
      try {
        requireThreadBinding(existing, command, context);
      } catch (IllegalArgumentException mismatch) {
        return false;
      }
    }
    for (IntakeThreadInitialization initialization : threadInitializations.values()) {
      if (initialization.party() != command.party()
          && initialization.threadId().equals(context.threadId())) {
        return false;
      }
    }
    return true;
  }

  private static void requireThreadBinding(
      IntakeThreadInitialization existing,
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context) {
    if (!existing.actorScopeHash().equals(command.actorScopeHash())
        || !existing.threadId().equals(context.threadId())
        || !existing.agentSessionId().equals(context.agentSessionId())) {
      throw new IllegalArgumentException("command does not match the private thread binding");
    }
  }

  private static String activityFailureCode(ActivityFailure failure) {
    if (failure.getCause() instanceof ApplicationFailure applicationFailure) {
      return applicationFailure.getType();
    }
    return "INTAKE_ACTIVITY_RETRY_EXHAUSTED";
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
        readinessParty = null;
        terminalReason = IntakeTerminalReason.NOT_ADMISSIBLE;
        roomPhase = IntakeRoomPhase.COMPLETED;
      }
      case CANCELLED -> {
        activeParty = IntakeParty.INITIATOR;
        readinessParty = null;
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
        readinessParty = null;
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

  private void restoreCarryState(IntakeRoomCarryState carry) {
    if (carry == null && Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
      carry =
          (IntakeRoomCarryState)
              Workflow.getMemo(CARRY_STATE_MEMO_KEY, IntakeRoomCarryState.class);
      if (carry == null) {
        throw new IllegalStateException("continued Intake workflow is missing carry state");
      }
    }
    if (carry == null) {
      nextCommandSequence = start.firstCommandSequence();
      nextEventSequence = start.firstEventSequence();
      processRevision = start.initialProcessRevision();
      roomRevision = start.initialRoomRevision();
      return;
    }
    roomPhase = carry.roomPhase();
    activeParty = carry.activeParty();
    nextCommandSequence = carry.nextCommandSequence();
    nextEventSequence = carry.nextEventSequence();
    processedCommandCount = carry.processedCommandCount();
    processedEventCount = carry.processedEventCount();
    initiatorComplete = carry.initiatorComplete();
    respondentUnlocked = carry.respondentUnlocked();
    respondentComplete = carry.respondentComplete();
    readinessParty = carry.readinessParty();
    lastEventId = carry.lastEventId();
    lastEventRef = carry.lastEventRef();
    lastEventHash = carry.lastEventHash();
    lastAgentRunRef = carry.lastAgentRunRef();
    lastGraphExecutionRef = carry.lastGraphExecutionRef();
    terminalReason = carry.terminalReason();
    processRevision = carry.processRevision();
    roomRevision = carry.roomRevision();
    protocolErrorCode = carry.protocolErrorCode();
    runGeneration = carry.runGeneration();
    lastDecision = carry.lastDecision();
    carry.observedCommands()
        .forEach(
            observed ->
                commandObservations.put(
                    observed.command().commandId(),
                    new CommandObservation(observed.command(), observed.decision())));
    carry.observedEvents()
        .forEach(
            observed ->
                eventObservations.put(
                    observed.event().eventId(), new EventObservation(observed.event(), observed.applied())));
    carry.threadInitializations()
        .forEach(initialization -> threadInitializations.put(initialization.party(), initialization));
  }

  private boolean shouldContinueAsNew() {
    return rolloverEnabled
        && ((runMaxAgeTimer != null && runMaxAgeTimer.isCompleted())
            || Workflow.getInfo().isContinueAsNewSuggested()
            || Workflow.getInfo().getHistoryLength() >= HISTORY_EVENT_LIMIT);
  }

  private boolean canContinueAsNew() {
    return roomPhase != IntakeRoomPhase.COMPLETED
        && inbox.isEmpty()
        && pendingCommand == null
        && activityExecution == null
        && Workflow.isEveryHandlerFinished();
  }

  private void continueAsNew() {
    Workflow.await(Workflow::isEveryHandlerFinished);
    IntakeRoomCarryState carry =
        new IntakeRoomCarryState(
            "intake-room-carry-state.v1",
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
            lastEventId,
            lastEventRef,
            lastEventHash,
            lastAgentRunRef,
            lastGraphExecutionRef,
            terminalReason,
            processRevision,
            roomRevision,
            protocolErrorCode,
            runGeneration + 1,
            lastDecision,
            commandObservations.values().stream()
                .map(
                    observed ->
                        new IntakeRoomCarryState.ObservedCommand(
                            "intake-observed-command.v1", observed.command(), observed.decision()))
                .toList(),
            eventObservations.values().stream()
                .map(
                    observed ->
                        new IntakeRoomCarryState.ObservedEvent(
                            "intake-observed-event.v1", observed.event(), observed.applied()))
                .toList(),
            new ArrayList<>(threadInitializations.values()));
    ContinueAsNewOptions options =
        ContinueAsNewOptions.newBuilder().setMemo(Map.of(CARRY_STATE_MEMO_KEY, carry)).build();
    Workflow.continueAsNew(options, start.withCarryState(carry));
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

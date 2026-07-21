package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
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
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

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
  private int sharedActivityRetriesRemaining;
  private boolean activityExecutionAuthorized;
  private int runGeneration;
  private io.temporal.workflow.Promise<Void> runMaxAgeTimer;
  private boolean rolloverEnabled;
  private boolean continueAsNewRequested;
  private Promise<Void> activeOrchestration;
  private CancellationScope activeCancellationScope;
  private String activeOrchestrationCommandId;
  private boolean activeCancellationRequested;
  private IntakeWorkflowCommand deferredCancellation;

  @Override
  public IntakeRoomSnapshot run(IntakeRoomStart start) {
    this.start = Objects.requireNonNull(start, "start must not be null");
    restoreCarryState();
    rolloverEnabled =
        Workflow.getVersion(ROLLOVER_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
    if (rolloverEnabled) {
      runMaxAgeTimer = Workflow.newTimer(RUN_MAX_AGE);
    }

    while (true) {
      if (activeOrchestration != null && activeOrchestration.isCompleted()) {
        settleActiveOrchestration();
        continue;
      }
      if (activeOrchestration != null && activeCancellationRequested) {
        Workflow.await(activeOrchestration::isCompleted);
        settleActiveOrchestration();
        continue;
      }
      if (shouldContinueAsNew() && canContinueAsNew()) {
        continueAsNew();
        return null;
      }
      if (activeOrchestration != null) {
        InboxItem interrupting = pollInterruptingInput();
        if (interrupting != null) {
          processNextInput(interrupting);
          continue;
        }
        Workflow.await(
            () -> activeOrchestration.isCompleted() || hasInterruptingInput());
        continue;
      }
      if (!inbox.isEmpty()) {
        processNextInput(inbox.removeFirst());
        continue;
      }
      if (roomPhase == IntakeRoomPhase.COMPLETED) {
        if (activeOrchestration != null) {
          Workflow.await(activeOrchestration::isCompleted);
          continue;
        }
        Workflow.await(Workflow::isEveryHandlerFinished);
        if (inbox.isEmpty()) {
          return state();
        }
        continue;
      }
      Workflow.await(
          () ->
              !inbox.isEmpty()
                  || (activeOrchestration != null && activeOrchestration.isCompleted())
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
  public void requestContinueAsNew() {
    continueAsNewRequested = true;
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

  private InboxItem pollInterruptingInput() {
    Iterator<InboxItem> iterator = inbox.iterator();
    while (iterator.hasNext()) {
      InboxItem item = iterator.next();
      if (isInterruptingInput(item)) {
        iterator.remove();
        return item;
      }
    }
    return null;
  }

  private boolean hasInterruptingInput() {
    return inbox.stream().anyMatch(this::isInterruptingInput);
  }

  private boolean isInterruptingInput(InboxItem input) {
    if (input instanceof EventInput) {
      return true;
    }
    return input instanceof CommandInput commandInput
        && preemptsActivityCommand(commandInput.command());
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
        IntakeActivityTerminalFailure terminalFailure =
            terminalFailureFor(command.commandId(), null, null);
        if (terminalFailure != null) {
          protocolErrorCode = terminalFailure.failureType();
          return;
        }
        if (pendingCommand != null
            && pendingCommand.commandId().equals(command.commandId())
            && command.executionContext() != null
            && activeOrchestration == null) {
          startOrchestration(command);
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
    if (preemptsActivityCommand(command) && deferCancellation(command)) {
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
    if (command.executionContext() != null) {
      int attempts = command.executionContext().retryBudget().activityAttemptsRemaining();
      activityExecutionAuthorized = attempts > 0;
      sharedActivityRetriesRemaining = Math.max(0, attempts - 1);
    } else {
      activityExecutionAuthorized = false;
      sharedActivityRetriesRemaining = 0;
    }
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
      startOrchestration(command);
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
    boolean rootOperation =
        pendingCommand.executionContext() == null
            && pendingCommand.operationKey().equals(event.operationKey());
    boolean stageOperation =
        activityExecution != null
            && activityExecution.commandId().equals(pendingCommand.commandId())
            && activityExecution.stageOperationKey().equals(event.operationKey())
            && (expectedActivityOperationKey == null
                || expectedActivityOperationKey.equals(activityExecution.stageOperationKey()));
    if (!rootOperation && !stageOperation) {
      protocolErrorCode = "EVENT_OPERATION_KEY_MISMATCH";
      return;
    }
    if (stageOperation
        && !eventAllowedForActivityStage(activityExecution.stage(), event.eventType())) {
      protocolErrorCode = "EVENT_ACTIVITY_STAGE_MISMATCH";
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
    if (event.graphExecutionRef() != null
        && pendingCommand.executionContext() != null
        && !pendingCommand
            .executionContext()
            .threadId()
            .equals(event.graphExecutionRef().threadId())) {
      protocolErrorCode = "EVENT_GRAPH_THREAD_MISMATCH";
      return;
    }
    if (!applyEvent(event)) {
      return;
    }
    if (expectedActivityOperationKey == null
        && activeCancellationScope != null
        && pendingCommand.commandId().equals(activeOrchestrationCommandId)) {
      activeCancellationRequested = true;
      activeCancellationScope.cancel();
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
    sharedActivityRetriesRemaining = 0;
    activityExecutionAuthorized = false;
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
      if (command.commandType() == IntakeCommandType.INTAKE_MESSAGE) {
        ensureSnapshot(command, context);
        GraphExecutionReceipt graph = completedGraphFor(command, context);
        if (graph == null) {
          graph = executeGraph(command, context);
        }
        TurnFinalizationReceipt finalization =
            finalizeTurn(command, context, graph);
        processEvent(finalization.committedEvent(), finalization.operation().operationKey());
        return;
      }
      BranchCommitReceipt branch = executeBranch(command, context);
      processEvent(branch.committedEvent(), branch.operation().operationKey());
    } catch (IntakeActivityDeadlineExceeded failure) {
      protocolErrorCode = "INTAKE_ACTIVITY_DEADLINE_EXPIRED";
    } catch (IntakeTerminalStageFailure failure) {
      protocolErrorCode = failure.failureType();
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (ActivityFailure failure) {
      if (failure.getCause() instanceof CanceledFailure) {
        throw failure;
      }
      protocolErrorCode = activityFailureCode(failure);
    } catch (IntakeActivityReconciliationMiss failure) {
      protocolErrorCode = IntakeActivityFailureTypes.RETRY_BUDGET_EXHAUSTED;
    } catch (RuntimeException failure) {
      // Receipt validation failures are fail-closed and leave the command pending for recovery.
      protocolErrorCode = "INTAKE_ACTIVITY_RECEIPT_INVALID";
    }
  }

  private void startOrchestration(IntakeWorkflowCommand command) {
    if (activeOrchestration != null) {
      throw new IllegalStateException("an Intake Activity orchestration is already running");
    }
    activeOrchestrationCommandId = command.commandId();
    activeCancellationRequested = false;
    activeCancellationScope = Workflow.newCancellationScope(() -> orchestrate(command));
    activeOrchestration = Async.procedure(activeCancellationScope::run);
  }

  private void settleActiveOrchestration() {
    Promise<Void> completed = activeOrchestration;
    String completedCommandId = activeOrchestrationCommandId;
    boolean cancellationRequested = activeCancellationRequested;
    IntakeWorkflowCommand cancellation = deferredCancellation;
    try {
      completed.get();
    } catch (RuntimeException failure) {
      if (!isCancellation(failure)) {
        protocolErrorCode = "INTAKE_ACTIVITY_ORCHESTRATION_FAILED";
      }
    }
    activeOrchestration = null;
    activeCancellationScope = null;
    activeOrchestrationCommandId = null;
    activeCancellationRequested = false;
    deferredCancellation = null;
    consumeQueuedCommittedEvent(completedCommandId);
    if (cancellationRequested
        && pendingCommand != null
        && pendingCommand.commandId().equals(completedCommandId)) {
      pendingCommand = null;
      activityExecution = null;
      sharedActivityRetriesRemaining = 0;
      activityExecutionAuthorized = false;
      protocolErrorCode = null;
    }
    if (cancellation != null) {
      processCommand(cancellation);
    }
  }

  private boolean preemptsActivityCommand(IntakeWorkflowCommand command) {
    return command.commandType() == IntakeCommandType.INTAKE_CANCEL
        && command.party() == IntakeParty.INITIATOR
        && pendingCommand != null
        && pendingCommand.executionContext() != null;
  }

  private boolean deferCancellation(IntakeWorkflowCommand command) {
    if (activeOrchestration == null) {
      consumeQueuedCommittedEvent(
          pendingCommand == null ? null : pendingCommand.commandId());
      if (pendingCommand != null && pendingCommand.executionContext() != null) {
        pendingCommand = null;
        activityExecution = null;
        sharedActivityRetriesRemaining = 0;
        activityExecutionAuthorized = false;
        protocolErrorCode = null;
      }
      return false;
    }
    if (deferredCancellation != null) {
      if (!deferredCancellation.equals(command)) {
        rejectCommand(command, "CANCELLATION_ALREADY_PENDING", true);
      }
      return true;
    }
    deferredCancellation = command;
    activeCancellationRequested = true;
    activeCancellationScope.cancel();
    return true;
  }

  private boolean consumeQueuedCommittedEvent(String commandId) {
    if (commandId == null
        || pendingCommand == null
        || !pendingCommand.commandId().equals(commandId)
        || activityExecution == null
        || !activityExecution.commandId().equals(commandId)) {
      return false;
    }
    String expectedOperationKey = activityExecution.stageOperationKey();
    Iterator<InboxItem> iterator = inbox.iterator();
    while (iterator.hasNext()) {
      InboxItem item = iterator.next();
      if (!(item instanceof EventInput eventInput)) {
        continue;
      }
      IntakeDomainEventRef event = eventInput.event();
      if (!event.commandId().equals(commandId)
          || !event.operationKey().equals(expectedOperationKey)) {
        continue;
      }
      iterator.remove();
      processEvent(event, expectedOperationKey);
      EventObservation observation = eventObservations.get(event.eventId());
      if (observation != null && observation.applied()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCancellation(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof CanceledFailure) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private void ensureSnapshot(
      IntakeWorkflowCommand command, IntakeCommandExecutionContext context) {
    IntakeThreadInitialization existing = threadInitializations.get(command.party());
    if (existing != null) {
      requireThreadBinding(existing, command, context);
      return;
    }
    long domainRevision = processRevision;
    String operationKey =
        IntakeOperationKeys.snapshotPublish(
            command.caseId(), command.roomEpoch(), command.actorScopeHash(), domainRevision);
    SnapshotPublicationReceipt receipt =
        invokeStage(
            command,
            context,
            IntakeActivityStage.SNAPSHOT_PUBLICATION,
            operationKey,
            envelope ->
                activities(context)
                    .publishSnapshot(
                        new SnapshotPublicationRequest(
                            "intake-snapshot-publication-request.v1",
                            envelope,
                            context.threadId(),
                            context.agentSessionId(),
                            domainRevision,
                            operationKey,
                            command.requestHash())));
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
      IntakeWorkflowCommand command, IntakeCommandExecutionContext context) {
    String operationKey =
        IntakeOperationKeys.graphExecute(
            command.caseId(), command.roomEpoch(), context.threadId(), command.commandId());
    GraphExecutionReceipt receipt =
        invokeStage(
            command,
            context,
            IntakeActivityStage.GRAPH_EXECUTION,
            operationKey,
            envelope ->
                activities(context)
                    .executeGraph(
                        new GraphExecutionRequest(
                            "intake-graph-execution-request.v1",
                            envelope,
                            context.threadId(),
                            context.agentSessionId(),
                            operationKey,
                            command.requestHash())));
    requireOperation(receipt.operation(), operationKey, command.requestHash());
    activityExecution = activityExecution.withGraphExecution(receipt);
    return receipt;
  }

  private TurnFinalizationReceipt finalizeTurn(
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
    TurnFinalizationReceipt receipt =
        invokeStage(
            command,
            context,
            IntakeActivityStage.TURN_FINALIZATION,
            operationKey,
            envelope -> {
              TurnFinalizationRequest request =
                  new TurnFinalizationRequest(
                      "intake-turn-finalization-request.v1",
                      envelope,
                      context.threadId(),
                      context.agentSessionId(),
                      graph,
                      operationKey,
                      command.requestHash());
              TurnFinalizationReceipt finalization =
                  activities(context).finalizeTurn(request);
              finalization.requireMatches(request);
              return finalization;
            });
    requireOperation(receipt.operation(), operationKey, command.requestHash());
    if (!graph.operation().resultHash().equals(receipt.operation().resultHash())) {
      throw new IllegalArgumentException("finalization result does not match graph result");
    }
    return receipt;
  }

  private BranchCommitReceipt executeBranch(
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
    BranchCommitReceipt receipt =
        invokeStage(
            command,
            context,
            stage,
            operationKey,
            envelope -> {
              BranchCommitRequest request =
                  new BranchCommitRequest(
                      "intake-branch-commit-request.v1",
                      envelope,
                      operation,
                      operationKey,
                      command.requestHash());
              BranchCommitReceipt branchReceipt =
                  switch (operation) {
                    case INITIATOR_ACCEPT -> activities(context).acceptInitiator(request);
                    case INITIATOR_REJECT -> activities(context).rejectInitiator(request);
                    case CANCEL -> activities(context).cancelIntake(request);
                    case RESPONDENT_CONFIRM -> activities(context).confirmRespondent(request);
                  };
              branchReceipt.requireMatches(request);
              return branchReceipt;
            });
    requireOperation(receipt.operation(), operationKey, command.requestHash());
    if (receipt.branchOperation() != operation) {
      throw new IllegalArgumentException("branch receipt does not match its exact request");
    }
    return receipt;
  }

  private ActivityEnvelope activityEnvelope(
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context,
      ActivityInvocation invocation) {
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
    RetryBudget invocationBudget =
        new RetryBudget(
            "intake-retry-budget.v1",
            context.retryBudget().providerAttemptsRemaining(),
            invocation.mode() == ActivityInvocationMode.RECONCILE_ONLY ? 0 : 1,
            context.retryBudget().repairsRemaining());
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
        invocationBudget,
        versions,
        invocation);
  }

  private IntakeRoomActivities activities(IntakeCommandExecutionContext context) {
    long remainingMillis = context.deadlineEpochMillis() - Workflow.currentTimeMillis();
    if (remainingMillis <= 0) {
      throw new IntakeActivityDeadlineExceeded();
    }
    return Workflow.newActivityStub(
        IntakeRoomActivities.class,
        IntakeActivityTemporalPolicy.options(
            context.retryBudget(), Duration.ofMillis(remainingMillis)));
  }

  private void setActivityStage(
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context,
      IntakeActivityStage stage,
      String operationKey,
      ActivityInvocation invocation) {
    GraphExecutionReceipt graphReceipt =
        activityExecution != null
                && activityExecution.commandId().equals(command.commandId())
                && activityExecution.completedGraphExecution() != null
            ? activityExecution.completedGraphExecution()
            : null;
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
            new RetryBudget(
                "intake-retry-budget.v1",
                context.retryBudget().providerAttemptsRemaining(),
                invocation.mode() == ActivityInvocationMode.RECONCILE_ONLY ? 0 : 1,
                context.retryBudget().repairsRemaining()),
            invocation,
            graphReceipt,
            null);
  }

  private GraphExecutionReceipt completedGraphFor(
      IntakeWorkflowCommand command, IntakeCommandExecutionContext context) {
    if (activityExecution == null
        || !activityExecution.commandId().equals(command.commandId())
        || activityExecution.completedGraphExecution() == null) {
      return null;
    }
    GraphExecutionReceipt graph = activityExecution.completedGraphExecution();
    String expectedKey =
        IntakeOperationKeys.graphExecute(
            command.caseId(), command.roomEpoch(), context.threadId(), command.commandId());
    requireOperation(graph.operation(), expectedKey, command.requestHash());
    return graph;
  }

  /** Executes one stable operation at a time while consuming the command-wide retry pool. */
  private <T> T invokeStage(
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context,
      IntakeActivityStage stage,
      String operationKey,
      Function<ActivityEnvelope, T> invocation) {
    IntakeActivityTerminalFailure terminalFailure =
        terminalFailureFor(command.commandId(), stage, operationKey);
    if (terminalFailure != null) {
      throw new IntakeTerminalStageFailure(terminalFailure.failureType());
    }
    while (true) {
      ActivityInvocation next = nextInvocation(command, context, stage, operationKey);
      setActivityStage(command, context, stage, operationKey, next);
      try {
        T result = invocation.apply(activityEnvelope(command, context, next));
        if (next.mode() == ActivityInvocationMode.RECONCILE_ONLY
            && result == null) {
          throw new IntakeActivityReconciliationMiss();
        }
        return result;
      } catch (CanceledFailure failure) {
        throw failure;
      } catch (ActivityFailure failure) {
        if (failure.getCause() instanceof CanceledFailure || isCancellation(failure)) {
          throw failure;
        }
        String failureCode = activityFailureCode(failure);
        if (!IntakeActivityFailureTypes.isRetryable(failureCode)) {
          activityExecution = activityExecution.withTerminalFailure(failureCode);
          throw failure;
        }
        if (next.mode() == ActivityInvocationMode.RECONCILE_ONLY) {
          throw new IntakeActivityReconciliationMiss();
        }
        if (sharedActivityRetriesRemaining > 0) {
          sharedActivityRetriesRemaining--;
          activityExecution =
              activityExecution.withInvocation(
                  new ActivityInvocation(
                      "intake-activity-invocation.v1",
                      ActivityInvocationMode.INFRASTRUCTURE_RETRY,
                      sharedActivityRetriesRemaining));
          continue;
        }
        // The next invocation may only read the operation ledger/receipt.
        activityExecution =
            activityExecution.withInvocation(
                new ActivityInvocation(
                    "intake-activity-invocation.v1",
                    ActivityInvocationMode.RECONCILE_ONLY,
                    0));
        continue;
      } catch (IntakeActivityReconciliationMiss failure) {
        protocolErrorCode = IntakeActivityFailureTypes.RETRY_BUDGET_EXHAUSTED;
        throw failure;
      }
    }
  }

  private ActivityInvocation nextInvocation(
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context,
      IntakeActivityStage stage,
      String operationKey) {
    if (activityExecution != null
        && activityExecution.commandId().equals(command.commandId())
        && activityExecution.stage() == stage
        && activityExecution.stageOperationKey().equals(operationKey)) {
      ActivityInvocation current = activityExecution.invocation();
      if (current.mode() == ActivityInvocationMode.RECONCILE_ONLY) {
        return current;
      }
      if (current.mode() == ActivityInvocationMode.INFRASTRUCTURE_RETRY) {
        return current;
      }
    }
    return new ActivityInvocation(
        "intake-activity-invocation.v1",
        activityExecutionAuthorized
            ? ActivityInvocationMode.FIRST_EXECUTION
            : ActivityInvocationMode.RECONCILE_ONLY,
        sharedActivityRetriesRemaining);
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
    return IntakeActivityFailureTypes.classify(failure);
  }

  private IntakeActivityTerminalFailure terminalFailureFor(
      String commandId, IntakeActivityStage stage, String operationKey) {
    if (activityExecution == null
        || !activityExecution.commandId().equals(commandId)
        || activityExecution.terminalFailure() == null
        || (stage != null && activityExecution.stage() != stage)
        || (operationKey != null
            && !activityExecution.stageOperationKey().equals(operationKey))) {
      return null;
    }
    return activityExecution.terminalFailure();
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

  private static boolean eventAllowedForActivityStage(
      IntakeActivityStage stage, IntakeDomainEventType eventType) {
    return switch (stage) {
      case SNAPSHOT_PUBLICATION, GRAPH_EXECUTION -> false;
      case TURN_FINALIZATION ->
          eventType == IntakeDomainEventType.TURN_NEEDS_INPUT
              || eventType == IntakeDomainEventType.TURN_READY_TO_CONFIRM;
      case INITIATOR_ACCEPTANCE -> eventType == IntakeDomainEventType.INITIATOR_ACCEPTED;
      case INITIATOR_REJECTION -> eventType == IntakeDomainEventType.NOT_ADMISSIBLE;
      case CANCELLATION -> eventType == IntakeDomainEventType.CANCELLED;
      case RESPONDENT_CONFIRMATION -> eventType == IntakeDomainEventType.RESPONDENT_CONFIRMED;
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

  private void restoreCarryState() {
    boolean continued = Workflow.getInfo().getContinuedExecutionRunId().isPresent();
    IntakeRoomCarryState supplied = start.carryState();
    if (!continued) {
      if (supplied != null) {
        throw new IllegalStateException("initial Intake workflow cannot provide carry state");
      }
      nextCommandSequence = start.firstCommandSequence();
      nextEventSequence = start.firstEventSequence();
      processRevision = start.initialProcessRevision();
      roomRevision = start.initialRoomRevision();
      return;
    }
    IntakeRoomCarryState memo =
        (IntakeRoomCarryState) Workflow.getMemo(CARRY_STATE_MEMO_KEY, IntakeRoomCarryState.class);
    if (memo == null && supplied == null) {
      throw new IllegalStateException("continued Intake workflow is missing carry state");
    }
    if (memo != null && supplied != null && !memo.equals(supplied)) {
      throw new IllegalStateException("continued Intake carry state conflicts with its memo");
    }
    IntakeRoomCarryState carry = supplied != null ? supplied : memo;
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
    return continueAsNewRequested
        || (rolloverEnabled
            && ((runMaxAgeTimer != null && runMaxAgeTimer.isCompleted())
            || Workflow.getInfo().isContinueAsNewSuggested()
            || Workflow.getInfo().getHistoryLength() >= HISTORY_EVENT_LIMIT));
  }

  private boolean canContinueAsNew() {
    return roomPhase != IntakeRoomPhase.COMPLETED
        && inbox.isEmpty()
        && pendingCommand == null
        && activityExecution == null
        && activeOrchestration == null
        && deferredCancellation == null
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

  private static final class IntakeActivityDeadlineExceeded extends RuntimeException {}

  private static final class IntakeActivityReconciliationMiss extends RuntimeException {}

  private static final class IntakeTerminalStageFailure extends RuntimeException {
    private final String failureType;

    private IntakeTerminalStageFailure(String failureType) {
      this.failureType = failureType;
    }

    private String failureType() {
      return failureType;
    }
  }
}

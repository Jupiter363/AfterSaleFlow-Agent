package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

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
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class IntakeRoomWorkflowImpl implements IntakeRoomWorkflow {

  private static final int RECENT_CAPACITY = 256;
  private static final String CARRY_STATE_MEMO_KEY = "intake_room_carry_state_v1";
  private static final String ROLLOVER_CHANGE_ID = "intake-room-rollover-v1";
  private static final String CANCELLATION_RECONCILIATION_CHANGE_ID =
      "intake-room-cancellation-reconciliation-v1";
  private static final String AGENT_RUN_CHILD_CHANGE_ID = "intake-room-agent-run-v2-child-v1";
  private static final String AGENT_RUN_WINNING_ATTEMPT_CHANGE_ID =
      "intake-room-agent-run-winning-attempt-v1";
  private static final String AGENT_RUN_POST_COMMIT_RECONCILIATION_CHANGE_ID =
      "intake-room-agent-run-post-commit-reconciliation-v1";
  private static final String AGENT_RUN_LATE_COMMIT_RECONCILIATION_CHANGE_ID =
      "intake-room-agent-run-late-commit-reconciliation-v1";
  private static final String AGENT_RUN_TERMINAL_NO_COMMIT_RELEASE_CHANGE_ID =
      "intake-room-agent-run-terminal-no-commit-release-v1";
  private static final String AGENT_RUN_FINALIZATION_REJECTED_RELEASE_CHANGE_ID =
      "intake-room-agent-run-finalization-rejected-terminal-no-commit-v1";
  private static final String AGENT_RUN_PENDING_FINALIZATION_RECOVERY_CHANGE_ID =
      "intake-room-agent-run-pending-finalization-recovery-v1";
  private static final String AGENT_RUN_TERMINAL_NO_COMMIT_RECOVERY_CHANGE_ID =
      "intake-room-agent-run-terminal-no-commit-recovery-v1";
  private static final String AGENT_RUN_TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_CHANGE_ID =
      "intake-room-agent-run-terminal-no-commit-parent-convergence-v1";
  private static final String AGENT_RUN_TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_V2_CHANGE_ID =
      "intake-room-agent-run-terminal-no-commit-parent-convergence-v2";
  private static final String AGENT_RUN_TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_V3_CHANGE_ID =
      "intake-room-agent-run-terminal-no-commit-parent-convergence-v3";
  private static final String AGENT_RUN_TERMINAL_NO_COMMIT_ACKNOWLEDGED_RECOVERY_CHANGE_ID =
      "intake-room-agent-run-terminal-no-commit-acknowledged-recovery-v1";
  private static final String AGENT_RUN_CANCELLATION_CLEAR_GUARD_CHANGE_ID =
      "intake-room-agent-run-cancellation-clear-guard-v1";
  private static final String AGENT_RUN_RESOLVED_CHILD_CARRY_GUARD_CHANGE_ID =
      "intake-room-agent-run-resolved-child-carry-guard-v1";
  private static final String AGENT_RUN_COMMITTED_EVENT_GAP_RECOVERY_CHANGE_ID =
      "intake-room-agent-run-committed-event-gap-recovery-v1";
  private static final String TARGET_BRANCH_CONFIRMATION_EVENT_GAP_RECOVERY_CHANGE_ID =
      "intake-room-target-branch-confirmation-event-gap-recovery-v1";
  private static final String TARGET_SOURCE_CURSOR_ONLY_EVENT_CHANGE_ID =
      "intake-room-target-source-cursor-only-event-v1";
  private static final String TARGET_SOURCE_FUTURE_CURSOR_BUFFER_CHANGE_ID =
      "intake-room-target-source-future-cursor-buffer-v1";
  private static final String FORMAL_EVENT_REJECTION_RECOVERY_CHANGE_ID =
      "intake-room-formal-event-rejection-recovery-v1";
  private static final String TARGET_BRANCH_OUTPUT_SCHEMA_VERSION =
      "target-e2e-room-proposal-source.v2";
  private static final long HISTORY_EVENT_LIMIT = 2_000;
  private static final Duration RUN_MAX_AGE = Duration.ofHours(24);
  private static final int POST_COMMIT_RECONCILIATION_ATTEMPTS = 5;
  private static final Duration POST_COMMIT_RECONCILIATION_INITIAL_DELAY =
      Duration.ofMillis(200);
  private static final Duration FINALIZATION_RECONCILIATION_READ_TIMEOUT =
      Duration.ofSeconds(5);
  private static final String TARGET_FINALIZATION_RECOVERY_FAILURE =
      "INTAKE_TARGET_FINALIZATION_RECOVERY_FAILED";

  private final ArrayDeque<InboxItem> inbox = new ArrayDeque<>();
  private final CaseCommandLifecycleActivities caseCommandLifecycleActivities =
      Workflow.newActivityStub(
          CaseCommandLifecycleActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumInterval(Duration.ofSeconds(5))
                      .setMaximumAttempts(3)
                      .build())
              .build());
  private final Map<String, CommandObservation> commandObservations = new LinkedHashMap<>();
  private final Map<String, EventObservation> eventObservations = new LinkedHashMap<>();
  private final Map<String, TargetIntakeSourceEventRef> targetSourceEventObservations =
      new LinkedHashMap<>();
  private final Map<IntakeParty, IntakeThreadInitialization> threadInitializations =
      new EnumMap<>(IntakeParty.class);
  private final ArrayDeque<IntakeWorkflowCommand> deferredTargetCommands = new ArrayDeque<>();

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
  private IntakeAgentRunChildState targetAgentRunChild;
  private IntakeAgentRunFinalizationReadResult authoritativeCommittedTargetFinalization;
  private IntakeAgentRunFinalizationRecoveryRequest activeTargetFinalizationRecovery;
  private IntakeAgentRunFinalizationRecoveryRequest completedTargetFinalizationRecoveryRequest;
  private IntakeAgentRunFinalizationRecoveryResult completedTargetFinalizationRecoveryResult;
  private IntakeTerminalNoCommitRecoveryRequest completedTerminalNoCommitRecoveryRequest;
  private IntakeTerminalNoCommitRecoveryResult completedTerminalNoCommitRecoveryResult;
  private TargetAgentRunPreCommandState targetAgentRunPreCommandState;
  private int sharedActivityRetriesRemaining;
  private boolean activityExecutionAuthorized;
  private int runGeneration;
  private io.temporal.workflow.Promise<Void> runMaxAgeTimer;
  private boolean rolloverEnabled;
  private boolean winningAttemptEnabled;
  private boolean formalEventRejectionRecoveryEnabled;
  private boolean futureTargetSourceCursorBufferingEnabled;
  private boolean continueAsNewRequested;
  private boolean continueAsNewBlockedByTargetState;
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
    winningAttemptEnabled =
        Workflow.getVersion(
                AGENT_RUN_WINNING_ATTEMPT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
            == 1;
    formalEventRejectionRecoveryEnabled =
        Workflow.getVersion(
                FORMAL_EVENT_REJECTION_RECOVERY_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
            == 1;
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
      if (activeOrchestration == null
          && deferredCancellation != null
          && pendingCommand == null) {
        processDeferredCancellation();
        continue;
      }
      if (activeOrchestration == null
          && pendingCommand == null
          && !deferredTargetCommands.isEmpty()) {
        inbox.addFirst(new CommandInput(deferredTargetCommands.removeFirst()));
        continue;
      }
      if (shouldContinueAsNew() && canContinueAsNew()) {
        if (continueAsNew()) {
          return null;
        }
        continue;
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
  public void targetSourceEventObserved(TargetIntakeSourceEventRef event) {
    inbox.addLast(
        new TargetSourceEventInput(
            Objects.requireNonNull(event, "target source event must not be null")));
  }

  @Override
  public void requestContinueAsNew() {
    continueAsNewRequested = true;
  }

  @Override
  public IntakeAgentRunFinalizationRecoveryResult recoverTargetFinalization(
      IntakeAgentRunFinalizationRecoveryRequest request) {
    requireTargetFinalizationRecovery(request, false);
    if (completedTargetFinalizationRecoveryResult != null) {
      return completedTargetFinalizationRecoveryResult;
    }
    activeTargetFinalizationRecovery = request;
    try {
      IntakeWorkflowCommand command = pendingWorkflowCommand();
      if (request.isTerminalNoCommitRecovery()) {
        return recoverPendingFinalizationRejection(request, command);
      }
      IntakeAgentRunFinalizationReadResult finalization;
      if (request.matchesAlreadyAdopted(
          targetAgentRunChild, authoritativeCommittedTargetFinalization)) {
        finalization = authoritativeCommittedTargetFinalization;
      } else {
        finalization =
            readTargetFinalization(
                command,
                IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION,
                FINALIZATION_RECONCILIATION_READ_TIMEOUT);
        requireExpectedRecoveryFinalization(request, finalization);
        requireTargetFinalizationRecovery(request, true);
        commitTargetFinalization(finalization);
      }
      requireExpectedRecoveryFinalization(request, finalization);
      IntakeAgentRunChildState adoptedChildState = request.committedChildState();
      if (!adoptedChildState.equals(targetAgentRunChild)
          || !finalization.equals(authoritativeCommittedTargetFinalization)) {
        throw new IllegalStateException("target finalization recovery adoption changed authority");
      }
      IntakeDomainEventRef committedEvent = finalization.receipt().committedEvent();
      processEvent(committedEvent, finalization.receipt().operation().operationKey());
      EventObservation observation = eventObservations.get(committedEvent.eventId());
      if (observation == null || !observation.applied()) {
        throw new IllegalStateException("target finalization recovery event was not applied");
      }
      IntakeAgentRunFinalizationRecoveryResult recovered =
          new IntakeAgentRunFinalizationRecoveryResult(
              IntakeAgentRunFinalizationRecoveryResult.SCHEMA_VERSION,
              request,
              adoptedChildState,
              finalization);
      completedTargetFinalizationRecoveryRequest = request;
      completedTargetFinalizationRecoveryResult = recovered;
      return recovered;
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (ActivityFailure failure) {
      rethrowTargetFinalizationRecoveryCancellation(failure);
      throw targetFinalizationRecoveryFailure(failure);
    } catch (RuntimeException failure) {
      throw targetFinalizationRecoveryFailure(failure);
    } finally {
      activeTargetFinalizationRecovery = null;
    }
  }

  private IntakeAgentRunFinalizationRecoveryResult recoverPendingFinalizationRejection(
      IntakeAgentRunFinalizationRecoveryRequest request, IntakeWorkflowCommand command) {
    IntakeAgentRunFinalizationReadResult finalization =
        readTargetFinalization(
            command,
            IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION,
            FINALIZATION_RECONCILIATION_READ_TIMEOUT);
    requireExpectedRecoveryFinalization(request, finalization);
    requireTargetFinalizationRecovery(request, true);
    if (Workflow.getVersion(
            AGENT_RUN_PENDING_FINALIZATION_RECOVERY_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        != 1) {
      throw new IllegalStateException("pending terminal finalization recovery is not enabled");
    }
    IntakeAgentRunChildState terminalChild = request.terminalNoCommitChildState();
    if (!settleFinalizationRejectedTargetAgentRun(command, finalization)) {
      throw new IllegalStateException("pending terminal finalization recovery did not converge");
    }
    TargetIntakeCommandTerminalNoCommit acknowledgedAuthority =
        completedTerminalNoCommitRecoveryResult == null
            ? null
            : completedTerminalNoCommitRecoveryResult.resolvedAuthority().authority();
    if (acknowledgedAuthority == null
        || !command.commandId().equals(acknowledgedAuthority.commandId())
        || pendingCommand != null
        || targetAgentRunChild != null
        || processRevision != acknowledgedAuthority.newProcessRevision()
        || roomRevision != acknowledgedAuthority.newRoomRevision()
        || nextEventSequence - 1 != acknowledgedAuthority.lastCaseEventSequence()) {
      throw new IllegalStateException("pending terminal finalization recovery changed authority");
    }
    IntakeAgentRunFinalizationRecoveryResult recovered =
        new IntakeAgentRunFinalizationRecoveryResult(
            IntakeAgentRunFinalizationRecoveryResult.V2_SCHEMA_VERSION,
            request,
            terminalChild,
            finalization,
            IntakeAgentRunFinalizationRecoveryResult.Disposition.TERMINAL_NO_COMMIT_CONVERGED,
            acknowledgedAuthority.asObservedV2Authority());
    completedTargetFinalizationRecoveryRequest = request;
    completedTargetFinalizationRecoveryResult = recovered;
    return recovered;
  }

  @Override
  public void validateRecoverTargetFinalization(
      IntakeAgentRunFinalizationRecoveryRequest request) {
    requireTargetFinalizationRecovery(request, false);
  }

  @Override
  public IntakeTerminalNoCommitRecoveryResult recoverTerminalNoCommit(
      IntakeTerminalNoCommitRecoveryRequest request) {
    requireTerminalNoCommitRecovery(request);
    if (completedTerminalNoCommitRecoveryResult != null) {
      if (request.equals(completedTerminalNoCommitRecoveryRequest)) {
        return completedTerminalNoCommitRecoveryResult;
      }
      if (!IntakeTerminalNoCommitRecoveryRequest.V3_SCHEMA_VERSION.equals(
          request.schemaVersion())) {
        throw new IllegalArgumentException("terminal-no-commit recovery replay conflicts");
      }
    }
    if (IntakeTerminalNoCommitRecoveryRequest.V3_SCHEMA_VERSION.equals(
        request.schemaVersion())) {
      return recoverAcknowledgedTerminalNoCommit(request);
    }
    ResolveTargetIntakeTerminalNoCommitResult resolved =
        caseCommandLifecycleActivities.resolveTargetIntakeTerminalNoCommit(
            new ResolveTargetIntakeTerminalNoCommit(
                "resolve-target-intake-terminal-no-commit.v1", request.authority()));
    requireTerminalNoCommitRecovery(request);
    if (resolved == null || !request.authority().equals(resolved.authority())) {
      throw new IllegalArgumentException(
          "terminal-no-commit authority read returned conflicting evidence");
    }
    TargetIntakeCommandTerminalNoCommit authority = request.authority();
    if (processRevision == authority.expectedProcessRevision()
        && roomRevision == authority.expectedRoomRevision()) {
      processRevision = authority.newProcessRevision();
      roomRevision = authority.newRoomRevision();
    } else if (processRevision != authority.newProcessRevision()
        || roomRevision != authority.newRoomRevision()) {
      throw new IllegalStateException("terminal-no-commit Room coordinates changed");
    }
    signalTerminalNoCommit(authority);
    protocolErrorCode = null;
    IntakeTerminalNoCommitRecoveryResult recovered =
        new IntakeTerminalNoCommitRecoveryResult(
            IntakeTerminalNoCommitRecoveryResult.SCHEMA_VERSION,
            IntakeTerminalNoCommitRecoveryResult.Disposition.EMITTED,
            request,
            resolved);
    completedTerminalNoCommitRecoveryRequest = request;
    completedTerminalNoCommitRecoveryResult = recovered;
    return recovered;
  }

  private IntakeTerminalNoCommitRecoveryResult recoverAcknowledgedTerminalNoCommit(
      IntakeTerminalNoCommitRecoveryRequest request) {
    if (Workflow.getVersion(
            AGENT_RUN_TERMINAL_NO_COMMIT_ACKNOWLEDGED_RECOVERY_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            1)
        != 1) {
      throw new IllegalStateException("acknowledged terminal-no-commit recovery is not enabled");
    }
    ResolveTargetIntakeTerminalNoCommitResult resolved =
        resolveStrictV3TerminalNoCommit(request.authority());
    requireTerminalNoCommitRecovery(request);
    TargetIntakeCommandTerminalNoCommit authority = resolved.authority();
    if (!TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
            authority.schemaVersion())
        || !request.authority().equals(authority.asObservedV2Authority())) {
      throw new IllegalArgumentException(
          "terminal-no-commit authority read returned conflicting v3 evidence");
    }
    ConvergeTargetIntakeTerminalNoCommitResult convergence =
        convergeStrictV3TerminalNoCommit(resolved);
    requireTerminalNoCommitRecovery(request);
    requireAcknowledgedTerminalNoCommit(authority, resolved, convergence);
    TargetIntakeCommandTerminalNoCommit observedAuthority = request.authority();
    if (processRevision == observedAuthority.expectedProcessRevision()
        && roomRevision == observedAuthority.expectedRoomRevision()) {
      processRevision = observedAuthority.newProcessRevision();
      roomRevision = observedAuthority.newRoomRevision();
    } else if (processRevision != observedAuthority.newProcessRevision()
        || roomRevision != observedAuthority.newRoomRevision()) {
      throw new IllegalStateException("terminal-no-commit Room coordinates changed");
    }
    signalTerminalNoCommit(authority);
    protocolErrorCode = null;
    IntakeTerminalNoCommitRecoveryResult recovered =
        new IntakeTerminalNoCommitRecoveryResult(
            IntakeTerminalNoCommitRecoveryResult.V2_SCHEMA_VERSION,
            IntakeTerminalNoCommitRecoveryResult.Disposition.PARENT_CONVERGED,
            request,
            resolved,
            convergence);
    completedTerminalNoCommitRecoveryRequest = request;
    completedTerminalNoCommitRecoveryResult = recovered;
    return recovered;
  }

  @Override
  public void validateRecoverTerminalNoCommit(IntakeTerminalNoCommitRecoveryRequest request) {
    requireTerminalNoCommitRecovery(request);
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
    if (input instanceof EventInput eventInput) {
      processEvent(eventInput.event());
      return;
    }
    processTargetSourceEvent(((TargetSourceEventInput) input).event());
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
    if (input instanceof EventInput || input instanceof TargetSourceEventInput) {
      return true;
    }
    return input instanceof CommandInput commandInput
        && preemptsActivityCommand(commandInput.command());
  }

  private void processTargetSourceEvent(TargetIntakeSourceEventRef event) {
    if (!start.targetE2eCandidate()) {
      protocolErrorCode = "TARGET_SOURCE_EVENT_LANE_NOT_AUTHORIZED";
      return;
    }
    if (eventObservations.containsKey(event.eventId())) {
      protocolErrorCode = "EVENT_ID_REUSE_CONFLICT";
      return;
    }
    TargetIntakeSourceEventRef observed = targetSourceEventObservations.get(event.eventId());
    if (observed != null) {
      if (!observed.equals(event)) {
        protocolErrorCode = "TARGET_SOURCE_EVENT_ID_REUSE_CONFLICT";
        return;
      }
      if (futureTargetSourceCursorBufferingEnabled) {
        drainBufferedTargetEvents();
      } else {
        retryBufferedFormalEventAtCursor();
      }
      return;
    }
    if (!TargetIntakeSourceEventRef.isCursorOnlyEventType(event.eventType())) {
      protocolErrorCode = "TARGET_SOURCE_EVENT_TYPE_NOT_ALLOWED";
      return;
    }
    if (!TargetIntakeSourceEventRef.ROOM_MESSAGE_CREATED.equals(event.eventType())
        && Workflow.getVersion(
                TARGET_SOURCE_CURSOR_ONLY_EVENT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
            != 1) {
      protocolErrorCode = "TARGET_SOURCE_EVENT_TYPE_NOT_ALLOWED";
      return;
    }
    if (!matchesEnvelope(event)) {
      protocolErrorCode = "TARGET_SOURCE_EVENT_SCOPE_MISMATCH";
      return;
    }
    if (hasSourceEventSequenceConflict(event)
        || hasFormalEventSequenceConflict(event.eventSequence(), event.eventId())) {
      protocolErrorCode = "TARGET_SOURCE_EVENT_SEQUENCE_ID_CONFLICT";
      return;
    }
    if (event.eventSequence() < nextEventSequence) {
      protocolErrorCode = "TARGET_SOURCE_EVENT_SEQUENCE_REPLAY_UNKNOWN";
      return;
    }
    if (event.eventSequence() > nextEventSequence) {
      if (Workflow.getVersion(
              TARGET_SOURCE_FUTURE_CURSOR_BUFFER_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
          != 1) {
        protocolErrorCode = "TARGET_SOURCE_EVENT_SEQUENCE_GAP";
        return;
      }
      if (!canStoreTargetSourceObservation(true)) {
        protocolErrorCode = "TARGET_SOURCE_EVENT_BUFFER_CAPACITY_EXCEEDED";
        return;
      }
      futureTargetSourceCursorBufferingEnabled = true;
      storeTargetSourceObservation(event, true);
      protocolErrorCode = "TARGET_SOURCE_EVENT_SEQUENCE_GAP";
      return;
    }

    if (futureTargetSourceCursorBufferingEnabled) {
      if (!canStoreTargetSourceObservation(false)) {
        protocolErrorCode = "TARGET_SOURCE_EVENT_BUFFER_CAPACITY_EXCEEDED";
        return;
      }
      storeTargetSourceObservation(event, false);
      drainBufferedTargetEvents();
      return;
    }

    targetSourceEventObservations.put(event.eventId(), event);
    trim(targetSourceEventObservations);
    nextEventSequence++;
    protocolErrorCode = null;
    retryBufferedFormalEventAtCursor();
  }

  private void retryBufferedFormalEventAtCursor() {
    var candidates =
        eventObservations.values().stream()
            .filter(
                observation ->
                    !observation.applied()
                        && observation.event().eventSequence() == nextEventSequence)
            .map(EventObservation::event)
            .toList();
    if (candidates.size() > 1) {
      protocolErrorCode = "EVENT_SEQUENCE_ID_CONFLICT";
      return;
    }
    if (candidates.isEmpty()) {
      return;
    }
    long cursor = nextEventSequence;
    processEvent(candidates.get(0));
    if (nextEventSequence != cursor) {
      return;
    }
  }

  private void drainBufferedTargetEvents() {
    while (true) {
      var sourceCandidates =
          targetSourceEventObservations.values().stream()
              .filter(event -> event.eventSequence() == nextEventSequence)
              .toList();
      var formalCandidates =
          eventObservations.values().stream()
              .filter(
                  observation ->
                      !observation.applied()
                          && observation.event().eventSequence() == nextEventSequence)
              .map(EventObservation::event)
              .toList();
      if (sourceCandidates.size() > 1
          || formalCandidates.size() > 1
          || (!sourceCandidates.isEmpty()
              && !formalCandidates.isEmpty()
              && !formalEventRejectionRecoveryEnabled)) {
        protocolErrorCode = "EVENT_SEQUENCE_ID_CONFLICT";
        return;
      }
      if (!sourceCandidates.isEmpty()) {
        nextEventSequence++;
        protocolErrorCode = null;
        continue;
      }
      if (!formalCandidates.isEmpty()) {
        long cursor = nextEventSequence;
        processEvent(formalCandidates.get(0), null, false);
        if (nextEventSequence == cursor) {
          return;
        }
        continue;
      }
      refreshBufferedTargetEventGap();
      return;
    }
  }

  private void refreshBufferedTargetEventGap() {
    long nextSourceSequence =
        targetSourceEventObservations.values().stream()
            .mapToLong(TargetIntakeSourceEventRef::eventSequence)
            .filter(sequence -> sequence > nextEventSequence)
            .min()
            .orElse(Long.MAX_VALUE);
    long nextFormalSequence =
        eventObservations.values().stream()
            .filter(
                observation ->
                    !observation.applied() && reservesFormalEventSequence(observation))
            .map(EventObservation::event)
            .mapToLong(IntakeDomainEventRef::eventSequence)
            .filter(sequence -> sequence > nextEventSequence)
            .min()
            .orElse(Long.MAX_VALUE);
    if (nextSourceSequence == Long.MAX_VALUE && nextFormalSequence == Long.MAX_VALUE) {
      return;
    }
    if (nextSourceSequence == nextFormalSequence) {
      protocolErrorCode = "EVENT_SEQUENCE_ID_CONFLICT";
      return;
    }
    protocolErrorCode =
        nextSourceSequence < nextFormalSequence
            ? "TARGET_SOURCE_EVENT_SEQUENCE_GAP"
            : "EVENT_SEQUENCE_GAP";
  }

  private boolean canStoreTargetSourceObservation(boolean future) {
    long unapplied =
        targetSourceEventObservations.values().stream()
            .filter(observed -> observed.eventSequence() >= nextEventSequence)
            .count();
    int retainedLimit = future ? RECENT_CAPACITY - 1 : RECENT_CAPACITY;
    return unapplied < retainedLimit;
  }

  private void storeTargetSourceObservation(
      TargetIntakeSourceEventRef event, boolean future) {
    int retainedLimit = future ? RECENT_CAPACITY - 1 : RECENT_CAPACITY;
    while (targetSourceEventObservations.size() >= retainedLimit) {
      Iterator<Map.Entry<String, TargetIntakeSourceEventRef>> iterator =
          targetSourceEventObservations.entrySet().iterator();
      boolean removed = false;
      while (iterator.hasNext()) {
        if (iterator.next().getValue().eventSequence() < nextEventSequence) {
          iterator.remove();
          removed = true;
          break;
        }
      }
      if (!removed) {
        throw new IllegalStateException("target source event capacity preflight was inconsistent");
      }
    }
    targetSourceEventObservations.put(event.eventId(), event);
  }

  private boolean hasSourceEventSequenceConflict(TargetIntakeSourceEventRef event) {
    return targetSourceEventObservations.values().stream()
        .anyMatch(
            observed ->
                observed.eventSequence() == event.eventSequence()
                    && !observed.eventId().equals(event.eventId()));
  }

  private boolean hasFormalEventSequenceConflict(long eventSequence, String eventId) {
    return eventObservations.values().stream()
        .filter(this::reservesFormalEventSequence)
        .map(EventObservation::event)
        .anyMatch(
            observed ->
                observed.eventSequence() == eventSequence
                    && !observed.eventId().equals(eventId));
  }

  private boolean reservesFormalEventSequence(EventObservation observation) {
    return !formalEventRejectionRecoveryEnabled
        || observation.applied()
        || observation.event().eventSequence() > nextEventSequence;
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
            && activeTargetFinalizationRecovery == null
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
    recoverTerminalNoCommitTargetAgentRun(command);
    reconcileLateCommittedTargetAgentRun(command);
    if (command.executionContext() != null) {
      if (!matchesTargetAgentRunAuthority(command)) {
        if (deferTargetAgentRunCommand(command)) {
          return;
        }
        rejectCommand(command, "COMMAND_TARGET_AGENT_RUN_AUTHORITY_MISMATCH", true);
        return;
      }
    }
    if (preemptsActivityCommand(command) && deferCancellation(command)) {
      return;
    }
    if (deferPinnedTargetBranchConfirmation(command)) {
      return;
    }
    String rejection = businessRejection(command);
    if (rejection != null) {
      rejectCommand(command, rejection, true);
      return;
    }
    if (command.executionContext() != null && command.executionContext().isTargetAgentRun()) {
      if (targetAgentRunPreCommandState != null) {
        rejectCommand(command, "COMMAND_TARGET_AGENT_RUN_STATE_CONFLICT", true);
        return;
      }
      targetAgentRunPreCommandState =
          new TargetAgentRunPreCommandState(
              command.commandId(),
              roomPhase,
              activeParty,
              readinessParty,
              nextEventSequence - 1);
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
    processEvent(event, null, true);
  }

  private void processEvent(
      IntakeDomainEventRef event, String expectedActivityOperationKey) {
    processEvent(event, expectedActivityOperationKey, true);
  }

  private void processEvent(
      IntakeDomainEventRef event,
      String expectedActivityOperationKey,
      boolean drainBufferedEventsAfterApply) {
    if (targetSourceEventObservations.containsKey(event.eventId())) {
      protocolErrorCode = "EVENT_ID_REUSE_CONFLICT";
      return;
    }
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
      if (hasFormalEventSequenceConflict(event.eventSequence(), event.eventId())
          || targetSourceEventObservations.values().stream()
              .anyMatch(source -> source.eventSequence() == event.eventSequence())) {
        protocolErrorCode = "EVENT_SEQUENCE_ID_CONFLICT";
        return;
      }
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
    boolean initialTargetAgentRunEvent = matchesInitialTargetAgentRunEvent(event);
    boolean winningAttemptEvent = false;
    if (!initialTargetAgentRunEvent && matchesLegacyWinningTargetEvent(event)) {
      int gapRecoveryVersion =
          Workflow.getVersion(
              AGENT_RUN_COMMITTED_EVENT_GAP_RECOVERY_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
      winningAttemptEvent =
          gapRecoveryVersion == Workflow.DEFAULT_VERSION
              || matchesAuthoritativeWinningTargetEvent(event);
    }
    if (!pendingCommand.commandId().equals(event.commandId()) && !winningAttemptEvent) {
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
    boolean targetAgentRunOperation =
        targetAgentRunChild != null
            && targetAgentRunChild.status()
                == IntakeAgentRunChildState.Status.RECEIPT_COMMITTED
            && targetAgentRunChild.commandId().equals(pendingCommand.commandId())
            && targetAgentRunChild.finalizationOperationKey().equals(event.operationKey())
            && (expectedActivityOperationKey == null
                || expectedActivityOperationKey.equals(
                    targetAgentRunChild.finalizationOperationKey()));
    if (targetAgentRunOperation && !initialTargetAgentRunEvent && !winningAttemptEvent) {
      protocolErrorCode = "EVENT_AGENT_RUN_IDENTITY_MISMATCH";
      return;
    }
    if (!rootOperation && !stageOperation && !targetAgentRunOperation) {
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
        && !(winningAttemptEvent
            ? event.commandId().equals(event.graphExecutionRef().graphCommandId())
            : pendingCommand.commandId().equals(event.graphExecutionRef().graphCommandId()))) {
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
    if (targetAgentRunPreCommandState != null
        && targetAgentRunPreCommandState.commandId().equals(pendingCommand.commandId())) {
      targetAgentRunPreCommandState = null;
    }
    pendingCommand = null;
    // The child state authorizes only this command's finalization event. Once that event is
    // consumed, retain the public references above but release the per-command child slot.
    if (targetAgentRunOperation) {
      targetAgentRunChild = null;
      authoritativeCommittedTargetFinalization = null;
      targetAgentRunPreCommandState = null;
    }
    activityExecution = null;
    sharedActivityRetriesRemaining = 0;
    activityExecutionAuthorized = false;
    protocolErrorCode = null;
    if (drainBufferedEventsAfterApply && futureTargetSourceCursorBufferingEnabled) {
      drainBufferedTargetEvents();
    }
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
        if (targetAgentRunChildEnabled(context)) {
          ensureTargetThreadInitialization(command, context);
          orchestrateTargetAgentRun(command, context);
          return;
        }
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
      if (command.executionContext() != null
          && command.executionContext().isTargetAgentRun()
          && postCommitTargetReconciliationEnabled()
          && reconcileCommittedTargetAgentRunWithBackoff(command)) {
        return;
      }
      // Receipt validation failures are fail-closed and leave the command pending for recovery.
      protocolErrorCode = "INTAKE_ACTIVITY_RECEIPT_INVALID";
    }
  }

  private void orchestrateTargetAgentRun(
      IntakeWorkflowCommand command, IntakeCommandExecutionContext context) {
    IntakeTargetAgentRunContext target = context.targetAgentRun();
    target.requireMatches(start, command, processRevision, roomRevision);
    String childWorkflowId = IntakeAgentRunChildIds.forCommand(command);
    boolean newlyStarted = targetAgentRunChild == null;
    if (newlyStarted) {
      authoritativeCommittedTargetFinalization = null;
      targetAgentRunChild = IntakeAgentRunChildState.pending(childWorkflowId, target);
      activityExecution = null;
    } else {
      targetAgentRunChild.requireMatches(command, target);
    }

    if (!newlyStarted && targetAgentRunChild.status() == IntakeAgentRunChildState.Status.PENDING) {
      IntakeAgentRunFinalizationReadResult recovery =
          readTargetFinalization(
              command,
              IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION);
      if (settleTargetFinalization(command, recovery)) {
        return;
      }
      protocolErrorCode = "TARGET_AGENT_RUN_CHILD_UNRESOLVED";
      return;
    }

    if (newlyStarted) {
      Duration remaining = remainingTargetDeadline(context);
      AgentRunWorkflow child =
          Workflow.newChildWorkflowStub(
              AgentRunWorkflow.class,
              ChildWorkflowOptions.newBuilder()
                  .setWorkflowId(childWorkflowId)
                  .setTaskQueue(AGENT_EXECUTION)
                  .setWorkflowExecutionTimeout(remaining)
                  .setWorkflowRunTimeout(remaining)
                  .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                  .setParentClosePolicy(PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                  .setCancellationType(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED)
                  .build());
      Promise<ExecuteAgentRunResult> childResult = Async.function(child::run, target.request());
      ExecuteAgentRunResult result = childResult.get();
      requireTargetChildResult(command, target, result, winningAttemptEnabled);
      if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED) {
        terminateTargetAgentRunWithoutCommit(command, target, result);
        return;
      }
      targetAgentRunChild = targetAgentRunChild.resultReady(result.resultHash());
    }

    if (targetAgentRunChild.status() == IntakeAgentRunChildState.Status.RESULT_READY) {
      IntakeAgentRunFinalizationReadResult finalization =
          readTargetFinalization(
              command,
              IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);
      if (!settleTargetFinalization(command, finalization)) {
        if (finalization.resolution() == IntakeAgentRunFinalizationReadResult.Resolution.PENDING
            && postCommitTargetReconciliationEnabled()
            && reconcileCommittedTargetAgentRunWithBackoff(command)) {
          return;
        }
        protocolErrorCode = "TARGET_AGENT_RUN_FINALIZATION_UNRESOLVED";
      }
    }
  }

  private IntakeAgentRunFinalizationReadResult readTargetFinalization(
      IntakeWorkflowCommand command, IntakeAgentRunFinalizationReadRequest.Mode mode) {
    return readTargetFinalization(command, mode, remainingTargetDeadline(command.executionContext()));
  }

  private IntakeAgentRunFinalizationReadResult readTargetFinalization(
      IntakeWorkflowCommand command,
      IntakeAgentRunFinalizationReadRequest.Mode mode,
      Duration readWindow) {
    IntakeAgentRunFinalizationReadRequest request = targetFinalizationReadRequest(command, mode);
    IntakeAgentRunFinalizationReadResult result =
        Workflow.newActivityStub(
                IntakeAgentRunFinalizationReadActivities.class,
                IntakeAgentRunFinalizationReadPolicy.options(readWindow))
            .readFinalization(request);
    if (result == null) {
      throw new IllegalArgumentException("target finalization lookup returned no resolution");
    }
    result.requireMatches(request, winningAttemptEnabled);
    return result;
  }

  private void reconcileLateCommittedTargetAgentRun(IntakeWorkflowCommand incoming) {
    if (activeTargetFinalizationRecovery != null
        || pendingCommand == null
        || pendingCommand.commandId().equals(incoming.commandId())
        || targetAgentRunChild == null
        || !targetAgentRunChild.unresolved()
        || !lateCommitTargetReconciliationEnabled()) {
      return;
    }
    reconcileCommittedTargetAgentRun(
        pendingWorkflowCommand(), FINALIZATION_RECONCILIATION_READ_TIMEOUT);
  }

  /**
   * A committed receipt can precede the case-timeline cursor event that authorizes its formal
   * Intake event. Hold the next command only after its own authority is valid; the old child
   * remains the sole authority until the buffered formal event is applied.
   */
  private boolean deferTargetAgentRunCommand(IntakeWorkflowCommand incoming) {
    if (incoming.executionContext() == null
        || !incoming.executionContext().isTargetAgentRun()
        || pendingCommand == null
        || pendingCommand.commandId().equals(incoming.commandId())
        || pendingCommand.executionContext() == null
        || !pendingCommand.executionContext().isTargetAgentRun()
        || targetAgentRunChild == null
        || targetAgentRunChild.status()
            != IntakeAgentRunChildState.Status.RECEIPT_COMMITTED
        || !targetAgentRunChild.commandId().equals(pendingCommand.commandId())) {
      return false;
    }
    IntakeDomainEventRef bufferedEvent = bufferedFormalEventForPendingTargetAgentRun();
    if (bufferedEvent == null) {
      return false;
    }
    try {
      targetAgentRunChild.requireMatches(
          pendingWorkflowCommand(), pendingCommand.executionContext().targetAgentRun());
      incoming.executionContext()
          .targetAgentRun()
          .requireMatches(
              start,
              incoming,
              bufferedEvent.processRevision(),
              bufferedEvent.roomRevision());
    } catch (IllegalArgumentException mismatch) {
      return false;
    }
    return enqueueDeferredTargetCommand(
        incoming, AGENT_RUN_COMMITTED_EVENT_GAP_RECOVERY_CHANGE_ID);
  }

  private boolean deferPinnedTargetBranchConfirmation(IntakeWorkflowCommand incoming) {
    IntakeCommandExecutionContext context = incoming.executionContext();
    if (context == null
        || !context.isTargetBranch()
        || !context.hasPinnedTargetBranchAuthority()
        || incoming.commandType() != IntakeCommandType.INTAKE_CONFIRM
        || pendingCommand == null
        || pendingCommand.commandId().equals(incoming.commandId())
        || pendingCommand.executionContext() == null
        || !pendingCommand.executionContext().isTargetAgentRun()
        || targetAgentRunChild == null
        || targetAgentRunChild.status()
            != IntakeAgentRunChildState.Status.RECEIPT_COMMITTED
        || !targetAgentRunChild.commandId().equals(pendingCommand.commandId())) {
      return false;
    }
    IntakeDomainEventRef bufferedEvent = bufferedFormalEventForPendingTargetAgentRun();
    if (bufferedEvent == null
        || bufferedEvent.eventType() != IntakeDomainEventType.TURN_READY_TO_CONFIRM
        || bufferedEvent.party() != incoming.party()
        || context.expectedProcessRevision().longValue() != bufferedEvent.processRevision()
        || context.expectedRoomRevision().longValue() != bufferedEvent.roomRevision()) {
      return false;
    }
    try {
      targetAgentRunChild.requireMatches(
          pendingWorkflowCommand(), pendingCommand.executionContext().targetAgentRun());
    } catch (IllegalArgumentException mismatch) {
      return false;
    }
    return enqueueDeferredTargetCommand(
        incoming, TARGET_BRANCH_CONFIRMATION_EVENT_GAP_RECOVERY_CHANGE_ID);
  }

  private boolean enqueueDeferredTargetCommand(IntakeWorkflowCommand incoming, String changeId) {
    for (IntakeWorkflowCommand deferred : deferredTargetCommands) {
      if (deferred.commandId().equals(incoming.commandId())) {
        return deferred.equals(incoming);
      }
    }
    if (!deferredTargetCommands.isEmpty()) {
      return false;
    }
    if (Workflow.getVersion(changeId, Workflow.DEFAULT_VERSION, 1) != 1) {
      return false;
    }
    deferredTargetCommands.addLast(incoming);
    return true;
  }

  private IntakeDomainEventRef bufferedFormalEventForPendingTargetAgentRun() {
    var buffered =
        eventObservations.values().stream()
            .filter(
                observation ->
                    !observation.applied()
                        && observation.event().eventSequence() > nextEventSequence)
            .map(EventObservation::event)
            .toList();
    if (buffered.size() != 1) {
      return null;
    }
    IntakeDomainEventRef event = buffered.get(0);
    IntakeTargetAgentRunContext pendingTarget = pendingCommand.executionContext().targetAgentRun();
    IntakeGraphExecutionRef graph = event.graphExecutionRef();
    if (!matchesEnvelope(event)
        || event.party() != pendingCommand.party()
        || !event.actorScopeHash().equals(pendingCommand.actorScopeHash())
        || !event.operationKey().equals(targetAgentRunChild.finalizationOperationKey())
        || !event.requestHash().equals(pendingCommand.requestHash())
        || !event.resultHash().equals(targetAgentRunChild.resultHash())
        || event.agentRunRef() == null
        || !event.agentRunRef().logicalRunId().equals(targetAgentRunChild.logicalRunId())
        || graph == null
        || !graph.graphVersion().equals(start.graphVersion())
        || !graph.threadId().equals(pendingCommand.executionContext().threadId())
        || !graph.resultHash().equals(targetAgentRunChild.resultHash())
        || !pendingTarget.request().logicalRunId().equals(targetAgentRunChild.logicalRunId())
        || event.processRevision() < processRevision
        || event.roomRevision() < roomRevision
        || !eventAllowedForPendingCommand(event.eventType())) {
      return null;
    }
    boolean initialAttemptIdentity = matchesInitialTargetAgentRunEvent(event);
    if (!initialAttemptIdentity && !matchesAuthoritativeWinningTargetEvent(event)) {
      return null;
    }
    return event;
  }

  private boolean matchesInitialTargetAgentRunEvent(IntakeDomainEventRef event) {
    return pendingCommand != null
        && targetAgentRunChild != null
        && targetAgentRunChild.status()
            == IntakeAgentRunChildState.Status.RECEIPT_COMMITTED
        && targetAgentRunChild.commandId().equals(pendingCommand.commandId())
        && event.agentRunRef() != null
        && event.graphExecutionRef() != null
        && event.commandId().equals(pendingCommand.commandId())
        && event.agentRunRef().attemptId().equals(targetAgentRunChild.attemptId())
        && event.graphExecutionRef().graphCommandId().equals(pendingCommand.commandId());
  }

  private boolean matchesLegacyWinningTargetEvent(IntakeDomainEventRef event) {
    return winningAttemptEnabled
        && targetAgentRunChild != null
        && targetAgentRunChild.status()
            == IntakeAgentRunChildState.Status.RECEIPT_COMMITTED
        && event.agentRunRef() != null
        && targetAgentRunChild.logicalRunId().equals(event.agentRunRef().logicalRunId())
        && targetAgentRunChild.finalizationOperationKey().equals(event.operationKey());
  }

  private boolean matchesAuthoritativeWinningTargetEvent(IntakeDomainEventRef event) {
    if (!matchesLegacyWinningTargetEvent(event)
        || authoritativeCommittedTargetFinalization == null
        || pendingCommand == null
        || targetAgentRunChild == null
        || event.graphExecutionRef() == null) {
      return false;
    }
    var authoritative = authoritativeCommittedTargetFinalization;
    var locator = authoritative.locator();
    var receipt = authoritative.receipt();
    return !locator.attemptId().equals(targetAgentRunChild.attemptId())
        && !event.commandId().equals(pendingCommand.commandId())
        && receipt.committedEvent().equals(event)
        && receipt.formalReceipt().commandId().equals(event.commandId())
        && locator.attemptId().equals(event.agentRunRef().attemptId())
        && event.commandId().equals(event.graphExecutionRef().graphCommandId())
        && locator.operationKey().equals(targetAgentRunChild.finalizationOperationKey())
        && locator.logicalRunId().equals(targetAgentRunChild.logicalRunId())
        && locator.resultHash().equals(targetAgentRunChild.resultHash())
        && receipt.operation().requestHash().equals(pendingCommand.requestHash());
  }

  private boolean reconcileCommittedTargetAgentRun(
      IntakeWorkflowCommand command, Duration readWindow) {
    return reconcileCommittedTargetAgentRunOnce(command, readWindow)
        == TargetFinalizationReconciliation.SETTLED;
  }

  private boolean reconcileCommittedTargetAgentRunWithBackoff(IntakeWorkflowCommand command) {
    Duration delay = POST_COMMIT_RECONCILIATION_INITIAL_DELAY;
    for (int attempt = 1; attempt <= POST_COMMIT_RECONCILIATION_ATTEMPTS; attempt++) {
      TargetFinalizationReconciliation reconciliation =
          reconcileCommittedTargetAgentRunOnce(
              command, FINALIZATION_RECONCILIATION_READ_TIMEOUT);
      if (reconciliation == TargetFinalizationReconciliation.SETTLED) {
        return true;
      }
      if (reconciliation != TargetFinalizationReconciliation.PENDING
          || attempt == POST_COMMIT_RECONCILIATION_ATTEMPTS) {
        return false;
      }
      Workflow.sleep(delay);
      delay = delay.multipliedBy(2);
    }
    return false;
  }

  private TargetFinalizationReconciliation reconcileCommittedTargetAgentRunOnce(
      IntakeWorkflowCommand command, Duration readWindow) {
    if (command == null
        || command.executionContext() == null
        || !command.executionContext().isTargetAgentRun()
        || targetAgentRunChild == null
        || !targetAgentRunChild.unresolved()
        || !targetAgentRunChild.commandId().equals(command.commandId())) {
      return TargetFinalizationReconciliation.UNRESOLVED;
    }
    try {
      IntakeAgentRunFinalizationReadResult result =
          readTargetFinalization(
              command,
              IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION,
              readWindow);
      if (result.resolution() == IntakeAgentRunFinalizationReadResult.Resolution.PENDING) {
        return TargetFinalizationReconciliation.PENDING;
      }
      if (!settleTargetFinalization(command, result)) {
        return TargetFinalizationReconciliation.UNRESOLVED;
      }
      return pendingCommand == null || !pendingCommand.commandId().equals(command.commandId())
          ? TargetFinalizationReconciliation.SETTLED
          : TargetFinalizationReconciliation.UNRESOLVED;
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (RuntimeException unresolved) {
      return TargetFinalizationReconciliation.UNRESOLVED;
    }
  }

  private static boolean postCommitTargetReconciliationEnabled() {
    return Workflow.getVersion(
            AGENT_RUN_POST_COMMIT_RECONCILIATION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        == 1;
  }

  private static boolean lateCommitTargetReconciliationEnabled() {
    return Workflow.getVersion(
            AGENT_RUN_LATE_COMMIT_RECONCILIATION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        == 1;
  }

  private boolean settleTargetFinalization(
      IntakeWorkflowCommand command,
      IntakeAgentRunFinalizationReadResult result) {
    return switch (result.resolution()) {
      case COMMITTED -> {
        commitTargetFinalization(result);
        processEvent(
            result.receipt().committedEvent(), result.receipt().operation().operationKey());
        yield true;
      }
      case ABSENT_TERMINAL -> {
        // Legacy histories treated an ordinary ABSENT read as unresolved. Do not terminalize
        // replayed histories until this exact execution records the release marker.
        if (Workflow.getVersion(
                AGENT_RUN_TERMINAL_NO_COMMIT_RELEASE_CHANGE_ID,
                Workflow.DEFAULT_VERSION,
                1)
            != 1) {
          yield false;
        }
        yield terminateTargetAgentRunWithoutCommit(
            command, "TARGET_AGENT_RUN_FINALIZATION_ABSENT");
      }
      case TERMINAL_NO_COMMIT ->
          settleFinalizationRejectedTargetAgentRun(command, result);
      case PENDING -> false;
    };
  }

  private boolean settleFinalizationRejectedTargetAgentRun(
      IntakeWorkflowCommand command,
      IntakeAgentRunFinalizationReadResult result) {
    if (Workflow.getVersion(
            AGENT_RUN_FINALIZATION_REJECTED_RELEASE_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            1)
        != 1) {
      return false;
    }
    if (command.executionContext() == null
        || !command.executionContext().isTargetAgentRun()
        || result.terminalNoCommitEvidence() == null) {
      protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
      return false;
    }
    IntakeTargetAgentRunContext target = command.executionContext().targetAgentRun();
    ExecuteAgentRunResult terminalResult = result.terminalNoCommitEvidence().terminalResult();
    try {
      requireTargetChildResult(command, target, terminalResult, winningAttemptEnabled);
    } catch (IllegalArgumentException mismatch) {
      protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
      return false;
    }
    return terminateTargetAgentRunWithoutCommit(command, target, terminalResult);
  }

  private void commitTargetFinalization(IntakeAgentRunFinalizationReadResult result) {
    IntakeAgentRunChildState committedChild = targetAgentRunChild;
    if (committedChild == null) {
      throw new IllegalArgumentException("committed finalization requires an AgentRun child");
    }
    if (committedChild.resultHash() == null) {
      committedChild = committedChild.resultReady(result.locator().resultHash());
    }
    committedChild = committedChild.committed(result, winningAttemptEnabled);
    if (authoritativeCommittedTargetFinalization != null
        && !authoritativeCommittedTargetFinalization.equals(result)) {
      throw new IllegalArgumentException("committed AgentRun finalization identity changed");
    }
    targetAgentRunChild = committedChild;
    authoritativeCommittedTargetFinalization = result;
  }

  private void recoverTerminalNoCommitTargetAgentRun(IntakeWorkflowCommand incoming) {
    if (pendingCommand == null
        || pendingCommand.commandId().equals(incoming.commandId())
        || targetAgentRunChild == null
        || targetAgentRunChild.status()
            != IntakeAgentRunChildState.Status.TERMINAL_NO_COMMIT
        || authoritativeCommittedTargetFinalization != null
        || Workflow.getVersion(
                AGENT_RUN_TERMINAL_NO_COMMIT_RECOVERY_CHANGE_ID,
                Workflow.DEFAULT_VERSION,
                1)
            != 1) {
      return;
    }
    IntakeWorkflowCommand terminalCommand = pendingWorkflowCommand();
    if (releaseTargetAgentRunWithoutCommit(terminalCommand.commandId())) {
      recordTerminalTargetAgentRunDecision(
          terminalCommand, "TARGET_AGENT_RUN_TERMINAL_NO_COMMIT");
    }
  }

  private boolean terminateTargetAgentRunWithoutCommit(
      IntakeWorkflowCommand command,
      IntakeTargetAgentRunContext target,
      ExecuteAgentRunResult result) {
    if (Workflow.getVersion(
            AGENT_RUN_TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            1)
        != 1) {
      return terminateTargetAgentRunWithoutCommit(
          command, "TARGET_AGENT_RUN_" + result.outcome().name());
    }
    if (result.outcome() != ExecuteAgentRunResult.Outcome.FAILED
        || result.retryable()
        || result.recoveryAction()
            != com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction
                .FAIL_LOGICAL_RUN) {
      protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
      return false;
    }
    long terminalProcessRevision = Math.incrementExact(target.expectedProcessRevision());
    long terminalRoomRevision = Math.incrementExact(target.expectedRoomRevision());
    var graphCommand = target.request().command();
    var eventRef = graphCommand.eventRef();
    TargetAgentRunPreCommandState preCommandState = targetAgentRunPreCommandState;
    if (processRevision != target.expectedProcessRevision()
        || roomRevision != target.expectedRoomRevision()
        || eventRef == null
        || preCommandState == null
        || !preCommandState.commandId().equals(command.commandId())) {
      protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
      return false;
    }
    boolean useV2Authority =
        Workflow.getVersion(
                AGENT_RUN_TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_V2_CHANGE_ID,
                Workflow.DEFAULT_VERSION,
                1)
            == 1;
    TargetIntakeCommandTerminalNoCommit observedAuthority =
        new TargetIntakeCommandTerminalNoCommit(
            useV2Authority
                ? TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION
                : TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION,
            start.tenantSurrogate(),
            start.caseId(),
            com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE,
            start.roomEpoch(),
            start.fencingToken(),
            Workflow.getInfo().getWorkflowId(),
            Workflow.getInfo().getFirstExecutionRunId(),
            start.workflowBuildId(),
            target.activationId(),
            target.activationManifestHash(),
            target.caseBuildId(),
            target.controlBuildId(),
            target.agentBuildId(),
            target.graphBindingHash(),
            target.graphCodeBuildId(),
            target.commandHash(),
            target.commandEnvelopeHash(),
            target.request().logicalInputHash(),
            useV2Authority ? graphCommand.requestHash() : null,
            command.commandId(),
            command.sequence(),
            command.requestHash(),
            eventRef.artifactId(),
            command.payloadRef(),
            command.payloadHash(),
            target.expectedProcessRevision(),
            terminalProcessRevision,
            target.expectedRoomRevision(),
            terminalRoomRevision,
            useV2Authority ? preCommandState.lastCaseEventSequence() : null,
            nextEventSequence - 1,
            result.logicalRunId(),
            target.request().attemptId(),
            result.attemptId(),
            result.attemptNo(),
            result.publicOutputEmitted()
                ? AgentRunAttemptStatus.ABORTED
                : AgentRunAttemptStatus.FAILED,
            result.outcome(),
            result.errorCode(),
            result.retryable(),
            result.recoveryAction(),
            result.lastSequenceNo(),
            result.publicOutputEmitted(),
            result.completedAt());
    boolean useV3Authority =
        useV2Authority
            && Workflow.getVersion(
                    AGENT_RUN_TERMINAL_NO_COMMIT_PARENT_CONVERGENCE_V3_CHANGE_ID,
                    Workflow.DEFAULT_VERSION,
                    1)
                == 1;
    ResolveTargetIntakeTerminalNoCommitResult resolved = null;
    ConvergeTargetIntakeTerminalNoCommitResult convergence = null;
    TargetIntakeCommandTerminalNoCommit authority = observedAuthority;
    if (useV3Authority) {
      resolved = resolveStrictV3TerminalNoCommit(observedAuthority);
      authority = resolved.authority();
      convergence = convergeStrictV3TerminalNoCommit(resolved);
      requireAcknowledgedTerminalNoCommit(authority, resolved, convergence);
    }
    if (pendingCommand == null
        || !pendingCommand.commandId().equals(command.commandId())
        || targetAgentRunChild == null
        || !targetAgentRunChild.commandId().equals(command.commandId())
        || targetAgentRunChild.status()
            == IntakeAgentRunChildState.Status.RECEIPT_COMMITTED
        || authoritativeCommittedTargetFinalization != null) {
      protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
      return false;
    }
    try {
      targetAgentRunChild.requireMatches(command, target);
      targetAgentRunChild = targetAgentRunChild.terminalNoCommit();
    } catch (IllegalArgumentException | IllegalStateException mismatch) {
      protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
      return false;
    }
    if (!releaseTargetAgentRunWithoutCommit(command.commandId())) {
      return false;
    }
    processRevision = terminalProcessRevision;
    roomRevision = terminalRoomRevision;
    recordTerminalTargetAgentRunDecision(command, result.errorCode());
    protocolErrorCode = result.errorCode();
    signalTerminalNoCommit(authority);
    if (useV3Authority) {
      cacheAcknowledgedTerminalNoCommit(observedAuthority, resolved, convergence);
    } else {
      cacheTerminalNoCommitEmission(authority);
    }
    return true;
  }

  private ResolveTargetIntakeTerminalNoCommitResult resolveStrictV3TerminalNoCommit(
      TargetIntakeCommandTerminalNoCommit observedAuthority) {
    return caseCommandLifecycleActivities.resolveTargetIntakeTerminalNoCommit(
        new ResolveTargetIntakeTerminalNoCommit(
            ResolveTargetIntakeTerminalNoCommit.V2_SCHEMA_VERSION,
            observedAuthority,
            targetSourceEventObservations.values().stream()
                .filter(
                    event -> event.eventSequence() <= observedAuthority.lastCaseEventSequence())
                .sorted(Comparator.comparingLong(TargetIntakeSourceEventRef::eventSequence))
                .toList()));
  }

  private ConvergeTargetIntakeTerminalNoCommitResult convergeStrictV3TerminalNoCommit(
      ResolveTargetIntakeTerminalNoCommitResult resolved) {
    return caseCommandLifecycleActivities.convergeTargetIntakeTerminalNoCommit(
        new ConvergeTargetIntakeTerminalNoCommit(
            "converge-target-intake-terminal-no-commit.v1",
            resolved.authority(),
            resolved.caseWorkflowId(),
            resolved.caseWorkflowRunId(),
            resolved.caseWorkflowBuildId()));
  }

  private static void requireAcknowledgedTerminalNoCommit(
      TargetIntakeCommandTerminalNoCommit authority,
      ResolveTargetIntakeTerminalNoCommitResult resolved,
      ConvergeTargetIntakeTerminalNoCommitResult convergence) {
    if (resolved == null
        || convergence == null
        || !ResolveTargetIntakeTerminalNoCommitResult.V2_SCHEMA_VERSION.equals(
            resolved.schemaVersion())
        || !TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
            authority.schemaVersion())
        || !authority.equals(resolved.authority())
        || !authority.equals(convergence.authority())
        || !authority.receiptUri().equals(resolved.receiptUri())
        || !authority.receiptSha256().equals(resolved.receiptSha256())
        || !authority.receiptUri().equals(convergence.receiptUri())
        || !authority.receiptSha256().equals(convergence.receiptSha256())
        || convergence.processRevision() < authority.newProcessRevision()
        || convergence.roomRevision() < authority.newRoomRevision()
        || convergence.lastCommandSequence() < authority.caseCommandSequence()
        || convergence.lastCaseEventSequence()
            < authority.newProjectionLastCaseEventSequence()) {
      throw new IllegalArgumentException(
          "terminal-no-commit convergence returned conflicting acknowledgement");
    }
  }

  /**
   * Intake child 向案件根工作流回传“已执行、尚未由父流程落账”的终态 authority。下游
   * {@code CaseProcessWorkflow.targetIntakeCommandTerminalNoCommit} 只入队，随后由父 workflow 的
   * command-lifecycle 活动以幂等方式收敛命令账本；这使 child 重试不会重复完成同一命令。
   */
  private void signalTerminalNoCommit(TargetIntakeCommandTerminalNoCommit authority) {
    CaseProcessWorkflow parent =
        Workflow.newExternalWorkflowStub(
            CaseProcessWorkflow.class,
            CaseProcessWorkflowProtocol.caseWorkflowId(
                authority.tenantSurrogate(), authority.caseId()));
    parent.targetIntakeCommandTerminalNoCommit(authority);
  }

  private void cacheTerminalNoCommitEmission(
      TargetIntakeCommandTerminalNoCommit authority) {
    IntakeTerminalNoCommitRecoveryRequest request =
        new IntakeTerminalNoCommitRecoveryRequest(
            TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION.equals(
                    authority.schemaVersion())
                ? IntakeTerminalNoCommitRecoveryRequest.LEGACY_SCHEMA_VERSION
                : IntakeTerminalNoCommitRecoveryRequest.SCHEMA_VERSION,
            Workflow.getInfo().getWorkflowId(),
            Workflow.getInfo().getRunId(),
            authority);
    ResolveTargetIntakeTerminalNoCommitResult resolved =
        new ResolveTargetIntakeTerminalNoCommitResult(
            "resolve-target-intake-terminal-no-commit-result.v1",
            authority,
            authority.receiptUri(),
            authority.receiptSha256());
    completedTerminalNoCommitRecoveryRequest = request;
    completedTerminalNoCommitRecoveryResult =
        new IntakeTerminalNoCommitRecoveryResult(
            IntakeTerminalNoCommitRecoveryResult.SCHEMA_VERSION,
            IntakeTerminalNoCommitRecoveryResult.Disposition.ALREADY_EMITTED,
            request,
            resolved);
  }

  private void cacheAcknowledgedTerminalNoCommit(
      TargetIntakeCommandTerminalNoCommit observedAuthority,
      ResolveTargetIntakeTerminalNoCommitResult resolved,
      ConvergeTargetIntakeTerminalNoCommitResult convergence) {
    IntakeTerminalNoCommitRecoveryRequest request =
        new IntakeTerminalNoCommitRecoveryRequest(
            IntakeTerminalNoCommitRecoveryRequest.V3_SCHEMA_VERSION,
            Workflow.getInfo().getWorkflowId(),
            Workflow.getInfo().getRunId(),
            observedAuthority);
    completedTerminalNoCommitRecoveryRequest = request;
    completedTerminalNoCommitRecoveryResult =
        new IntakeTerminalNoCommitRecoveryResult(
            IntakeTerminalNoCommitRecoveryResult.V2_SCHEMA_VERSION,
            IntakeTerminalNoCommitRecoveryResult.Disposition.PARENT_CONVERGED,
            request,
            resolved,
            convergence);
  }

  private boolean terminateTargetAgentRunWithoutCommit(
      IntakeWorkflowCommand command, String reasonCode) {
    if (pendingCommand == null
        || !pendingCommand.commandId().equals(command.commandId())
        || targetAgentRunChild == null
        || !targetAgentRunChild.commandId().equals(command.commandId())
        || targetAgentRunChild.status()
            == IntakeAgentRunChildState.Status.RECEIPT_COMMITTED) {
      protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
      return false;
    }
    if (targetAgentRunChild.status()
        != IntakeAgentRunChildState.Status.TERMINAL_NO_COMMIT) {
      targetAgentRunChild = targetAgentRunChild.terminalNoCommit();
    }
    authoritativeCommittedTargetFinalization = null;
    if (Workflow.getVersion(
            AGENT_RUN_TERMINAL_NO_COMMIT_RELEASE_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        != 1) {
      protocolErrorCode = reasonCode;
      return false;
    }
    if (!releaseTargetAgentRunWithoutCommit(command.commandId())) {
      return false;
    }
    recordTerminalTargetAgentRunDecision(command, reasonCode);
    protocolErrorCode = reasonCode;
    return true;
  }

  private boolean releaseTargetAgentRunWithoutCommit(String commandId) {
    if (pendingCommand == null
        || !pendingCommand.commandId().equals(commandId)
        || pendingCommand.executionContext() == null
        || !pendingCommand.executionContext().isTargetAgentRun()
        || targetAgentRunPreCommandState == null
        || !targetAgentRunPreCommandState.commandId().equals(commandId)
        || authoritativeCommittedTargetFinalization != null
        || (targetAgentRunChild != null
            && (!targetAgentRunChild.commandId().equals(commandId)
                || targetAgentRunChild.status()
                    != IntakeAgentRunChildState.Status.TERMINAL_NO_COMMIT))) {
      protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
      return false;
    }
    if (targetAgentRunChild != null) {
      try {
        targetAgentRunChild.requireMatches(
            pendingWorkflowCommand(), pendingCommand.executionContext().targetAgentRun());
      } catch (IllegalArgumentException mismatch) {
        protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
        return false;
      }
    }
    roomPhase = targetAgentRunPreCommandState.roomPhase();
    activeParty = targetAgentRunPreCommandState.activeParty();
    readinessParty = targetAgentRunPreCommandState.readinessParty();
    pendingCommand = null;
    targetAgentRunChild = null;
    authoritativeCommittedTargetFinalization = null;
    targetAgentRunPreCommandState = null;
    activityExecution = null;
    sharedActivityRetriesRemaining = 0;
    activityExecutionAuthorized = false;
    return true;
  }

  private void recordTerminalTargetAgentRunDecision(
      IntakeWorkflowCommand command, String reasonCode) {
    IntakeCommandDecision terminal = decision(command, "REJECTED", reasonCode);
    lastDecision = terminal;
    commandObservations.put(command.commandId(), new CommandObservation(command, terminal));
  }

  private static void requireTargetChildResult(
      IntakeWorkflowCommand command,
      IntakeTargetAgentRunContext target,
      ExecuteAgentRunResult result,
      boolean allowWinningAttempt) {
    if (result == null
        || !target.request().agentRunId().equals(result.agentRunId())
        || !target.request().logicalRunId().equals(result.logicalRunId())
        || result.attemptNo() < target.request().attemptNo()
        || result.attemptNo() > target.request().attemptLimit()
        || (!allowWinningAttempt
            && (!target.request().attemptId().equals(result.attemptId())
                || target.request().attemptNo() != result.attemptNo()))
        || (result.attemptNo() == target.request().attemptNo()
            && !target.request().attemptId().equals(result.attemptId()))
        || (result.attemptNo() > target.request().attemptNo()
            && target.request().attemptId().equals(result.attemptId()))) {
      throw new IllegalArgumentException("AgentRun child result identity does not match the request");
    }
    if (result.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED
        && (result.graphResult() == null
            || (!allowWinningAttempt
                && !command.commandId().equals(result.graphResult().commandId()))
            || (result.attemptNo() > target.request().attemptNo()
                && command.commandId().equals(result.graphResult().commandId()))
            || !target.request().logicalRunId().equals(result.graphResult().logicalRunId())
            || !result.attemptId().equals(result.graphResult().attemptId())
            || !target.request().command().graphKey().equals(result.graphResult().graphKey())
            || !target.request().command().graphVersion().equals(result.graphResult().graphVersion())
            || !result.resultHash().equals(result.graphResult().outputHash()))) {
      throw new IllegalArgumentException("completed AgentRun result does not match its Graph command");
    }
  }

  private static Duration remainingTargetDeadline(IntakeCommandExecutionContext context) {
    long remainingMillis = context.deadlineEpochMillis() - Workflow.currentTimeMillis();
    if (remainingMillis <= 0) {
      throw new IntakeActivityDeadlineExceeded();
    }
    return Duration.ofMillis(remainingMillis);
  }

  private static boolean targetAgentRunChildEnabled(IntakeCommandExecutionContext context) {
    return context.isTargetAgentRun()
        && Workflow.getVersion(AGENT_RUN_CHILD_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
  }

  private void startOrchestration(IntakeWorkflowCommand command) {
    if (activeTargetFinalizationRecovery != null) {
      throw new IllegalStateException("target finalization recovery is already running");
    }
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
    consumeQueuedCommittedEvent(completedCommandId);
    if (cancellation != null && hasPendingActivityCommand(completedCommandId)) {
      if (!cancellationReconciliationEnabled()) {
        if (!clearPendingActivity(completedCommandId)) {
          return;
        }
        protocolErrorCode = null;
      } else if (!resolvePendingActivityForCancellation(completedCommandId)) {
        return;
      }
    }
    if (cancellation != null) {
      processDeferredCancellation();
    }
  }

  private boolean preemptsActivityCommand(IntakeWorkflowCommand command) {
    return command.commandType() == IntakeCommandType.INTAKE_CANCEL
        && command.party() == IntakeParty.INITIATOR
        && pendingCommand != null
        && pendingCommand.executionContext() != null;
  }

  private boolean deferCancellation(IntakeWorkflowCommand command) {
    if (deferredCancellation != null) {
      if (!deferredCancellation.equals(command)) {
        rejectCommand(command, "CANCELLATION_ALREADY_PENDING", true);
        return true;
      }
    } else {
      deferredCancellation = command;
    }
    if (activeTargetFinalizationRecovery != null) {
      return true;
    }
    if (activeOrchestration != null) {
      activeCancellationRequested = true;
      activeCancellationScope.cancel();
      return true;
    }
    String pendingCommandId = pendingCommand.commandId();
    consumeQueuedCommittedEvent(pendingCommandId);
    if (hasPendingActivityCommand(pendingCommandId)) {
      if (!cancellationReconciliationEnabled()) {
        if (!clearPendingActivity(pendingCommandId)) {
          return true;
        }
        protocolErrorCode = null;
      } else if (!resolvePendingActivityForCancellation(pendingCommandId)) {
        return true;
      }
    }
    deferredCancellation = null;
    return false;
  }

  private void processDeferredCancellation() {
    IntakeWorkflowCommand cancellation = deferredCancellation;
    deferredCancellation = null;
    if (cancellation != null) {
      processCommand(cancellation);
    }
  }

  private boolean resolvePendingActivityForCancellation(String commandId) {
    consumeQueuedCommittedEvent(commandId);
    if (!hasPendingActivityCommand(commandId)) {
      return true;
    }
    if (hasUnresolvedQueuedActivityEvent(commandId)) {
      if (protocolErrorCode == null) {
        protocolErrorCode = "INTAKE_ACTIVITY_RECONCILIATION_UNRESOLVED";
      }
      return false;
    }
    IntakeWorkflowCommand originalCommand = pendingWorkflowCommand();
    CancellationReconciliation reconciliation = reconcileCanceledStage(originalCommand);
    return switch (reconciliation.status()) {
      case UNRESOLVED -> false;
      case ABSENT -> {
        boolean cleared = clearPendingActivity(commandId);
        if (cleared) {
          protocolErrorCode = null;
        }
        yield cleared;
      }
      case COMMITTED -> settleCommittedReconciliation(commandId, reconciliation);
    };
  }

  private boolean settleCommittedReconciliation(
      String commandId, CancellationReconciliation reconciliation) {
    if (reconciliation.committedEvent() == null) {
      // Snapshot and Graph receipts contain no formal event and are safe to abandon on cancellation.
      if (!clearPendingActivity(commandId)) {
        return false;
      }
      protocolErrorCode = null;
      return true;
    }
    processEvent(reconciliation.committedEvent(), reconciliation.operationKey());
    return !hasPendingActivityCommand(commandId);
  }

  private CancellationReconciliation reconcileCanceledStage(
      IntakeWorkflowCommand command) {
    if (targetAgentRunChild != null
        && targetAgentRunChild.commandId().equals(command.commandId())
        && targetAgentRunChild.unresolved()) {
      return reconcileCanceledTargetAgentRun(command);
    }
    IntakeActivityExecutionState canceledStage = activityExecution;
    if (canceledStage == null
        || !canceledStage.commandId().equals(command.commandId())) {
      protocolErrorCode = "INTAKE_ACTIVITY_RECONCILIATION_UNRESOLVED";
      return CancellationReconciliation.unresolved();
    }
    sharedActivityRetriesRemaining = 0;
    activityExecutionAuthorized = false;
    CancellationReconciliation[] result = new CancellationReconciliation[1];
    CancellationScope reconciliationScope =
        Workflow.newDetachedCancellationScope(
            () -> result[0] = reconcileStageReceipt(command, canceledStage));
    try {
      reconciliationScope.run();
      if (result[0] != null) {
        return result[0];
      }
      protocolErrorCode = "INTAKE_ACTIVITY_RECONCILIATION_UNRESOLVED";
    } catch (IntakeActivityDeadlineExceeded failure) {
      protocolErrorCode = "INTAKE_ACTIVITY_DEADLINE_EXPIRED";
    } catch (ActivityFailure failure) {
      protocolErrorCode = activityFailureCode(failure);
    } catch (RuntimeException failure) {
      protocolErrorCode =
          failure instanceof IllegalArgumentException
              ? "INTAKE_ACTIVITY_RECEIPT_INVALID"
              : "INTAKE_ACTIVITY_RECONCILIATION_UNRESOLVED";
    }
    return CancellationReconciliation.unresolved();
  }

  private CancellationReconciliation reconcileCanceledTargetAgentRun(
      IntakeWorkflowCommand command) {
    CancellationReconciliation[] reconciliation = new CancellationReconciliation[1];
    CancellationScope reconciliationScope =
        Workflow.newDetachedCancellationScope(
            () -> {
              IntakeAgentRunFinalizationReadResult result =
                  readTargetFinalization(
                      command,
                      IntakeAgentRunFinalizationReadRequest.Mode.CANCELLATION_RECONCILIATION);
              reconciliation[0] =
                  switch (result.resolution()) {
                    case COMMITTED -> {
                      commitTargetFinalization(result);
                      yield CancellationReconciliation.committed(
                          result.receipt().operation().operationKey(),
                          result.receipt().committedEvent());
                    }
                    case ABSENT_TERMINAL -> {
                      targetAgentRunChild = targetAgentRunChild.terminalNoCommit();
                      authoritativeCommittedTargetFinalization = null;
                      yield CancellationReconciliation.absent(command.operationKey());
                    }
                    case TERMINAL_NO_COMMIT ->
                        settleTargetFinalization(command, result)
                            ? CancellationReconciliation.absent(command.operationKey())
                            : CancellationReconciliation.unresolved();
                    case PENDING -> CancellationReconciliation.unresolved();
                  };
            });
    try {
      reconciliationScope.run();
      return reconciliation[0] == null
          ? CancellationReconciliation.unresolved()
          : reconciliation[0];
    } catch (IntakeActivityDeadlineExceeded failure) {
      protocolErrorCode = "INTAKE_ACTIVITY_DEADLINE_EXPIRED";
    } catch (ActivityFailure failure) {
      protocolErrorCode = activityFailureCode(failure);
    } catch (RuntimeException failure) {
      protocolErrorCode = "INTAKE_ACTIVITY_RECONCILIATION_UNRESOLVED";
    }
    return CancellationReconciliation.unresolved();
  }

  private CancellationReconciliation reconcileStageReceipt(
      IntakeWorkflowCommand command, IntakeActivityExecutionState canceledStage) {
    IntakeCommandExecutionContext context = command.executionContext();
    String operationKey = canceledStage.stageOperationKey();
    ActivityInvocation invocation =
        new ActivityInvocation(
            "intake-activity-invocation.v1", ActivityInvocationMode.RECONCILE_ONLY, 0);
    setActivityStage(command, context, canceledStage.stage(), operationKey, invocation);
    if (canceledStage.terminalFailure() != null) {
      activityExecution =
          activityExecution.withTerminalFailure(canceledStage.terminalFailure().failureType());
    }
    ActivityEnvelope envelope = activityEnvelope(command, context, invocation);
    return switch (canceledStage.stage()) {
      case SNAPSHOT_PUBLICATION -> {
        long domainRevision = processRevision;
        SnapshotPublicationRequest request =
            new SnapshotPublicationRequest(
                "intake-snapshot-publication-request.v1",
                envelope,
                context.threadId(),
                context.agentSessionId(),
                domainRevision,
                operationKey,
                command.requestHash());
        SnapshotPublicationReceipt receipt = activities(context).publishSnapshot(request);
        if (receipt == null) {
          yield CancellationReconciliation.absent(operationKey);
        }
        requireOperation(receipt.operation(), operationKey, command.requestHash());
        if (receipt.domainRevision() != domainRevision) {
          throw new IllegalArgumentException(
              "snapshot reconciliation receipt revision does not match the request");
        }
        yield CancellationReconciliation.committed(operationKey, null);
      }
      case GRAPH_EXECUTION -> {
        GraphExecutionRequest request =
            new GraphExecutionRequest(
                "intake-graph-execution-request.v1",
                envelope,
                context.threadId(),
                context.agentSessionId(),
                operationKey,
                command.requestHash());
        GraphExecutionReceipt receipt = activities(context).executeGraph(request);
        if (receipt == null) {
          yield CancellationReconciliation.absent(operationKey);
        }
        requireOperation(receipt.operation(), operationKey, command.requestHash());
        if (!context.threadId().equals(receipt.graphExecutionRef().threadId())
            || !command.commandId().equals(receipt.graphExecutionRef().graphCommandId())
            || !start.graphVersion().equals(receipt.graphExecutionRef().graphVersion())) {
          throw new IllegalArgumentException(
              "Graph reconciliation receipt does not match the command");
        }
        yield CancellationReconciliation.committed(operationKey, null);
      }
      case TURN_FINALIZATION -> {
        GraphExecutionReceipt graph = canceledStage.completedGraphExecution();
        if (graph == null) {
          throw new IllegalStateException(
              "turn finalization reconciliation requires the completed Graph receipt");
        }
        TurnFinalizationRequest request =
            new TurnFinalizationRequest(
                "intake-turn-finalization-request.v1",
                envelope,
                context.threadId(),
                context.agentSessionId(),
                graph,
                operationKey,
                command.requestHash());
        TurnFinalizationReceipt receipt = activities(context).finalizeTurn(request);
        if (receipt == null) {
          yield CancellationReconciliation.absent(operationKey);
        }
        receipt.requireMatches(request);
        requireOperation(receipt.operation(), operationKey, command.requestHash());
        yield CancellationReconciliation.committed(operationKey, receipt.committedEvent());
      }
      case INITIATOR_ACCEPTANCE,
          INITIATOR_REJECTION,
          CANCELLATION,
          RESPONDENT_CONFIRMATION ->
          reconcileBranchStage(command, context, canceledStage.stage(), envelope, operationKey);
    };
  }

  private CancellationReconciliation reconcileBranchStage(
      IntakeWorkflowCommand command,
      IntakeCommandExecutionContext context,
      IntakeActivityStage stage,
      ActivityEnvelope envelope,
      String operationKey) {
    BranchOperation operation = context.branchOperation();
    IntakeActivityStage expectedStage =
        switch (operation) {
          case INITIATOR_ACCEPT -> IntakeActivityStage.INITIATOR_ACCEPTANCE;
          case INITIATOR_REJECT -> IntakeActivityStage.INITIATOR_REJECTION;
          case CANCEL -> IntakeActivityStage.CANCELLATION;
          case RESPONDENT_CONFIRM -> IntakeActivityStage.RESPONDENT_CONFIRMATION;
        };
    if (stage != expectedStage) {
      throw new IllegalArgumentException(
          "branch reconciliation stage does not match its operation");
    }
    BranchCommitRequest request =
        new BranchCommitRequest(
            "intake-branch-commit-request.v1",
            envelope,
            operation,
            operationKey,
            command.requestHash());
    BranchCommitReceipt receipt =
        switch (operation) {
          case INITIATOR_ACCEPT -> activities(context).acceptInitiator(request);
          case INITIATOR_REJECT -> activities(context).rejectInitiator(request);
          case CANCEL -> activities(context).cancelIntake(request);
          case RESPONDENT_CONFIRM -> activities(context).confirmRespondent(request);
        };
    if (receipt == null) {
      return CancellationReconciliation.absent(operationKey);
    }
    receipt.requireMatches(request);
    requireOperation(receipt.operation(), operationKey, command.requestHash());
    return CancellationReconciliation.committed(operationKey, receipt.committedEvent());
  }

  private IntakeWorkflowCommand pendingWorkflowCommand() {
    IntakePendingCommand pending = pendingCommand;
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        pending.commandId(),
        start.tenantSurrogate(),
        start.caseId(),
        start.roomEpoch(),
        start.fencingToken(),
        pending.sequence(),
        pending.commandType(),
        pending.party(),
        pending.actorScopeHash(),
        pending.payloadRef(),
        pending.payloadHash(),
        pending.operationKey(),
        pending.requestHash(),
        pending.executionContext());
  }

  private boolean hasPendingActivityCommand(String commandId) {
    return commandId != null
        && pendingCommand != null
        && pendingCommand.commandId().equals(commandId)
        && pendingCommand.executionContext() != null;
  }

  private boolean clearPendingActivity(String commandId) {
    if (!hasPendingActivityCommand(commandId)) {
      return true;
    }
    boolean targetCommand = pendingCommand.executionContext().isTargetAgentRun();
    if (targetCommand
        && Workflow.getVersion(
                AGENT_RUN_CANCELLATION_CLEAR_GUARD_CHANGE_ID,
                Workflow.DEFAULT_VERSION,
                1)
            == 1) {
      if (targetAgentRunChild == null
          || !targetAgentRunChild.commandId().equals(commandId)) {
        protocolErrorCode = "TARGET_AGENT_RUN_TERMINAL_RELEASE_IDENTITY_MISMATCH";
        return false;
      }
      if (targetAgentRunChild.status()
          == IntakeAgentRunChildState.Status.RECEIPT_COMMITTED) {
        if (!hasExactCommittedTargetFinalization(commandId)) {
          protocolErrorCode = "TARGET_AGENT_RUN_COMMITTED_AUTHORITY_MISMATCH";
        }
        return false;
      }
      if (targetAgentRunChild.status()
          == IntakeAgentRunChildState.Status.TERMINAL_NO_COMMIT) {
        IntakeWorkflowCommand terminalCommand = pendingWorkflowCommand();
        if (!releaseTargetAgentRunWithoutCommit(commandId)) {
          return false;
        }
        recordTerminalTargetAgentRunDecision(
            terminalCommand, "TARGET_AGENT_RUN_FINALIZATION_ABSENT");
        return true;
      }
      if (targetAgentRunChild.unresolved()) {
        protocolErrorCode = "TARGET_AGENT_RUN_FINALIZATION_UNRESOLVED";
        return false;
      }
    }
    pendingCommand = null;
    activityExecution = null;
    if (targetAgentRunChild != null
        && targetAgentRunChild.commandId().equals(commandId)
        && targetAgentRunChild.unresolved()) {
      targetAgentRunChild = targetAgentRunChild.terminalNoCommit();
    }
    authoritativeCommittedTargetFinalization = null;
    targetAgentRunPreCommandState = null;
    sharedActivityRetriesRemaining = 0;
    activityExecutionAuthorized = false;
    return true;
  }

  private boolean hasExactCommittedTargetFinalization(String commandId) {
    if (pendingCommand == null
        || !pendingCommand.commandId().equals(commandId)
        || pendingCommand.executionContext() == null
        || !pendingCommand.executionContext().isTargetAgentRun()
        || targetAgentRunChild == null
        || !targetAgentRunChild.commandId().equals(commandId)
        || targetAgentRunChild.status()
            != IntakeAgentRunChildState.Status.RECEIPT_COMMITTED
        || authoritativeCommittedTargetFinalization == null) {
      return false;
    }
    try {
      IntakeWorkflowCommand command = pendingWorkflowCommand();
      IntakeAgentRunFinalizationReadRequest request =
          targetFinalizationReadRequest(
              command, IntakeAgentRunFinalizationReadRequest.Mode.AFTER_CHILD_COMPLETION);
      authoritativeCommittedTargetFinalization.requireMatches(request, winningAttemptEnabled);
      return targetAgentRunChild.equals(
          targetAgentRunChild.committed(
              authoritativeCommittedTargetFinalization, winningAttemptEnabled));
    } catch (IllegalArgumentException | IllegalStateException mismatch) {
      return false;
    }
  }

  private IntakeAgentRunFinalizationReadRequest targetFinalizationReadRequest(
      IntakeWorkflowCommand command, IntakeAgentRunFinalizationReadRequest.Mode mode) {
    return winningAttemptEnabled
        ? IntakeAgentRunFinalizationReadRequest.winningAttempt(mode, command, targetAgentRunChild)
        : IntakeAgentRunFinalizationReadRequest.exact(mode, command, targetAgentRunChild);
  }

  private void requireTerminalNoCommitRecovery(
      IntakeTerminalNoCommitRecoveryRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    TargetIntakeCommandTerminalNoCommit authority = request.authority();
    boolean acknowledgedRecovery =
        IntakeTerminalNoCommitRecoveryRequest.V3_SCHEMA_VERSION.equals(
            request.schemaVersion());
    if ((!IntakeTerminalNoCommitRecoveryRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
            && !acknowledgedRecovery)
        || authority == null
        || !TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(
            authority.schemaVersion())) {
      throw new IllegalArgumentException(
          "terminal-no-commit recovery requires a v2 authority and v2/v3 request");
    }
    if (completedTerminalNoCommitRecoveryRequest != null
        || completedTerminalNoCommitRecoveryResult != null) {
      if (request.equals(completedTerminalNoCommitRecoveryRequest)
          && completedTerminalNoCommitRecoveryResult != null) {
        return;
      }
      boolean upgradesUnacknowledgedV2 =
          acknowledgedRecovery
              && completedTerminalNoCommitRecoveryRequest != null
              && completedTerminalNoCommitRecoveryResult != null
              && IntakeTerminalNoCommitRecoveryRequest.SCHEMA_VERSION.equals(
                  completedTerminalNoCommitRecoveryRequest.schemaVersion())
              && IntakeTerminalNoCommitRecoveryResult.SCHEMA_VERSION.equals(
                  completedTerminalNoCommitRecoveryResult.schemaVersion())
              && authority.equals(completedTerminalNoCommitRecoveryRequest.authority());
      if (!upgradesUnacknowledgedV2) {
        throw new IllegalArgumentException("terminal-no-commit recovery replay conflicts");
      }
    }
    if (start == null
        || !Workflow.getInfo().getWorkflowId().equals(request.workflowId())
        || !Workflow.getInfo().getRunId().equals(request.workflowRunId())) {
      throw new IllegalArgumentException("terminal-no-commit recovery workflow is stale");
    }
    if (!Workflow.getInfo().getFirstExecutionRunId().equals(authority.roomWorkflowRunId())
        || !start.tenantSurrogate().equals(authority.tenantSurrogate())
        || !start.caseId().equals(authority.caseId())
        || start.roomEpoch() != authority.roomEpoch()
        || start.fencingToken() != authority.fencingToken()
        || !start.workflowBuildId().equals(authority.roomWorkflowBuildId())
        || nextCommandSequence != authority.caseCommandSequence() + 1
        || nextEventSequence - 1 != authority.lastCaseEventSequence()) {
      throw new IllegalArgumentException("terminal-no-commit recovery room authority is stale");
    }
    if (pendingCommand != null
        || targetAgentRunChild != null
        || authoritativeCommittedTargetFinalization != null
        || targetAgentRunPreCommandState != null
        || activeOrchestration != null
        || activeCancellationScope != null
        || activeOrchestrationCommandId != null
        || activityExecution != null) {
      throw new IllegalStateException("terminal-no-commit recovery requires released Room state");
    }
    CommandObservation observation = commandObservations.get(authority.commandId());
    if (observation == null || observation.decision() == null) {
      throw new IllegalStateException("terminal-no-commit rejected command observation is absent");
    }
    IntakeWorkflowCommand command = observation.command();
    IntakeCommandDecision decision = observation.decision();
    IntakeTargetAgentRunContext target =
        command.executionContext() == null ? null : command.executionContext().targetAgentRun();
    var targetRequest = target == null ? null : target.request();
    var graph = targetRequest == null ? null : targetRequest.command();
    var message = graph == null ? null : graph.eventRef();
    String retainedTerminalReason = "TARGET_AGENT_RUN_" + authority.agentRunOutcome().name();
    if (!"REJECTED".equals(decision.status())
        || decision.reasonCode() == null
        || (!decision.reasonCode().equals(authority.errorCode())
            && !decision.reasonCode().equals(retainedTerminalReason))
        || command.sequence() != authority.caseCommandSequence()
        || !command.requestHash().equals(authority.commandRequestHash())
        || !command.payloadRef().equals(authority.messageRef())
        || !command.payloadHash().equals(authority.messageHash())
        || target == null
        || targetRequest == null
        || graph == null
        || !target.activationId().equals(authority.activationId())
        || !target.activationManifestHash().equals(authority.activationManifestHash())
        || !target.caseBuildId().equals(authority.caseBuildId())
        || !target.controlBuildId().equals(authority.controlBuildId())
        || !target.agentBuildId().equals(authority.agentBuildId())
        || !target.graphBindingHash().equals(authority.graphBindingHash())
        || !target.graphCodeBuildId().equals(authority.graphCodeBuildId())
        || !target.commandHash().equals(authority.commandHash())
        || !target.commandEnvelopeHash().equals(authority.commandEnvelopeHash())
        || !targetRequest.logicalInputHash().equals(authority.logicalInputHash())
        || !graph.requestHash().equals(authority.agentRunExecutionRequestHash())
        || !targetRequest.logicalRunId().equals(authority.logicalRunId())
        || !targetRequest.attemptId().equals(authority.rootAttemptId())
        || target.expectedProcessRevision() != authority.expectedProcessRevision()
        || target.expectedRoomRevision() != authority.expectedRoomRevision()
        || message == null
        || !message.artifactId().equals(authority.messageId())) {
      throw new IllegalArgumentException("terminal-no-commit rejected authority conflicts");
    }
    boolean sourceCoordinates =
        processRevision == authority.expectedProcessRevision()
            && roomRevision == authority.expectedRoomRevision();
    boolean targetCoordinates =
        processRevision == authority.newProcessRevision()
            && roomRevision == authority.newRoomRevision();
    if (!sourceCoordinates || targetCoordinates) {
      if (!targetCoordinates) {
        throw new IllegalStateException("terminal-no-commit Room revisions are stale");
      }
    }
    boolean formalEventApplied =
        eventObservations.values().stream()
            .anyMatch(
                observed ->
                    observed.applied()
                        && observed.event().commandId().equals(authority.commandId()));
    if (formalEventApplied) {
      throw new IllegalStateException("terminal-no-commit command already has a formal event");
    }
  }

  private void requireTargetFinalizationRecovery(
      IntakeAgentRunFinalizationRecoveryRequest request, boolean handlerRevalidation) {
    Objects.requireNonNull(request, "request must not be null");
    if (completedTargetFinalizationRecoveryRequest != null) {
      if (!completedTargetFinalizationRecoveryRequest.equals(request)
          || completedTargetFinalizationRecoveryResult == null) {
        throw new IllegalArgumentException("target finalization recovery replay conflicts");
      }
      return;
    }
    if (activeTargetFinalizationRecovery != null
        && (!handlerRevalidation || !activeTargetFinalizationRecovery.equals(request))) {
      throw new IllegalStateException("target finalization recovery is already running");
    }
    if (start == null) {
      throw new IllegalStateException("target finalization recovery requires an initialized room");
    }
    if (roomPhase == IntakeRoomPhase.COMPLETED || terminalReason != null) {
      throw new IllegalStateException("target finalization recovery requires a nonterminal room");
    }
    if (!winningAttemptEnabled) {
      throw new IllegalStateException("target finalization recovery requires v2 winning authority");
    }
    if (pendingCommand == null
        || pendingCommand.executionContext() == null
        || !pendingCommand.executionContext().isTargetAgentRun()
        || targetAgentRunChild == null
        || targetAgentRunPreCommandState == null
        || !targetAgentRunPreCommandState.commandId().equals(pendingCommand.commandId())) {
      throw new IllegalStateException("target finalization recovery state is unavailable");
    }
    if (activeOrchestration != null
        || activeCancellationScope != null
        || activeOrchestrationCommandId != null
        || activeCancellationRequested
        || activityExecution != null
        || (deferredCancellation != null && !handlerRevalidation)) {
      throw new IllegalStateException("target finalization recovery requires idle orchestration");
    }
    request.requireMatches(
        Workflow.getInfo().getWorkflowId(),
        Workflow.getInfo().getRunId(),
        start,
        pendingWorkflowCommand(),
        targetAgentRunChild,
        authoritativeCommittedTargetFinalization,
        processRevision,
        roomRevision,
        targetAgentRunPreCommandState.lastCaseEventSequence());
  }

  private static void requireExpectedRecoveryFinalization(
      IntakeAgentRunFinalizationRecoveryRequest request,
      IntakeAgentRunFinalizationReadResult finalization) {
    IntakeAgentRunFinalizationReadResult.Resolution expectedResolution =
        request.isTerminalNoCommitRecovery()
            ? IntakeAgentRunFinalizationReadResult.Resolution.TERMINAL_NO_COMMIT
            : IntakeAgentRunFinalizationReadResult.Resolution.COMMITTED;
    if (finalization == null
        || finalization.resolution() != expectedResolution
        || !request.expectedFinalization().equals(finalization)) {
      throw new IllegalArgumentException(
          "target finalization recovery read does not match expected authority");
    }
  }

  private static void rethrowTargetFinalizationRecoveryCancellation(ActivityFailure failure) {
    Throwable current = failure.getCause();
    while (current != null) {
      if (current instanceof CanceledFailure canceled) {
        throw canceled;
      }
      current = current.getCause();
    }
  }

  private static ApplicationFailure targetFinalizationRecoveryFailure(RuntimeException failure) {
    return ApplicationFailure.newNonRetryableFailureWithCause(
        "target finalization recovery failed after acceptance",
        TARGET_FINALIZATION_RECOVERY_FAILURE,
        failure);
  }

  private static boolean cancellationReconciliationEnabled() {
    return Workflow.getVersion(
            CANCELLATION_RECONCILIATION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        == 1;
  }

  private boolean consumeQueuedCommittedEvent(String commandId) {
    if (commandId == null
        || pendingCommand == null
        || !pendingCommand.commandId().equals(commandId)) {
      return false;
    }
    String expectedOperationKey =
        activityExecution != null && activityExecution.commandId().equals(commandId)
            ? activityExecution.stageOperationKey()
            : targetAgentRunChild != null
                    && targetAgentRunChild.commandId().equals(commandId)
                    && targetAgentRunChild.finalizationOperationKey() != null
                ? targetAgentRunChild.finalizationOperationKey()
                : null;
    if (expectedOperationKey == null) {
      return false;
    }
    Iterator<InboxItem> iterator = inbox.iterator();
    while (iterator.hasNext()) {
      InboxItem item = iterator.next();
      if (!(item instanceof EventInput eventInput)) {
        continue;
      }
      IntakeDomainEventRef event = eventInput.event();
      if ((!event.commandId().equals(commandId)
              && !matchesAuthoritativeWinningTargetEvent(event))
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

  private boolean hasUnresolvedQueuedActivityEvent(String commandId) {
    if (!hasPendingActivityCommand(commandId)) {
      return false;
    }
    String operationKey =
        activityExecution != null
            ? activityExecution.stageOperationKey()
            : targetAgentRunChild == null ? null : targetAgentRunChild.finalizationOperationKey();
    if (operationKey == null) {
      return false;
    }
    return eventObservations.values().stream()
        .anyMatch(
            observation ->
                !observation.applied()
                    && reservesFormalEventSequence(observation)
                    && (observation.event().commandId().equals(commandId)
                        || matchesAuthoritativeWinningTargetEvent(observation.event()))
                    && observation.event().operationKey().equals(operationKey));
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

  /**
   * The target lane receives a command that was already admitted with an immutable domain
   * snapshot. That signed command is the initialization authority, so invoking the legacy
   * snapshot Activity here would both duplicate the publication and reintroduce its legacy
   * pinned-version contract.
   */
  private void ensureTargetThreadInitialization(
      IntakeWorkflowCommand command, IntakeCommandExecutionContext context) {
    IntakeThreadInitialization existing = threadInitializations.get(command.party());
    if (existing != null) {
      requireThreadBinding(existing, command, context);
      return;
    }

    IntakeTargetAgentRunContext target = context.targetAgentRun();
    target.requireMatches(start, command, processRevision, roomRevision);
    var snapshot = target.request().command().domainSnapshotRef();
    String operationKey =
        IntakeOperationKeys.snapshotPublish(
            command.caseId(), command.roomEpoch(), command.actorScopeHash(), processRevision);
    SnapshotPublicationReceipt receipt =
        new SnapshotPublicationReceipt(
            "intake-snapshot-publication-receipt.v1",
            new OperationReceipt(
                "intake-operation-receipt.v1",
                operationKey,
                command.requestHash(),
                snapshot.sha256(),
                processRevision,
                roomRevision),
            new IntakeActivityProtocol.ImmutablePayloadRef(
                "immutable-payload-ref.v1",
                snapshot.artifactId(),
                "INTAKE_SNAPSHOT",
                snapshot.schemaVersion(),
                snapshot.uri(),
                target.commandEnvelopeHash(),
                snapshot.sha256(),
                snapshot.sizeBytes()),
            processRevision);
    threadInitializations.put(
        command.party(),
        new IntakeThreadInitialization(
            "intake-thread-initialization.v1",
            command.party(),
            command.actorScopeHash(),
            context.threadId(),
            context.agentSessionId(),
            processRevision,
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
    PinnedVersions versions;
    if (context.hasRegisteredTargetBranchPins()) {
      versions = Objects.requireNonNull(
          context.branchPinnedVersions(), "target branch private-thread pins are absent");
      if (!versions.workflowBuildId().equals(start.workflowBuildId())
          || !versions.graphVersion().equals(start.graphVersion())
          || !versions.checkpointSchemaVersion().equals(start.checkpointSchemaVersion())) {
        throw new IllegalArgumentException(
            "target branch private-thread pins differ from the active room authority");
      }
    } else {
      // v1-v4 histories retain their original start-derived Activity command shape.
      versions =
          new PinnedVersions(
              context.isTargetBranch()
                  ? "intake-pinned-versions.v2"
                  : "intake-pinned-versions.v1",
              start.workflowBuildId(),
              start.graphVersion(),
              start.checkpointSchemaVersion(),
              start.promptVersion(),
              start.modelProfileId(),
              context.isTargetBranch()
                  ? TARGET_BRANCH_OUTPUT_SCHEMA_VERSION
                  : start.outputSchemaVersion(),
              start.policyVersion(),
              start.guardrailVersion(),
              start.toolPolicyVersion());
    }
    RetryBudget invocationBudget =
        new RetryBudget(
            "intake-retry-budget.v1",
            context.retryBudget().providerAttemptsRemaining(),
            invocation.mode() == ActivityInvocationMode.RECONCILE_ONLY ? 0 : 1,
            context.retryBudget().repairsRemaining());
    long activityProcessRevision = processRevision;
    long activityRoomRevision = roomRevision;
    if (context.hasPinnedTargetBranchAuthority()) {
      long expectedProcessRevision = context.expectedProcessRevision();
      long expectedRoomRevision = context.expectedRoomRevision();
      if (expectedProcessRevision < processRevision || expectedRoomRevision < roomRevision) {
        throw new IllegalArgumentException(
            "target branch authority revisions are behind the Intake workflow");
      }
      activityProcessRevision = expectedProcessRevision;
      activityRoomRevision = expectedRoomRevision;
    }
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
        activityProcessRevision,
        activityRoomRevision,
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
            context.retryBudget(),
            Duration.ofMillis(remainingMillis),
            context.isTargetBranch() ? CASE_CONTROL : AGENT_EXECUTION));
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

  private boolean matchesTargetAgentRunAuthority(IntakeWorkflowCommand command) {
    IntakeCommandExecutionContext context = command.executionContext();
    if (!context.isTargetAgentRun()) {
      return true;
    }
    try {
      context.targetAgentRun().requireMatches(start, command, processRevision, roomRevision);
      if (targetAgentRunChild != null) {
        targetAgentRunChild.requireMatches(command, context.targetAgentRun());
      }
      return true;
    } catch (IllegalArgumentException mismatch) {
      return false;
    }
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
    targetAgentRunChild = carry.targetAgentRunChild();
    completedTerminalNoCommitRecoveryResult = carry.completedTerminalNoCommitRecovery();
    completedTerminalNoCommitRecoveryRequest =
        completedTerminalNoCommitRecoveryResult == null
            ? null
            : completedTerminalNoCommitRecoveryResult.request();
    completedTargetFinalizationRecoveryRequest =
        carry.completedTargetFinalizationRecoveryRequest();
    completedTargetFinalizationRecoveryResult =
        carry.completedTargetFinalizationRecoveryResult();
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
    carry.observedTargetSourceEvents()
        .forEach(
            observed ->
                targetSourceEventObservations.put(
                    observed.event().eventId(), observed.event()));
    futureTargetSourceCursorBufferingEnabled =
        targetSourceEventObservations.values().stream()
            .anyMatch(event -> event.eventSequence() >= nextEventSequence);
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
        && (targetAgentRunChild == null || !targetAgentRunChild.unresolved())
        && activeOrchestration == null
        && deferredCancellation == null
        && deferredTargetCommands.isEmpty()
        && !hasBufferedFutureTargetSourceCursor()
        && !continueAsNewBlockedByTargetState
        && Workflow.isEveryHandlerFinished();
  }

  private boolean hasBufferedFutureTargetSourceCursor() {
    return targetSourceEventObservations.values().stream()
        .anyMatch(event -> event.eventSequence() >= nextEventSequence);
  }

  private boolean continueAsNew() {
    Workflow.await(Workflow::isEveryHandlerFinished);
    if (targetAgentRunChild != null
        || authoritativeCommittedTargetFinalization != null
        || targetAgentRunPreCommandState != null) {
      // A legacy history may already contain the old resolved-child carry command. DEFAULT
      // reproduces that command; new histories stop before serializing incomplete authority.
      if (Workflow.getVersion(
              AGENT_RUN_RESOLVED_CHILD_CARRY_GUARD_CHANGE_ID,
              Workflow.DEFAULT_VERSION,
              1)
          == 1) {
        continueAsNewBlockedByTargetState = true;
        protocolErrorCode = "TARGET_AGENT_RUN_CARRY_STATE_UNSAFE";
        return false;
      }
    }
    boolean carryPendingFinalizationRecovery = hasCompletedPendingFinalizationRecoveryCache();
    boolean carryAcknowledgedTerminalNoCommit =
        completedTerminalNoCommitRecoveryResult != null
            && IntakeTerminalNoCommitRecoveryResult.V2_SCHEMA_VERSION.equals(
                completedTerminalNoCommitRecoveryResult.schemaVersion());
    IntakeRoomCarryState carry =
        new IntakeRoomCarryState(
            carryAcknowledgedTerminalNoCommit
                ? "intake-room-carry-state.v6"
                : carryPendingFinalizationRecovery
                ? "intake-room-carry-state.v5"
                : completedTerminalNoCommitRecoveryResult != null
                ? "intake-room-carry-state.v4"
                : targetSourceEventObservations.isEmpty()
                ? targetAgentRunChild == null
                    ? "intake-room-carry-state.v1"
                    : "intake-room-carry-state.v2"
                : "intake-room-carry-state.v3",
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
            new ArrayList<>(threadInitializations.values()),
            targetAgentRunChild,
            targetSourceEventObservations.values().stream()
                .map(
                    event ->
                        new IntakeRoomCarryState.ObservedTargetSourceEvent(
                            "intake-observed-target-source-event.v1", event))
                .toList(),
            completedTerminalNoCommitRecoveryResult,
            carryPendingFinalizationRecovery
                ? completedTargetFinalizationRecoveryRequest
                : null,
            carryPendingFinalizationRecovery
                ? completedTargetFinalizationRecoveryResult
                : null);
    ContinueAsNewOptions options =
        ContinueAsNewOptions.newBuilder().setMemo(Map.of(CARRY_STATE_MEMO_KEY, carry)).build();
    Workflow.continueAsNew(options, start.withCarryState(carry));
    return true;
  }

  private boolean hasCompletedPendingFinalizationRecoveryCache() {
    if (completedTargetFinalizationRecoveryRequest == null
        && completedTargetFinalizationRecoveryResult == null) {
      return false;
    }
    if (completedTargetFinalizationRecoveryRequest == null
        || completedTargetFinalizationRecoveryResult == null
        || !completedTargetFinalizationRecoveryRequest.equals(
            completedTargetFinalizationRecoveryResult.request())) {
      throw new IllegalStateException("target finalization recovery cache is incomplete");
    }
    boolean v2Request =
        IntakeAgentRunFinalizationRecoveryRequest.V2_SCHEMA_VERSION.equals(
            completedTargetFinalizationRecoveryRequest.schemaVersion());
    boolean v2Result =
        IntakeAgentRunFinalizationRecoveryResult.V2_SCHEMA_VERSION.equals(
            completedTargetFinalizationRecoveryResult.schemaVersion());
    if (v2Request != v2Result) {
      throw new IllegalStateException("target finalization recovery cache schema conflicts");
    }
    return v2Request;
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

  private boolean matchesEnvelope(TargetIntakeSourceEventRef event) {
    return start.tenantSurrogate().equals(event.tenantSurrogate())
        && start.caseId().equals(event.caseId())
        && event.roomType()
            == com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE
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

  private sealed interface InboxItem permits CommandInput, EventInput, TargetSourceEventInput {}

  private record CommandInput(IntakeWorkflowCommand command) implements InboxItem {}

  private record EventInput(IntakeDomainEventRef event) implements InboxItem {}

  private record TargetSourceEventInput(TargetIntakeSourceEventRef event) implements InboxItem {}

  private record CommandObservation(
      IntakeWorkflowCommand command, IntakeCommandDecision decision) {}

  private record EventObservation(IntakeDomainEventRef event, boolean applied) {}

  private record TargetAgentRunPreCommandState(
      String commandId,
      IntakeRoomPhase roomPhase,
      IntakeParty activeParty,
      IntakeParty readinessParty,
      long lastCaseEventSequence) {

    private TargetAgentRunPreCommandState {
      Objects.requireNonNull(commandId, "commandId must not be null");
      Objects.requireNonNull(roomPhase, "roomPhase must not be null");
      Objects.requireNonNull(activeParty, "activeParty must not be null");
      if (lastCaseEventSequence < 0) {
        throw new IllegalArgumentException("lastCaseEventSequence must not be negative");
      }
    }
  }

  private enum TargetFinalizationReconciliation {
    SETTLED,
    PENDING,
    UNRESOLVED
  }

  private enum CancellationReconciliationStatus {
    COMMITTED,
    ABSENT,
    UNRESOLVED
  }

  private record CancellationReconciliation(
      CancellationReconciliationStatus status,
      String operationKey,
      IntakeDomainEventRef committedEvent) {

    private static CancellationReconciliation committed(
        String operationKey, IntakeDomainEventRef committedEvent) {
      return new CancellationReconciliation(
          CancellationReconciliationStatus.COMMITTED, operationKey, committedEvent);
    }

    private static CancellationReconciliation absent(String operationKey) {
      return new CancellationReconciliation(
          CancellationReconciliationStatus.ABSENT, operationKey, null);
    }

    private static CancellationReconciliation unresolved() {
      return new CancellationReconciliation(
          CancellationReconciliationStatus.UNRESOLVED, null, null);
    }
  }

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

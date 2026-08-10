package com.example.dispute.workflow.temporal.caseprocess;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE;
import static io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionResult;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.activity.domain.ProcessProjectionActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewOutcomeStartBindingPort.Binding;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ClosedRoomTuple;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ProvisionedRoomEpochHighWater;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.RecoveryErrorOrigin;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.UnreconciledChildExecution;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceStream;
import com.example.dispute.workflow.temporal.room.common.RoomControlStart;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.CancelExternalWorkflowException;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalExternalWorkflowException;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class CaseProcessWorkflowImpl implements CaseProcessWorkflow {

  private static final String CARRY_STATE_MEMO_KEY = "case_process_carry_state_v1";
  private static final String COMMAND_DEADLINE_CHANGE_ID = "case-process-command-deadline-v1";
  private static final String COMMAND_LEDGER_STATE_CHANGE_ID =
      "case-process-command-ledger-state-v1";
  private static final String FUTURE_ROOM_EVENT_RETENTION_CHANGE_ID =
      "case-process-future-room-event-retention-v1";
  private static final String ROOM_EPOCH_PROVISION_CHANGE_ID =
      "case-process-room-epoch-provision-v1";
  private static final String PROVISIONING_SEQUENCE_HIGH_WATER_CHANGE_ID =
      "case-process-provisioning-sequence-high-water-v1";
  private static final String AUTHORITY_CHECKPOINT_CHANGE_ID =
      "case-process-authority-checkpoint-v1";
  private static final String TYPED_INTAKE_CHILD_CHANGE_ID = "typed-intake-room-child-v1";
  private static final String AUTHORITY_BRIDGE_CHANGE_ID = "typed-intake-bridge-authority-v1";
  private static final String TARGET_TYPED_ROOM_CHANGE_ID =
      "target-e2e-typed-room-child-v1";
  private static final String CHILD_COMPENSATION_INVARIANT_CHANGE_ID =
      "case-process-child-compensation-invariant-v1";
  private static final String TARGET_INTAKE_PROJECTION_COMPLETION_CHANGE_ID =
      "case-process-target-intake-projection-completion-v1";
  private static final String TARGET_INTAKE_PROJECTION_READY_HIGH_WATER_CHANGE_ID =
      "case-process-target-intake-projection-ready-high-water-v1";
  private static final String TARGET_INTAKE_GLOBAL_PROJECTION_CURSOR_CHANGE_ID =
      "case-process-target-intake-global-projection-cursor-v1";
  private static final String INTAKE_PROJECTION_RECOVERY_FAILURE =
      "INTAKE_PROJECTION_COMPLETION_RECOVERY_FAILED";
  private static final String AUTHORITY_CHECKPOINT_MEMO_KEY =
      "case_process_authority_checkpoint_v1";
  private static final String SELECTION_V1 = "room-epoch-selection.v1";
  private static final String SELECTION_V2 = "room-epoch-selection.v2";
  private static final String INTAKE_ROOM_WORKFLOW_TYPE = "IntakeRoomWorkflow";
  private static final String INTAKE_ROOM_WORKFLOW_BUILD_ID = "intake-room.synthetic.v1";
  private static final String INTAKE_GRAPH_KEY = "intake.v2";
  private static final int INBOX_CAPACITY = 128;
  private static final int LOAD_BATCH_SIZE = 64;
  private static final int MAX_GAP_RECOVERY_ATTEMPTS = 3;
  private static final long HISTORY_EVENT_LIMIT = 2000;
  private static final Duration RUN_MAX_AGE = Duration.ofHours(24);
  private static final Duration GAP_RETRY_DELAY = Duration.ofSeconds(1);
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern TRACEPARENT =
      Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

  private final CaseProcessLedgerActivities ledgerActivities =
      Workflow.newActivityStub(
          CaseProcessLedgerActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumInterval(Duration.ofSeconds(5))
                      .setMaximumAttempts(3)
                      .build())
              .build());
  private final CaseCommandLifecycleActivities commandLifecycleActivities =
      Workflow.newActivityStub(
          CaseCommandLifecycleActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumInterval(Duration.ofMinutes(1))
                      .setMaximumAttempts(0)
                      .build())
              .build());
  private final IntakeChildBridgeActivities intakeChildBridgeActivities =
      Workflow.newActivityStub(
          IntakeChildBridgeActivities.class,
          intakeChildBridgeActivityOptions());
  private final IntakeChildBridgeActivitiesV2 intakeChildBridgeActivitiesV2 =
      Workflow.newActivityStub(
          IntakeChildBridgeActivitiesV2.class,
          intakeChildBridgeActivityOptions());
  private final ProcessProjectionActivities processProjectionActivities =
      Workflow.newActivityStub(
          ProcessProjectionActivities.class,
          targetIntakeProjectionCompletionActivityOptions());
  private final WorkflowQueue<PendingCommand> commandInbox = Workflow.newQueue(INBOX_CAPACITY);
  private final WorkflowQueue<CaseDomainEventRef> eventInbox = Workflow.newQueue(INBOX_CAPACITY);
  private final WorkflowQueue<PendingProvisioning> provisioningInbox =
      Workflow.newQueue(INBOX_CAPACITY);
  private final NavigableMap<Long, PendingCommand> orderedCommands = new TreeMap<>();
  private final ArrayDeque<PendingCommand> replayChecks = new ArrayDeque<>();
  private final ArrayDeque<TargetIntakeCommandTerminalNoCommit> terminalNoCommitInbox =
      new ArrayDeque<>();
  private final NavigableMap<Long, CaseDomainEventRef> bufferedEvents = new TreeMap<>();
  private final LinkedHashMap<String, ProcessedCommandIdentity> recentCommands =
      new LinkedHashMap<>();
  private final LinkedHashSet<ClosedRoomTuple> closedRooms = new LinkedHashSet<>();
  private final LinkedHashMap<String, ProvisioningCommitment> provisioningCommitments =
      new LinkedHashMap<>();
  private final EnumMap<com.example.dispute.workflow.contract.v1.ContractTypes.RoomType, Long>
      highestProvisionedEpochs =
          new EnumMap<>(com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.class);
  private final LinkedHashMap<String, PendingProvisioning> pendingProvisioningByUpdateId =
      new LinkedHashMap<>();
  private final LinkedHashSet<UnreconciledChildExecution> unreconciledChildren =
      new LinkedHashSet<>();

  private String tenantSurrogate;
  private String caseId;
  private RoomType activeRoomType;
  private long activeRoomEpoch = -1;
  private String activeChildWorkflowId;
  private String activeChildWorkflowRunId;
  private long activeFencingToken;
  private long activeRoomRevision = -1;
  private RoomControlWorkflow activeRoomChild;
  private IntakeRoomWorkflow activeIntakeChild;
  private TargetTypedRoomChildHandle activeTargetTypedChild;
  private ActiveChildDescriptor activeChildDescriptor;
  private long observedProcessRevision;
  private TargetRoomProgressReceipt lastTargetRoomProgress;
  private long nextCommandSequence = 1;
  private long nextCaseEventSequence = 1;
  private long processedCommandCount;
  private long processedEventCount;
  private long highestObservedCommandSequence;
  private long highestObservedEventSequence;
  private int runGeneration;
  private int commandInboxCount;
  private int eventInboxCount;
  private int provisioningInboxCount;
  private int commandRecoveryAttempts;
  private int eventRecoveryAttempts;
  private boolean commandManualRecoveryRequired;
  private boolean eventManualRecoveryRequired;
  private boolean provisioningManualRecoveryRequired;
  private boolean eventRecoveryForced;
  private boolean retrySequenceGapRequested;
  private boolean continueAsNewRequested;
  private boolean terminalTargetReviewCompleted;
  private Boolean futureRoomEventRetentionEnabled;
  private Boolean provisioningEnabled;
  private boolean authorityCheckpointEnabled;
  private int typedIntakeChildVersion;
  private int authorityBridgeVersion;
  private int targetTypedRoomVersion;
  private int childCompensationInvariantVersion;
  private int targetIntakeProjectionCompletionVersion;
  private boolean provisioningSwitchInProgress;
  private StartedChild uncommittedChild;
  private String protocolErrorCode;
  private RecoveryErrorOrigin protocolErrorOrigin;
  private Promise<Void> runMaxAgeTimer;
  private CaseProcessIntakeProjectionRecoveryRequest activeIntakeProjectionRecovery;
  private CaseProcessIntakeProjectionRecoveryRequest completedIntakeProjectionRecoveryRequest;
  private CaseProcessIntakeProjectionRecoveryResult completedIntakeProjectionRecoveryResult;

  private static ActivityOptions intakeChildBridgeActivityOptions() {
    return ActivityOptions.newBuilder()
        .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setScheduleToCloseTimeout(Duration.ofSeconds(30))
        .setHeartbeatTimeout(Duration.ofSeconds(10))
        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumInterval(Duration.ofSeconds(5))
                .setMaximumAttempts(3)
                .setDoNotRetry(
                    "INTAKE_CHILD_BRIDGE_INVARIANT",
                    "INTAKE_CHILD_BRIDGE_READ_UNCLASSIFIED")
                .build())
        .build();
  }

  private static ActivityOptions targetIntakeProjectionCompletionActivityOptions() {
    return ActivityOptions.newBuilder()
        .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setScheduleToCloseTimeout(Duration.ofMinutes(2))
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumInterval(Duration.ofSeconds(5))
                .setMaximumAttempts(0)
                .build())
        .build();
  }

  @Override
  public void run(CaseProcessCarryState carryState) {
    try {
      runUntilContinued(carryState);
    } catch (CanceledFailure failure) {
      if (childCompensationInvariantVersion == 1) {
        compensateChildrenAfterParentCancellation(failure);
      }
      throw failure;
    }
  }

  private void runUntilContinued(CaseProcessCarryState carryState) {
    restoreCarryState(carryState);
    provisioningEnabled =
        Workflow.getVersion(ROOM_EPOCH_PROVISION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
    authorityCheckpointEnabled =
        Workflow.getVersion(AUTHORITY_CHECKPOINT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
    typedIntakeChildVersion =
        Workflow.getVersion(TYPED_INTAKE_CHILD_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
    authorityBridgeVersion =
        Workflow.getVersion(AUTHORITY_BRIDGE_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
    targetTypedRoomVersion =
        Workflow.getVersion(TARGET_TYPED_ROOM_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
    childCompensationInvariantVersion =
        Workflow.getVersion(
            CHILD_COMPENSATION_INVARIANT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
    targetIntakeProjectionCompletionVersion =
        Workflow.getVersion(
            TARGET_INTAKE_PROJECTION_COMPLETION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
    validateCarriedTargetHistory();
    restoreActiveChildStub();
    restoreAuthorityCheckpoint();
    runMaxAgeTimer = Workflow.newTimer(RUN_MAX_AGE);
    while (true) {
      if (activeIntakeProjectionRecovery != null) {
        Workflow.await(() -> activeIntakeProjectionRecovery == null);
        continue;
      }
      drainCommandInbox();
      drainEventInbox();
      applyManualRecoveryRequest();

      if (terminalTargetReviewCompleted) {
        Workflow.await(Workflow::isEveryHandlerFinished);
        return;
      }

      if (processReplayCheck()) {
        continue;
      }
      if (processTargetIntakeTerminalNoCommit()) {
        continue;
      }
      if (processNextCommand()) {
        continue;
      }
      if (processNextEvent()) {
        continue;
      }
      if (recoverCommandGap()) {
        continue;
      }
      if (recoverEventGap()) {
        continue;
      }
      if (canSwitchRoomEpoch() && processNextProvisioning()) {
        continue;
      }
      if (shouldContinueAsNew() && canContinueAsNew()) {
        continueAsNew();
        return;
      }
      Workflow.await(this::hasWork);
    }
  }

  @Override
  public void acceptCommand(CaseCommandRef command) {
    awaitNoActiveIntakeProjectionRecovery();
    validateCommandEnvelope(command);
    validateProvisionedCommand(command);
    CompletablePromise<Void> completion = Workflow.newPromise();
    PendingCommand pending = PendingCommand.live(command, completion);
    commandInbox.put(pending);
    commandInboxCount++;
    completion.get();
  }

  @Override
  public void validateAcceptCommand(CaseCommandRef command) {
    requireNoActiveIntakeProjectionRecovery();
    validateCommandEnvelope(command);
    validateProvisionedCommand(command);
    if (command.caseCommandSequence() >= nextCommandSequence
        && deadlineElapsed(command, workflowNow())) {
      throw protocolFailure(
          "CASE_PROCESS_COMMAND_DEADLINE_EXPIRED",
          "command deadline elapsed before workflow admission");
    }
  }

  @Override
  public ProvisionRoomEpochReceipt provisionRoomEpoch(ProvisionRoomEpoch request) {
    awaitNoActiveIntakeProjectionRecovery();
    validateProvisionRequest(request);
    String updateId = currentUpdateId();
    String payloadSha256 = request.payloadSha256();
    requireExpectedProvisioningUpdateId(updateId, request);
    ProvisioningCommitment committed = provisioningCommitments.get(updateId);
    if (committed != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, committed.payloadSha256());
      return committed.receipt();
    }
    requireProvisioningReconciled();
    PendingProvisioning existing = pendingProvisioningByUpdateId.get(updateId);
    if (existing != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, existing.payloadSha256());
      return existing.completion().get();
    }
    validateProvisioningOrder(request, updateId, payloadSha256);
    observeProvisioningSequenceHighWater(request);
    CompletablePromise<ProvisionRoomEpochReceipt> completion = Workflow.newPromise();
    PendingProvisioning pending =
        new PendingProvisioning(updateId, payloadSha256, request, completion);
    pendingProvisioningByUpdateId.put(updateId, pending);
    provisioningInbox.put(pending);
    provisioningInboxCount++;
    return completion.get();
  }

  @Override
  public void validateProvisionRoomEpoch(ProvisionRoomEpoch request) {
    requireNoActiveIntakeProjectionRecovery();
    validateProvisionRequest(request);
    String updateId = currentUpdateId();
    String payloadSha256 = request.payloadSha256();
    requireExpectedProvisioningUpdateId(updateId, request);
    ProvisioningCommitment committed = provisioningCommitments.get(updateId);
    if (committed != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, committed.payloadSha256());
      return;
    }
    requireProvisioningReconciled();
    PendingProvisioning pending = pendingProvisioningByUpdateId.get(updateId);
    if (pending != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, pending.payloadSha256());
      return;
    }
    validateProvisioningOrder(request, updateId, payloadSha256);
  }

  @Override
  public CaseProcessIntakeProjectionRecoveryResult recoverIntakeProjectionCompletion(
      CaseProcessIntakeProjectionRecoveryRequest request) {
    requireIntakeProjectionRecovery(request, false);
    if (completedIntakeProjectionRecoveryResult != null) {
      return completedIntakeProjectionRecoveryResult;
    }
    activeIntakeProjectionRecovery = request;
    try {
      CompleteConsumedIntakeProjectionResult completed =
          processProjectionActivities.completeConsumedIntakeProjection(
              request.projectionCommand());
      if (!consumedIntakeProjectionResultMatches(request.projectionCommand(), completed)) {
        throw new IllegalArgumentException(
            "projection completion result does not match the recovery authority");
      }
      requireIntakeProjectionRecovery(request, true);
      CaseProcessIntakeProjectionRecoveryResult.Disposition disposition =
          completed.outcome() == CompleteConsumedIntakeProjectionOutcome.APPLIED
              ? CaseProcessIntakeProjectionRecoveryResult.Disposition.ADOPTED
              : CaseProcessIntakeProjectionRecoveryResult.Disposition.ALREADY_ADOPTED;
      CaseProcessIntakeProjectionRecoveryResult recovered =
          new CaseProcessIntakeProjectionRecoveryResult(
              CaseProcessIntakeProjectionRecoveryResult.SCHEMA_VERSION,
              disposition,
              request,
              completed);
      observeTargetIntakeProjectionReadyHighWater(completed);
      CaseDomainEventRef head = bufferedEvents.get(nextCaseEventSequence);
      if (!request.event().equals(head)
          || !bufferedEvents.remove(nextCaseEventSequence, head)) {
        throw new IllegalStateException("Intake projection recovery event authority changed");
      }
      nextCaseEventSequence++;
      processedEventCount++;
      eventRecoveryAttempts = 0;
      eventManualRecoveryRequired = false;
      if (nextCaseEventSequence > highestObservedEventSequence) {
        eventRecoveryForced = false;
      }
      clearIntakeProjectionRecoveryError();
      completedIntakeProjectionRecoveryRequest = request;
      completedIntakeProjectionRecoveryResult = recovered;
      return recovered;
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
      throw intakeProjectionRecoveryFailure(failure);
    } catch (RuntimeException failure) {
      throw intakeProjectionRecoveryFailure(failure);
    } finally {
      activeIntakeProjectionRecovery = null;
    }
  }

  @Override
  public void validateRecoverIntakeProjectionCompletion(
      CaseProcessIntakeProjectionRecoveryRequest request) {
    requireIntakeProjectionRecovery(request, false);
  }

  private void observeProvisioningSequenceHighWater(ProvisionRoomEpoch request) {
    if (Workflow.getVersion(
            PROVISIONING_SEQUENCE_HIGH_WATER_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        != 1) {
      return;
    }
    if (request.firstCommandSequence() < nextCommandSequence
        || request.firstCaseEventSequence() < nextCaseEventSequence) {
      return;
    }
    highestObservedCommandSequence =
        Math.max(highestObservedCommandSequence, request.lastCommandSequence());
    highestObservedEventSequence =
        Math.max(highestObservedEventSequence, request.lastCaseEventSequence());
  }

  @Override
  public void domainEventCommitted(CaseDomainEventRef event) {
    awaitNoActiveIntakeProjectionRecovery();
    String validationError = eventValidationError(event);
    if (validationError != null) {
      recordProtocolError(validationError, RecoveryErrorOrigin.DOMAIN_EVENT);
      return;
    }
    highestObservedEventSequence =
        Math.max(highestObservedEventSequence, event.caseEventSequence());
    if (event.caseEventSequence() < nextCaseEventSequence) {
      return;
    }
    if (!eventInbox.offer(event)) {
      eventRecoveryForced = true;
      recordProtocolError("CASE_PROCESS_EVENT_INBOX_FULL", RecoveryErrorOrigin.DOMAIN_EVENT);
      return;
    }
    eventInboxCount++;
  }

  @Override
  public void targetRoomProgressed(TargetRoomProgressReceipt receipt) {
    awaitNoActiveIntakeProjectionRecovery();
    if (receipt == null
        || activeChildDescriptor == null
        || activeChildDescriptor.kind() != ActiveChildKind.TARGET_TYPED_ROOM
        || receipt.roomType() != activeRoomType
        || receipt.roomEpoch() != activeRoomEpoch
        || receipt.fencingToken() != activeFencingToken) {
      recordProtocolError("TARGET_ROOM_PROGRESS_AUTHORITY_INVALID", RecoveryErrorOrigin.DOMAIN_EVENT);
      return;
    }
    if (receipt.equals(lastTargetRoomProgress)) return;
    if (receipt.processRevision() <= observedProcessRevision
        || receipt.roomRevision() <= activeRoomRevision) {
      recordProtocolError("TARGET_ROOM_PROGRESS_REVISION_INVALID", RecoveryErrorOrigin.DOMAIN_EVENT);
      return;
    }
    observedProcessRevision = receipt.processRevision();
    activeRoomRevision = receipt.roomRevision();
    activeChildDescriptor = activeChildDescriptor.withCurrentRevisions(observedProcessRevision, activeRoomRevision);
    lastTargetRoomProgress = receipt;
  }

  @Override
  public void targetIntakeCommandTerminalNoCommit(
      TargetIntakeCommandTerminalNoCommit authority) {
    awaitNoActiveIntakeProjectionRecovery();
    if (authority == null) {
      recordProtocolError(
          "TARGET_INTAKE_TERMINAL_NO_COMMIT_INVALID", RecoveryErrorOrigin.COMMAND);
      return;
    }
    if (terminalNoCommitInbox.size() >= INBOX_CAPACITY) {
      recordProtocolError(
          "TARGET_INTAKE_TERMINAL_NO_COMMIT_INBOX_FULL", RecoveryErrorOrigin.COMMAND);
      commandManualRecoveryRequired = true;
      return;
    }
    terminalNoCommitInbox.addLast(authority);
  }

  @Override
  public void retrySequenceGap() {
    awaitNoActiveIntakeProjectionRecovery();
    retrySequenceGapRequested = true;
  }

  @Override
  public void requestContinueAsNew() {
    continueAsNewRequested = true;
  }

  @Override
  public CaseProcessSnapshot state() {
    List<String> recentCommandIds = new ArrayList<>(recentCommands.keySet());
    return new CaseProcessSnapshot(
        "case-process-snapshot.v1",
        Workflow.getInfo().getWorkflowId(),
        Workflow.getInfo().getRunId(),
        tenantSurrogate,
        caseId,
        "CONTROL_PLANE_SHADOW",
        activeRoomType,
        activeRoomEpoch,
        activeChildWorkflowId,
        observedProcessRevision,
        nextCommandSequence,
        nextCaseEventSequence,
        processedCommandCount,
        processedEventCount,
        commandInboxCount + orderedCommands.size() + replayChecks.size(),
        eventInboxCount + bufferedEvents.size(),
        recentCommands.size(),
        highestObservedCommandSequence,
        highestObservedEventSequence,
        runGeneration,
        blockedReason(),
        protocolErrorCode,
        recentCommandIds,
        activeFencingToken,
        activeChildWorkflowRunId,
        provisioningCommitments.size(),
        currentProvisioningCommitment() == null
            ? null
            : currentProvisioningCommitment().payloadSha256(),
        activeChildDescriptor == null ? null : activeChildDescriptor.kind(),
        activeChildDescriptor == null ? null : activeChildDescriptor.selectionSchemaVersion(),
        activeChildDescriptor == null ? null : activeChildDescriptor.roomWorkflowType(),
        activeChildDescriptor == null ? null : activeChildDescriptor.roomWorkflowBuildId(),
        hasActiveChild() ? activeRoomRevision : null,
        protocolErrorOrigin,
        provisioningManualRecoveryRequired,
        new ArrayList<>(unreconciledChildren));
  }

  @Override
  public ProvisionRoomEpochReceipt provisioningReceipt() {
    ProvisioningCommitment commitment = currentProvisioningCommitment();
    return commitment == null ? null : commitment.receipt();
  }

  @Override
  public ProvisioningCommitment provisioningCommitment() {
    return currentProvisioningCommitment();
  }

  private void restoreCarryState(CaseProcessCarryState startCarryState) {
    CaseProcessCarryState carry = CaseProcessCarryState.initial();
    if (Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
      carry = startCarryState;
      if (carry == null) {
        carry =
            (CaseProcessCarryState)
                Workflow.getMemo(CARRY_STATE_MEMO_KEY, CaseProcessCarryState.class);
      }
      if (carry == null) {
        throw protocolFailure(
            "CASE_PROCESS_CARRY_STATE_MISSING", "continued workflow is missing carry state");
      }
    }
    tenantSurrogate = carry.tenantSurrogate();
    caseId = carry.caseId();
    activeRoomType = carry.activeRoomType();
    activeRoomEpoch = carry.activeRoomEpoch();
    activeChildWorkflowId = carry.activeChildWorkflowId();
    activeChildWorkflowRunId = carry.activeChildWorkflowRunId();
    activeFencingToken = carry.activeFencingToken();
    activeChildDescriptor = carry.activeChildDescriptor();
    observedProcessRevision = carry.observedProcessRevision();
    nextCommandSequence = carry.nextCommandSequence();
    nextCaseEventSequence = carry.nextCaseEventSequence();
    processedCommandCount = carry.processedCommandCount();
    processedEventCount = carry.processedEventCount();
    highestObservedCommandSequence = Math.max(0, nextCommandSequence - 1);
    highestObservedEventSequence = carry.highestObservedEventSequence();
    runGeneration = carry.runGeneration();
    commandRecoveryAttempts = carry.commandRecoveryAttempts();
    eventRecoveryAttempts = carry.eventRecoveryAttempts();
    commandManualRecoveryRequired = carry.commandManualRecoveryRequired();
    eventManualRecoveryRequired = carry.eventManualRecoveryRequired();
    provisioningManualRecoveryRequired = carry.provisioningManualRecoveryRequired();
    unreconciledChildren.addAll(carry.unreconciledChildren());
    lastTargetRoomProgress = carry.lastTargetRoomProgress();
    protocolErrorCode = carry.protocolErrorCode();
    protocolErrorOrigin =
        carry.protocolErrorOrigin() == null
            ? inferLegacyErrorOrigin(carry.protocolErrorCode())
            : carry.protocolErrorOrigin();
    carry.recentCommands().forEach(identity -> recentCommands.put(identity.commandId(), identity));
    carry.bufferedEvents().forEach(event -> bufferedEvents.put(event.caseEventSequence(), event));
    closedRooms.addAll(carry.closedRooms());
    carry
        .provisioningCommitments()
        .forEach(commitment -> provisioningCommitments.put(commitment.updateId(), commitment));
    validateCarriedProvisioningCommitments();
    carry
        .highestProvisionedEpochs()
        .forEach(
            highWater -> highestProvisionedEpochs.put(highWater.roomType(), highWater.roomEpoch()));
    normalizeAndValidateActiveChildDescriptor();
    activeRoomRevision = restoreActiveRoomRevision(carry.activeRoomRevision());
    validateTargetTypedActiveRevisions();
    if (tenantSurrogate != null) {
      requireWorkflowIdentity(tenantSurrogate, caseId);
    }
  }

  private void restoreActiveChildStub() {
    if (!hasActiveChild()) {
      return;
    }
    if (activeChildDescriptor.kind() == ActiveChildKind.GENERIC_ROOM_CONTROL) {
      activeRoomChild =
          Workflow.newExternalWorkflowStub(RoomControlWorkflow.class, activeChildWorkflowId);
      return;
    }
    if (activeChildDescriptor.kind() == ActiveChildKind.TARGET_TYPED_ROOM) {
      requireTargetTypedRoomVersion();
      validateTargetTypedDescriptor(activeChildDescriptor);
      activeTargetTypedChild = restoreTargetTypedRoomChild(activeChildDescriptor);
      if (activeTargetTypedChild == null
          || !matchesExecution(activeTargetTypedChild.execution(), activeChildDescriptor)) {
        throw protocolFailure(
            "TARGET_TYPED_ROOM_DISPATCHER_UNAVAILABLE",
            "target typed room restore requires the exact persisted child execution");
      }
      return;
    }
    if (typedIntakeChildVersion != 1) {
      throw protocolFailure(
          "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
          "typed Intake carry state requires the typed child version marker");
    }
    validateTypedDescriptor(activeChildDescriptor);
    activeIntakeChild =
        Workflow.newExternalWorkflowStub(IntakeRoomWorkflow.class, activeChildWorkflowId);
  }

  private void normalizeAndValidateActiveChildDescriptor() {
    if (activeChildWorkflowId == null) {
      if (activeChildDescriptor != null) {
        throw protocolFailure(
            "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
            "inactive carry state contains an active child descriptor");
      }
      return;
    }
    if (activeChildDescriptor == null) {
      ProvisioningCommitment commitment = currentProvisioningCommitment();
      if (commitment != null) {
        activeChildDescriptor =
            descriptor(
                commitment.request(),
                ActiveChildKind.GENERIC_ROOM_CONTROL,
                activeChildWorkflowRunId);
      } else {
        activeChildDescriptor =
            new ActiveChildDescriptor(
                ActiveChildKind.GENERIC_ROOM_CONTROL,
                SELECTION_V1,
                WriterMode.LEGACY,
                CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                "legacy-case-control.v1",
                null,
                null,
                activeRoomType,
                activeRoomEpoch,
                activeFencingToken,
                activeChildWorkflowId,
                activeChildWorkflowRunId);
      }
    }
    if (activeChildDescriptor.roomType() != activeRoomType
        || activeChildDescriptor.roomEpoch() != activeRoomEpoch
        || activeChildDescriptor.fencingToken() != activeFencingToken
        || !activeChildDescriptor.workflowId().equals(activeChildWorkflowId)
        || !Objects.equals(activeChildDescriptor.startedRunId(), activeChildWorkflowRunId)) {
      throw protocolFailure(
          "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
          "active child descriptor does not match the carried child identity");
    }
    validatePersistedDescriptor(activeChildDescriptor);
    if (activeChildDescriptor.kind() != ActiveChildKind.GENERIC_ROOM_CONTROL) {
      ProvisioningCommitment commitment = currentProvisioningCommitment();
      if (commitment == null || !activeChildDescriptor.matches(commitment)) {
        throw protocolFailure(
            "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
            "persisted typed Intake binding does not match its provisioning commitment");
      }
    }
  }

  private long restoreActiveRoomRevision(Long carriedRoomRevision) {
    if (!hasActiveChild()) {
      return -1;
    }
    if (carriedRoomRevision != null) {
      return carriedRoomRevision;
    }
    ProvisioningCommitment commitment = currentProvisioningCommitment();
    return commitment == null ? 0 : commitment.request().initialRoomRevision();
  }

  private static void validatePersistedDescriptor(ActiveChildDescriptor descriptor) {
    if (SELECTION_V1.equals(descriptor.selectionSchemaVersion())) {
      if (descriptor.kind() != ActiveChildKind.GENERIC_ROOM_CONTROL
          || descriptor.writerMode() == WriterMode.TEMPORAL) {
        throw protocolFailure(
            "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
            "v1 selection cannot restore a typed or TEMPORAL child");
      }
      return;
    }
    boolean validShadowIntake =
        descriptor.kind() == ActiveChildKind.TYPED_INTAKE
            && descriptor.writerMode() == WriterMode.SHADOW
            && descriptor.roomType() == RoomType.INTAKE
            && INTAKE_ROOM_WORKFLOW_TYPE.equals(descriptor.roomWorkflowType())
            && INTAKE_ROOM_WORKFLOW_BUILD_ID.equals(descriptor.roomWorkflowBuildId());
    boolean validTargetRoom =
        descriptor.kind() == ActiveChildKind.TARGET_TYPED_ROOM
            && descriptor.writerMode() == WriterMode.TEMPORAL
            && TargetTypedRoomProtocol.workflowType(descriptor.roomType())
                .equals(descriptor.roomWorkflowType());
    if (!SELECTION_V2.equals(descriptor.selectionSchemaVersion())
        || !CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(descriptor.caseWorkflowType())
        || (!validShadowIntake && !validTargetRoom)) {
      throw protocolFailure(
          "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
          "persisted active child selection is invalid");
    }
  }

  static void validateTypedDescriptor(ActiveChildDescriptor descriptor) {
    if (descriptor != null) {
      validatePersistedDescriptor(descriptor);
    }
    if (descriptor == null
        || descriptor.kind() != ActiveChildKind.TYPED_INTAKE
        || !SELECTION_V2.equals(descriptor.selectionSchemaVersion())
        || descriptor.writerMode() != WriterMode.SHADOW
        || descriptor.roomType() != RoomType.INTAKE
        || descriptor.fencingToken() < 1
        || !descriptor.hasPartyScopePins()
        || !CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(descriptor.caseWorkflowType())
        || !INTAKE_ROOM_WORKFLOW_TYPE.equals(descriptor.roomWorkflowType())
        || !INTAKE_ROOM_WORKFLOW_BUILD_ID.equals(descriptor.roomWorkflowBuildId())) {
      throw protocolFailure(
          "INTAKE_CHILD_ACTIVE_BINDING_INVALID", "persisted typed Intake binding is invalid");
    }
  }

  static void validateTargetTypedDescriptor(ActiveChildDescriptor descriptor) {
    if (descriptor != null) {
      validatePersistedDescriptor(descriptor);
    }
    if (descriptor == null
        || descriptor.kind() != ActiveChildKind.TARGET_TYPED_ROOM
        || !SELECTION_V2.equals(descriptor.selectionSchemaVersion())
        || descriptor.writerMode() != WriterMode.TEMPORAL
        || descriptor.fencingToken() < 1
        || (descriptor.roomType() == RoomType.INTAKE && !descriptor.hasPartyScopePins())
        || (descriptor.roomType() != RoomType.INTAKE && descriptor.hasPartyScopePins())
        || !CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(descriptor.caseWorkflowType())) {
      throw protocolFailure(
          "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID",
          "persisted target typed room binding is invalid");
    }
  }

  private void validateTargetTypedActiveRevisions() {
    if (activeChildDescriptor == null
        || activeChildDescriptor.kind() != ActiveChildKind.TARGET_TYPED_ROOM) {
      return;
    }
    validateTargetTypedDescriptor(activeChildDescriptor);
    ProvisioningCommitment commitment = currentProvisioningCommitment();
    if (commitment == null
        || !Objects.equals(
            activeChildDescriptor.initialProcessRevision(),
            commitment.request().initialProcessRevision())
        || !Objects.equals(
            activeChildDescriptor.initialRoomRevision(), commitment.request().initialRoomRevision())
        || !Objects.equals(activeChildDescriptor.currentProcessRevision(), observedProcessRevision)
        || activeRoomRevision < 0
        || !Objects.equals(activeChildDescriptor.currentRoomRevision(), activeRoomRevision)) {
      throw protocolFailure(
          "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID",
          "target typed child revision pins do not match active authority");
    }
  }

  private static boolean matchesExecution(
      WorkflowExecution execution, ActiveChildDescriptor descriptor) {
    return execution != null
        && descriptor.workflowId().equals(execution.getWorkflowId())
        && descriptor.startedRunId().equals(execution.getRunId());
  }

  private void applyTargetTypedRoomReceipt(TargetTypedRoomDispatchReceipt receipt) {
    if (receipt == null
        || receipt.roomType() != activeRoomType
        || receipt.roomEpoch() != activeRoomEpoch
        || receipt.fencingToken() != activeFencingToken
        || receipt.processRevision() < observedProcessRevision
        || receipt.roomRevision() < activeRoomRevision) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_RECEIPT_INVALID",
          "target typed room dispatch receipt violates the active fenced authority",
          null);
    }
    observedProcessRevision = receipt.processRevision();
    activeRoomRevision = receipt.roomRevision();
    activeChildDescriptor =
        activeChildDescriptor.withCurrentRevisions(observedProcessRevision, activeRoomRevision);
  }

  private void validateCarriedProvisioningCommitments() {
    String firstExecutionRunId = Workflow.getInfo().getFirstExecutionRunId();
    for (ProvisioningCommitment commitment : provisioningCommitments.values()) {
      requireCarriedCaseWorkflowRunId(commitment, firstExecutionRunId);
      if (commitment.request().writerMode() == WriterMode.TEMPORAL) {
        validateTargetProvisioningPins(commitment.request());
      }
    }
  }

  private void validateCarriedTargetHistory() {
    boolean carriesTemporal =
        provisioningCommitments.values().stream()
            .anyMatch(commitment -> commitment.request().writerMode() == WriterMode.TEMPORAL);
    if (carriesTemporal) {
      requireTargetTypedRoomVersion();
    }
  }

  private void requireTargetTypedRoomVersion() {
    if (targetTypedRoomVersion != 1) {
      throw protocolFailure(
          "TARGET_TYPED_ROOM_HISTORY_UNSUPPORTED",
          "TEMPORAL room history is not protected by the target typed-room version marker");
    }
  }

  static void requireCarriedCaseWorkflowRunId(
      ProvisioningCommitment commitment, String firstExecutionRunId) {
    if (commitment == null
        || firstExecutionRunId == null
        || !firstExecutionRunId.equals(commitment.receipt().caseWorkflowRunId())) {
      throw protocolFailure(
          "ROOM_EPOCH_CASE_WORKFLOW_RUN_ID_MISMATCH",
          "carried provisioning receipt does not match the first Case Workflow execution");
    }
  }

  private boolean hasActiveChild() {
    return activeChildDescriptor != null;
  }

  private boolean processNextProvisioning() {
    if (!provisioningEnabled || provisioningInboxCount == 0) {
      return false;
    }
    PendingProvisioning pending = provisioningInbox.poll();
    if (pending == null) {
      return false;
    }
    provisioningInboxCount--;
    provisioningSwitchInProgress = true;
    StartedChild started = null;
    boolean commitmentPublished = false;
    RuntimeException callerFailure = null;
    try {
      ProvisionRoomEpoch request = pending.request();
      requireProvisioningReconciled();
      validateAgainstCommittedProvisioning(request, pending.updateId(), pending.payloadSha256());
      if (request.firstCommandSequence() != nextCommandSequence
          || request.firstCaseEventSequence() != nextCaseEventSequence) {
        throw protocolFailure(
            "ROOM_EPOCH_SEQUENCE_BOUNDARY_CONFLICT",
            "provisioning sequence boundary does not match the case workflow");
      }
      boolean initialBootstrap =
          provisioningCommitments.isEmpty()
              && !hasActiveChild()
              && processedCommandCount == 0
              && processedEventCount == 0;
      if ((!initialBootstrap && request.initialProcessRevision() != observedProcessRevision)
          || (initialBootstrap && request.initialProcessRevision() < observedProcessRevision)) {
        throw protocolFailure(
            "ROOM_EPOCH_PROCESS_REVISION_CONFLICT",
            "provisioning process revision does not match case authority");
      }
      bindIdentity(request.tenantSurrogate(), request.caseId());
      ActiveChildKind childKind = selectProvisionedChildKind(request);
      started = startProvisionedChild(request, pending.payloadSha256(), childKind);
      uncommittedChild = started;
      if (childCompensationInvariantVersion == 1) {
        CancellationScope.throwCanceled();
      }

      ProvisionRoomEpochReceipt receipt =
          new ProvisionRoomEpochReceipt(
              "provision-room-epoch-receipt.v1",
              request.epochId(),
              request.tenantSurrogate(),
              request.caseId(),
              request.roomId(),
              request.roomType(),
              request.roomEpoch(),
              request.fencingToken(),
              request.initialProcessRevision(),
              request.initialRoomRevision(),
              request.macroPhase(),
              request.currentRoom(),
              request.roomPhase(),
              request.projectedDeadlineAt(),
              request.writerMode(),
              request.selectionSchemaVersion(),
              request.processContractVersion(),
              request.workflowType(),
              request.temporalBuildId(),
              request.roomWorkflowType(),
              request.roomWorkflowBuildId(),
              request.graphKey(),
              request.graphVersion(),
              request.checkpointSchemaVersion(),
              request.streamProtocol(),
              request.lastCommandSequence(),
              request.lastCaseEventSequence(),
              request.firstCommandSequence(),
              request.firstCaseEventSequence(),
              request.projectionRef(),
              request.projectionSha256(),
              request.requestedAt(),
              Workflow.getInfo().getWorkflowId(),
              Workflow.getInfo().getFirstExecutionRunId(),
              request.roomWorkflowId(),
              started.execution().getRunId(),
              pending.payloadSha256());
      ProvisioningCommitment commitment =
          new ProvisioningCommitment(pending.updateId(), pending.payloadSha256(), request, receipt);

      String retirementError =
          hasActiveChild() ? retireActiveChild("ROOM_CONTROL_REPLACED_BY_PROVISIONING") : null;
      activeRoomChild = started.genericChild();
      activeIntakeChild = started.typedIntakeChild();
      activeTargetTypedChild = started.targetTypedChild();
      activeRoomType = request.roomType();
      activeRoomEpoch = request.roomEpoch();
      activeChildWorkflowId = request.roomWorkflowId();
      activeChildWorkflowRunId = started.execution().getRunId();
      activeFencingToken = request.fencingToken();
      activeRoomRevision = request.initialRoomRevision();
      activeChildDescriptor =
          descriptor(
              request,
              childKind,
           activeChildWorkflowRunId,
           started.initiatorActorScopeHash(),
           started.respondentActorScopeHash(),
           started.targetTypedChild() == null
               ? null
               : started.targetTypedChild().reviewOutcomeStartBinding(),
           started.targetTypedChild() == null
               ? null
               : started.targetTypedChild().evidenceParticipantBinding());
      observedProcessRevision = request.initialProcessRevision();
      provisioningCommitments.put(pending.updateId(), commitment);
      highestProvisionedEpochs.merge(request.roomType(), request.roomEpoch(), Math::max);
      trimProvisioningCommitments();
      commitmentPublished = true;
      uncommittedChild = null;
      if (retirementError == null) {
        clearRecoveryError(RecoveryErrorOrigin.PROVISIONING);
      } else {
        recordProtocolError(retirementError, RecoveryErrorOrigin.PROVISIONING);
      }
      if (authorityCheckpointEnabled) {
        Workflow.upsertMemo(Map.of(AUTHORITY_CHECKPOINT_MEMO_KEY, receipt));
      }
      pending.complete(receipt);
    } catch (CanceledFailure failure) {
      callerFailure = failure;
      throw failure;
    } catch (TypedChildOperationFailure failure) {
      ApplicationFailure exposed = protocolFailure(failure.errorCode(), failure.getMessage());
      callerFailure = exposed;
      recordProtocolError(failure.errorCode(), RecoveryErrorOrigin.PROVISIONING);
      pending.fail(exposed);
    } catch (ApplicationFailure failure) {
      callerFailure = failure;
      recordProtocolError(failure.getType(), RecoveryErrorOrigin.PROVISIONING);
      pending.fail(failure);
    } catch (ChildWorkflowFailure failure) {
      ApplicationFailure conflict =
          protocolFailure(
              "ROOM_EPOCH_CHILD_START_CONFLICT",
              "room child workflow id is already bound to another execution");
      callerFailure = conflict;
      recordProtocolError(conflict.getType(), RecoveryErrorOrigin.PROVISIONING);
      pending.fail(conflict);
    } catch (RuntimeException failure) {
      ApplicationFailure exposed = failClosedProvisioningRuntime(failure);
      callerFailure = exposed;
      markProvisioningManualRecovery(null);
      recordProtocolError(
          "ROOM_EPOCH_PROVISIONING_RUNTIME_FAILURE", RecoveryErrorOrigin.PROVISIONING);
      pending.fail(exposed);
    } finally {
      if (started != null && !commitmentPublished) {
        CompensationOutcome compensation =
            cancelUncommittedChild(
                started.execution(), "ROOM_CONTROL_PROVISIONING_NOT_COMMITTED");
        if (compensation.requiresManualRecovery()) {
          markProvisioningManualRecovery(started.execution());
          if (callerFailure != null && compensation.failure() != null) {
            callerFailure.addSuppressed(compensation.failure());
          }
        }
      }
      clearUncommittedChild(started);
      provisioningSwitchInProgress = false;
      pendingProvisioningByUpdateId.remove(pending.updateId());
    }
    return true;
  }

  private CommandBinding bindIntakeChildCommand(CommandRequest request) {
    return authorityBridgeVersion == 1
        ? intakeChildBridgeActivitiesV2.bindCommand(request)
        : intakeChildBridgeActivities.bindCommand(request);
  }

  private StartBinding bindIntakeChildStart(StartRequest request) {
    return authorityBridgeVersion == 1
        ? intakeChildBridgeActivitiesV2.bindStart(request)
        : intakeChildBridgeActivities.bindStart(request);
  }

  private DomainEventBinding bindIntakeChildDomainEvent(DomainEventRequest request) {
    return authorityBridgeVersion == 1
        ? intakeChildBridgeActivitiesV2.bindDomainEvent(request)
        : intakeChildBridgeActivities.bindDomainEvent(request);
  }

  private void routeCommandToActiveChild(CaseCommandRef command) {
    if (activeChildDescriptor.kind() == ActiveChildKind.GENERIC_ROOM_CONTROL) {
      if (activeRoomChild == null) {
        throw new TypedChildOperationFailure(
            "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
            "generic active child stub is missing",
            null);
      }
      activeRoomChild.commandAccepted(command);
      return;
    }
    if (activeChildDescriptor.kind() == ActiveChildKind.TARGET_TYPED_ROOM) {
      validateTargetTypedDescriptor(activeChildDescriptor);
      validateTargetTypedActiveRevisions();
      if (activeTargetTypedChild == null) {
        throw new TypedChildOperationFailure(
            "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID",
            "target typed active child handle is missing",
            null);
      }
      try {
        applyTargetTypedRoomReceipt(activeTargetTypedChild.commandAccepted(command));
      } catch (TypedChildOperationFailure failure) {
        throw failure;
      } catch (CanceledFailure failure) {
        throw failure;
      } catch (RuntimeException failure) {
        throw new TypedChildOperationFailure(
            "TARGET_TYPED_ROOM_COMMAND_DISPATCH_FAILED",
            "target typed child could not accept the command",
            failure);
      }
      return;
    }
    ActiveChildBinding expected = activeBinding();
    if (activeIntakeChild == null) {
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
          "typed Intake active child stub is missing",
          null);
    }
    CommandBinding binding;
    try {
      binding =
          bindIntakeChildCommand(
              new CommandRequest("intake-child-command-request.v1", command, expected));
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_BRIDGE_COMMAND_FAILED",
          "typed Intake command binding Activity failed",
          failure);
    }
    validateCommandBinding(binding, command, expected);
    try {
      activeIntakeChild.commandAccepted(binding.command());
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (SignalExternalWorkflowException | TemporalFailure failure) {
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_COMMAND_SIGNAL_FAILED",
          "typed Intake child could not accept the command",
          failure);
    }
  }

  /**
   * Completes target-only work after CompleteCaseCommandRouting has committed. This ordering keeps
   * Review receipt acceptance separate from the Activity that mutates the terminal DB revision.
   */
  private void completeTargetChildAfterDurableRouting(CaseCommandRef command) {
    if (activeChildDescriptor == null
        || activeChildDescriptor.kind() != ActiveChildKind.TARGET_TYPED_ROOM) {
      return;
    }
    validateTargetTypedDescriptor(activeChildDescriptor);
    validateTargetTypedActiveRevisions();
    if (activeTargetTypedChild == null) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID",
          "target typed child handle is missing for post-routing completion",
          null);
    }
    try {
      TargetTypedRoomDispatchReceipt receipt = activeTargetTypedChild.postRouting(command);
      if (activeTargetTypedChild.terminalAfterPostRouting()) {
        adoptTargetTerminalReceipt(receipt, activeTargetTypedChild.terminalProgressReceipt());
      } else if (activeTargetTypedChild.sourceTransitionAfterPostRouting()) {
        adoptTargetSourceTransitionReceipt(
            receipt, activeTargetTypedChild.terminalProgressReceipt());
      } else if (receipt != null) {
        applyTargetTypedRoomReceipt(receipt);
      }
    } catch (TypedChildOperationFailure failure) {
      throw failure;
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_POST_ROUTING_COMPLETION_FAILED",
          "target typed child post-routing completion failed",
          failure);
    }
  }

  /** Replays B's exact terminal receipt when command routing was committed before parent progress. */
  private void recoverAppliedTargetTerminal(CaseCommandRef command) {
    if (activeChildDescriptor == null
        || activeChildDescriptor.kind() != ActiveChildKind.TARGET_TYPED_ROOM
        || activeRoomType != RoomType.REVIEW) {
      return;
    }
    validateTargetTypedDescriptor(activeChildDescriptor);
    validateTargetTypedActiveRevisions();
    if (activeTargetTypedChild == null) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID",
          "target typed child handle is missing for APPLIED recovery",
          null);
    }
    try {
      TargetTypedRoomDispatchReceipt receipt = activeTargetTypedChild.recoverAppliedTerminal(command);
      if (activeTargetTypedChild.terminalAfterPostRouting()) {
        adoptTargetTerminalReceipt(receipt, activeTargetTypedChild.terminalProgressReceipt());
      } else if (activeTargetTypedChild.sourceTransitionAfterPostRouting()) {
        adoptTargetSourceTransitionReceipt(
            receipt, activeTargetTypedChild.terminalProgressReceipt());
      }
    } catch (TypedChildOperationFailure failure) {
      throw failure;
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_APPLIED_RECOVERY_FAILED",
          "target Review APPLIED recovery could not load its terminal receipt",
          failure);
    }
  }

  private void adoptTargetTerminalReceipt(
      TargetTypedRoomDispatchReceipt receipt, TargetRoomProgressReceipt terminal) {
    if (terminal == null
        || receipt == null
        || terminal.roomType() != receipt.roomType()
        || terminal.roomEpoch() != receipt.roomEpoch()
        || terminal.fencingToken() != receipt.fencingToken()
        || terminal.processRevision() != receipt.processRevision()
        || terminal.roomRevision() != receipt.roomRevision()) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_TERMINAL_RECEIPT_INVALID",
          "target terminal completion is missing its exact durable progress receipt",
          null);
    }
    if (activeRoomType != RoomType.REVIEW) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_TERMINAL_TYPE_INVALID",
          "only target Review may complete the case process",
          null);
    }
    applyTargetTypedRoomReceipt(receipt);
    lastTargetRoomProgress = terminal;
    rememberClosedRoom(activeRoomType, activeRoomEpoch);
    terminalTargetReviewCompleted = true;
  }

  /** Closes the source Review epoch while the bootstrap outbox provisions a real next room. */
  private void adoptTargetSourceTransitionReceipt(
      TargetTypedRoomDispatchReceipt receipt, TargetRoomProgressReceipt sourceTerminal) {
    if (sourceTerminal == null
        || receipt == null
        || sourceTerminal.roomType() != receipt.roomType()
        || sourceTerminal.roomEpoch() != receipt.roomEpoch()
        || sourceTerminal.fencingToken() != receipt.fencingToken()
        || sourceTerminal.processRevision() != receipt.processRevision()
        || sourceTerminal.roomRevision() != receipt.roomRevision()) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_SOURCE_TRANSITION_RECEIPT_INVALID",
          "target source transition is missing its exact durable Review progress receipt",
          null);
    }
    if (activeRoomType != RoomType.REVIEW) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_SOURCE_TRANSITION_TYPE_INVALID",
          "only target Review may transition to a newly provisioned room",
          null);
    }
    applyTargetTypedRoomReceipt(receipt);
    lastTargetRoomProgress = sourceTerminal;
    rememberClosedRoom(activeRoomType, activeRoomEpoch);
  }

  private ActiveChildKind selectProvisionedChildKind(ProvisionRoomEpoch request) {
    validateTargetProvisioningPins(request);
    return selectProvisionedChildKind(
        typedIntakeChildVersion,
        targetTypedRoomVersion,
        request.selectionSchemaVersion(),
        request.processContractVersion(),
        request.writerMode(),
        request.roomType(),
        request.caseWorkflowType(),
        request.caseWorkflowBuildId(),
        request.roomWorkflowType(),
        request.roomWorkflowBuildId(),
        request.graphKey(),
        request.graphVersion(),
        request.checkpointSchemaVersion(),
        request.streamProtocol());
  }

  private static void validateTargetProvisioningPins(ProvisionRoomEpoch request) {
    if (request.writerMode() != WriterMode.TEMPORAL) {
      return;
    }
    if (!TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION.equals(
            request.selectionSchemaVersion())
        || !TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION.equals(
            request.processContractVersion())
        || !TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE.equals(request.caseWorkflowType())
        || !TargetTypedRoomProtocol.workflowType(request.roomType())
            .equals(request.roomWorkflowType())
        || !TargetTypedRoomProtocol.GRAPH_KEY.equals(request.graphKey())
        || !TargetTypedRoomProtocol.GRAPH_VERSION.equals(request.graphVersion())
        || !TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION.equals(
            request.checkpointSchemaVersion())
        || !TargetTypedRoomProtocol.STREAM_PROTOCOL.equals(request.streamProtocol())) {
      throw protocolFailure(
          "TARGET_TYPED_ROOM_SELECTION_INVALID",
          "target provisioning carries mixed Workflow or Graph protocol pins");
    }
  }

  static ActiveChildKind selectProvisionedChildKind(
      int markerVersion,
      String selectionSchemaVersion,
      WriterMode writerMode,
      RoomType roomType,
      String caseWorkflowType,
      String roomWorkflowType,
      String roomWorkflowBuildId,
      String graphKey) {
    if (writerMode == WriterMode.TEMPORAL) {
      throw protocolFailure(
          "INTAKE_CHILD_WRITER_MODE_INVALID",
          "Phase 4 room selection rejects TEMPORAL writer mode");
    }
    return selectProvisionedChildKind(
        markerVersion,
        Workflow.DEFAULT_VERSION,
        selectionSchemaVersion,
        null,
        writerMode,
        roomType,
        caseWorkflowType,
        null,
        roomWorkflowType,
        roomWorkflowBuildId,
        graphKey,
        null,
        null,
        null);
  }

  static ActiveChildKind selectProvisionedChildKind(
      int markerVersion,
      int targetMarkerVersion,
      String selectionSchemaVersion,
      String processContractVersion,
      WriterMode writerMode,
      RoomType roomType,
      String caseWorkflowType,
      String caseWorkflowBuildId,
      String roomWorkflowType,
      String roomWorkflowBuildId,
      String graphKey,
      String graphVersion,
      String checkpointSchemaVersion,
      String streamProtocol) {
    if (SELECTION_V1.equals(selectionSchemaVersion)) {
      if (roomWorkflowType != null || roomWorkflowBuildId != null) {
        throw protocolFailure(
            "INTAKE_CHILD_SELECTION_VERSION_INVALID",
            "v1 selection cannot contain a room child Workflow binding");
      }
      if (writerMode == WriterMode.TEMPORAL) {
        throw protocolFailure(
            "TARGET_TYPED_ROOM_SELECTION_INVALID",
            "TEMPORAL room selection requires the typed v2 contract");
      }
      return ActiveChildKind.GENERIC_ROOM_CONTROL;
    }
    if (!SELECTION_V2.equals(selectionSchemaVersion)) {
      throw protocolFailure(
          "INTAKE_CHILD_SELECTION_VERSION_INVALID",
          "unsupported room epoch selection version");
    }
    if (writerMode == WriterMode.TEMPORAL) {
      if (targetMarkerVersion != 1) {
        throw protocolFailure(
            "TARGET_TYPED_ROOM_HISTORY_UNSUPPORTED",
            "TEMPORAL room selection requires the target typed-room version marker");
      }
      if (!TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION.equals(processContractVersion)
          || !TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE.equals(caseWorkflowType)
          || caseWorkflowBuildId == null
          || caseWorkflowBuildId.isBlank()) {
        throw protocolFailure(
            "TARGET_TYPED_ROOM_CASE_WORKFLOW_TYPE_INVALID",
            "target selection does not bind the exact CaseProcessWorkflow contract and build");
      }
      if (!TargetTypedRoomProtocol.workflowType(roomType).equals(roomWorkflowType)
          || roomWorkflowBuildId == null
          || roomWorkflowBuildId.isBlank()
          || !TargetTypedRoomProtocol.GRAPH_KEY.equals(graphKey)
          || !TargetTypedRoomProtocol.GRAPH_VERSION.equals(graphVersion)
          || !TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION.equals(
              checkpointSchemaVersion)
          || !TargetTypedRoomProtocol.STREAM_PROTOCOL.equals(streamProtocol)) {
        throw protocolFailure(
            "TARGET_TYPED_ROOM_SELECTION_INVALID",
            "target selection has mixed or incomplete room Workflow and Graph pins");
      }
      return ActiveChildKind.TARGET_TYPED_ROOM;
    }
    if (writerMode != WriterMode.SHADOW) {
      throw protocolFailure(
          "INTAKE_CHILD_WRITER_MODE_INVALID",
          "Phase 4 typed Intake selection requires SHADOW writer mode");
    }
    if (roomType != RoomType.INTAKE) {
      throw protocolFailure(
          "INTAKE_CHILD_ROOM_TYPE_INVALID",
          "typed room selection is restricted to Intake");
    }
    if (!CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(caseWorkflowType)) {
      throw protocolFailure(
          "INTAKE_CHILD_CASE_WORKFLOW_TYPE_INVALID",
          "v2 selection does not bind the CaseProcessWorkflow case type");
    }
    if (!INTAKE_ROOM_WORKFLOW_TYPE.equals(roomWorkflowType)) {
      throw protocolFailure(
          "INTAKE_CHILD_WORKFLOW_TYPE_INVALID",
          "v2 Intake selection has an unsupported room child type");
    }
    if (!INTAKE_ROOM_WORKFLOW_BUILD_ID.equals(roomWorkflowBuildId)) {
      throw protocolFailure(
          "INTAKE_CHILD_WORKFLOW_BUILD_INVALID",
          "v2 Intake selection has an unsupported room child build");
    }
    if (!INTAKE_GRAPH_KEY.equals(graphKey)) {
      throw protocolFailure(
          "INTAKE_CHILD_SELECTION_INVALID", "v2 Intake selection has an invalid graph binding");
    }
    return markerVersion == 1
        ? ActiveChildKind.TYPED_INTAKE
        : ActiveChildKind.GENERIC_ROOM_CONTROL;
  }

  private StartedChild startProvisionedChild(
      ProvisionRoomEpoch request, String provisioningHash, ActiveChildKind childKind) {
    if (childKind == ActiveChildKind.TARGET_TYPED_ROOM) {
      requireTargetTypedRoomVersion();
      TargetTypedRoomChildHandle child =
          startTargetInDetachedCancellationScope(
              () -> startTargetTypedRoomChild(request, provisioningHash));
      if (child == null
          || child.execution() == null
          || !request.roomWorkflowId().equals(child.execution().getWorkflowId())
          || child.execution().getRunId().isBlank()) {
        throw protocolFailure(
            "TARGET_TYPED_ROOM_DISPATCHER_UNAVAILABLE",
            "target typed room start requires a target-only dispatcher");
      }
      return new StartedChild(
          null,
          null,
          child,
          child.execution(),
          child.initiatorActorScopeHash(),
          child.respondentActorScopeHash());
    }
    if (childKind == ActiveChildKind.TYPED_INTAKE) {
      return startTypedIntakeChild(request, provisioningHash);
    }
    RoomControlStart start =
        new RoomControlStart(
            "room-control-start.v1",
            request.tenantSurrogate(),
            request.caseId(),
            request.epochId(),
            request.roomId(),
            request.roomType(),
            request.roomEpoch(),
            request.caseWorkflowId(),
            request.roomWorkflowId(),
            request.firstCommandSequence(),
            request.firstCaseEventSequence(),
            request.fencingToken(),
            request.initialProcessRevision(),
            request.initialRoomRevision(),
            request.macroPhase(),
            request.currentRoom(),
            request.roomPhase(),
            request.projectedDeadlineAt(),
            request.writerMode(),
            request.selectionSchemaVersion(),
            request.processContractVersion(),
            request.workflowType(),
            request.temporalBuildId(),
            request.roomWorkflowType(),
            request.roomWorkflowBuildId(),
            request.graphKey(),
            request.graphVersion(),
            request.checkpointSchemaVersion(),
            request.streamProtocol(),
            request.lastCommandSequence(),
            request.lastCaseEventSequence(),
            request.projectionRef(),
            request.projectionSha256(),
            request.requestedAt(),
            Workflow.getInfo().getFirstExecutionRunId(),
            provisioningHash);
    return startInDetachedCancellationScope(
        () -> {
          RoomControlWorkflow child =
              Workflow.newChildWorkflowStub(
                  RoomControlWorkflow.class, childOptions(request.roomWorkflowId()));
          Promise<Void> childCompletion = Async.procedure(child::run, start);
          childCompletion.exceptionally(failure -> null);
          return new StartedChild(
              child, null, null, Workflow.getWorkflowExecution(child).get(), null, null);
        });
  }

  private StartedChild startTypedIntakeChild(
      ProvisionRoomEpoch request, String provisioningHash) {
    ActiveChildBinding expected = activeBinding(request);
    StartBinding binding;
    try {
      binding =
          bindIntakeChildStart(
              new StartRequest("intake-child-start-request.v1", request, expected));
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_BRIDGE_START_FAILED",
          "typed Intake start binding Activity failed",
          failure);
    }
    requireReturnedActiveBinding(binding == null ? null : binding.activeBinding(), expected);
    if (!provisioningHash.equals(binding.provisioningRequestHash())
        || binding.start() == null
        || !request.tenantSurrogate().equals(binding.start().tenantSurrogate())
        || !request.caseId().equals(binding.start().caseId())
        || request.roomEpoch() != binding.start().roomEpoch()
        || request.fencingToken() != binding.start().fencingToken()
        || request.initialProcessRevision() != binding.start().initialProcessRevision()
        || request.initialRoomRevision() != binding.start().initialRoomRevision()
        || request.firstCommandSequence() != binding.start().firstCommandSequence()
        || request.firstCaseEventSequence() != binding.start().firstEventSequence()
        || !request.roomWorkflowBuildId().equals(binding.start().workflowBuildId())
        || !request.graphVersion().equals(binding.start().graphVersion())
        || !request.checkpointSchemaVersion().equals(binding.start().checkpointSchemaVersion())
        || binding.start().carryState() != null) {
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_BRIDGE_START_BINDING_INVALID",
          "typed Intake start binding does not match persisted provisioning",
          null);
    }
    return startInDetachedCancellationScope(
        () -> {
          IntakeRoomWorkflow child =
              Workflow.newChildWorkflowStub(
                  IntakeRoomWorkflow.class, childOptions(request.roomWorkflowId()));
          Promise<?> childCompletion = Async.function(child::run, binding.start());
          childCompletion.exceptionally(failure -> null);
          return new StartedChild(
              null,
              child,
              null,
              Workflow.getWorkflowExecution(child).get(),
              binding.start().initiatorActorScopeHash(),
              binding.start().respondentActorScopeHash());
        });
  }

  /**
   * Target-only workflow implementations override this hook in an isolated source set. The
   * default artifact never acquires dynamic child-start capability.
   */
  protected TargetTypedRoomChildHandle startTargetTypedRoomChild(
      ProvisionRoomEpoch request, String provisioningHash) {
    throw protocolFailure(
        "TARGET_TYPED_ROOM_DISPATCHER_UNAVAILABLE",
        "target typed room start dispatcher is not assembled in the default artifact");
  }

  /** Restores the target-only handle after Continue-As-New without changing child identity. */
  protected TargetTypedRoomChildHandle restoreTargetTypedRoomChild(
      ActiveChildDescriptor descriptor) {
    throw protocolFailure(
        "TARGET_TYPED_ROOM_DISPATCHER_UNAVAILABLE",
        "target typed room restore dispatcher is not assembled in the default artifact");
  }

  private static StartedChild startInDetachedCancellationScope(Supplier<StartedChild> starter) {
    StartedChild[] started = new StartedChild[1];
    CancellationScope detached =
        Workflow.newDetachedCancellationScope(() -> started[0] = starter.get());
    detached.run();
    return Objects.requireNonNull(started[0], "detached child start did not capture an execution");
  }

  private static TargetTypedRoomChildHandle startTargetInDetachedCancellationScope(
      Supplier<TargetTypedRoomChildHandle> starter) {
    TargetTypedRoomChildHandle[] started = new TargetTypedRoomChildHandle[1];
    CancellationScope detached =
        Workflow.newDetachedCancellationScope(() -> started[0] = starter.get());
    detached.run();
    return Objects.requireNonNull(
        started[0], "detached target child start did not capture an execution");
  }

  private static ChildWorkflowOptions childOptions(String workflowId) {
    return ChildWorkflowOptions.newBuilder()
        .setWorkflowId(workflowId)
        .setTaskQueue(ROOM_CONTROL_TASK_QUEUE)
        .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        .setParentClosePolicy(PARENT_CLOSE_POLICY_ABANDON)
        .build();
  }

  private ActiveChildBinding activeBinding(ProvisionRoomEpoch request) {
    return new ActiveChildBinding(
        "active-intake-child-binding.v1",
        request.tenantSurrogate(),
        request.caseId(),
        request.roomEpoch(),
        request.fencingToken(),
        request.selectionSchemaVersion(),
        request.caseWorkflowType(),
        request.caseWorkflowBuildId(),
        request.roomWorkflowType(),
        request.roomWorkflowBuildId());
  }

  private ActiveChildBinding activeBinding() {
    validateTypedDescriptor(activeChildDescriptor);
    return new ActiveChildBinding(
        "active-intake-child-binding.v1",
        tenantSurrogate,
        caseId,
        activeChildDescriptor.roomEpoch(),
        activeChildDescriptor.fencingToken(),
        activeChildDescriptor.selectionSchemaVersion(),
        activeChildDescriptor.caseWorkflowType(),
        activeChildDescriptor.caseWorkflowBuildId(),
        activeChildDescriptor.roomWorkflowType(),
        activeChildDescriptor.roomWorkflowBuildId());
  }

  private static void requireReturnedActiveBinding(
      ActiveChildBinding returned, ActiveChildBinding expected) {
    if (!expected.equals(returned)) {
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
          "bridge returned an active child binding that does not match persisted authority",
          null);
    }
  }

  private void validateCommandBinding(
      CommandBinding binding, CaseCommandRef source, ActiveChildBinding expected) {
    if (binding == null || binding.command() == null) {
      throw invalidCommandBinding("typed Intake command binding is incomplete");
    }
    if (!expected.equals(binding.activeBinding())) {
      throw invalidCommandBinding("typed Intake command binding changed active authority");
    }
    IntakeWorkflowCommand typed = binding.command();
    IntakeCommandType expectedType = expectedCommandType(source.commandType());
    if (!source.payloadRef().sha256().equals(binding.sourcePayloadHash())
        || !source.requestHash().equals(binding.requestHash())
        || source.expectedProcessRevision() != binding.processRevision()
        || activeRoomRevision != binding.roomRevision()
        || !source.commandId().equals(typed.commandId())
        || !source.tenantSurrogate().equals(typed.tenantSurrogate())
        || !source.caseId().equals(typed.caseId())
        || source.roomEpoch() != typed.roomEpoch()
        || expected.fencingToken() != typed.fencingToken()
        || source.caseCommandSequence() != typed.sequence()
        || expectedType == null
        || expectedType != typed.commandType()
        || !expectedActorScopeHash(typed.party()).equals(typed.actorScopeHash())
        || !source.payloadRef().uri().equals(typed.payloadRef())
        || !source.payloadRef().sha256().equals(typed.payloadHash())
        || !source.requestHash().equals(typed.requestHash())
        || !("intake.operation:" + source.caseId() + ":" + source.commandId())
            .equals(typed.operationKey())
        || typed.executionContext() != null) {
      throw invalidCommandBinding(
          "typed Intake command binding does not match the authoritative command");
    }
  }

  private void validateDomainEventBinding(
      DomainEventBinding binding, CaseDomainEventRef source, ActiveChildBinding expected) {
    if (binding == null || binding.event() == null) {
      throw invalidEventBinding("typed Intake domain event binding is incomplete");
    }
    if (!expected.equals(binding.activeBinding())) {
      throw invalidEventBinding("typed Intake domain event binding changed active authority");
    }
    IntakeDomainEventRef typed = binding.event();
    IntakeDomainEventType expectedType = expectedEventType(source.eventType());
    if (!source.payloadRef().sha256().equals(binding.sourcePayloadHash())
        || !binding.requestHash().equals(typed.requestHash())
        || binding.processRevision() != typed.processRevision()
        || binding.roomRevision() != typed.roomRevision()
        || binding.processRevision() < observedProcessRevision
        || binding.roomRevision() < activeRoomRevision
        || !source.eventId().equals(typed.eventId())
        || source.caseEventSequence() != typed.eventSequence()
        || expectedType == null
        || expectedType != typed.eventType()
        || !validEventParty(expectedType, typed.party())
        || !expectedActorScopeHash(typed.party()).equals(typed.actorScopeHash())
        || !source.tenantSurrogate().equals(typed.tenantSurrogate())
        || !source.caseId().equals(typed.caseId())
        || source.roomEpoch() != typed.roomEpoch()
        || expected.fencingToken() != typed.fencingToken()
        || !("intake.operation:" + source.caseId() + ":" + typed.commandId())
            .equals(typed.operationKey())) {
      throw invalidEventBinding(
          "typed Intake domain event binding does not match the authoritative event");
    }
  }

  private static TypedChildOperationFailure invalidCommandBinding(String message) {
    return new TypedChildOperationFailure(
        "INTAKE_CHILD_BRIDGE_COMMAND_BINDING_INVALID", message, null);
  }

  private static TypedChildOperationFailure invalidEventBinding(String message) {
    return new TypedChildOperationFailure(
        "INTAKE_CHILD_BRIDGE_EVENT_BINDING_INVALID", message, null);
  }

  private static IntakeCommandType expectedCommandType(CommandType source) {
    return switch (source) {
      case INTAKE_MESSAGE -> IntakeCommandType.INTAKE_MESSAGE;
      case INTAKE_CONFIRM -> IntakeCommandType.INTAKE_CONFIRM;
      case INTAKE_CANCEL -> IntakeCommandType.INTAKE_CANCEL;
      default -> null;
    };
  }

  private static IntakeDomainEventType expectedEventType(String source) {
    return switch (source) {
      case "TURN_NEEDS_INPUT", "INTAKE_TURN_NEEDS_INPUT" ->
          IntakeDomainEventType.TURN_NEEDS_INPUT;
      case "TURN_READY_TO_CONFIRM", "INTAKE_TURN_READY_TO_CONFIRM" ->
          IntakeDomainEventType.TURN_READY_TO_CONFIRM;
      case "INITIATOR_ACCEPTED", "INITIATOR_INTAKE_COMPLETED" ->
          IntakeDomainEventType.INITIATOR_ACCEPTED;
      case "NOT_ADMISSIBLE", "INTAKE_REJECTED" -> IntakeDomainEventType.NOT_ADMISSIBLE;
      case "CANCELLED", "INTAKE_CANCELLED" -> IntakeDomainEventType.CANCELLED;
      case "RESPONDENT_CONFIRMED", "RESPONDENT_INTAKE_COMPLETED" ->
          IntakeDomainEventType.RESPONDENT_CONFIRMED;
      default -> null;
    };
  }

  private static boolean validEventParty(IntakeDomainEventType type, IntakeParty party) {
    return switch (type) {
      case RESPONDENT_CONFIRMED -> party == IntakeParty.RESPONDENT;
      case INITIATOR_ACCEPTED, NOT_ADMISSIBLE, CANCELLED -> party == IntakeParty.INITIATOR;
      case TURN_NEEDS_INPUT, TURN_READY_TO_CONFIRM -> party != null;
    };
  }

  private String expectedActorScopeHash(IntakeParty party) {
    validateTypedDescriptor(activeChildDescriptor);
    return party == IntakeParty.INITIATOR
        ? activeChildDescriptor.initiatorActorScopeHash()
        : activeChildDescriptor.respondentActorScopeHash();
  }

  private static ActiveChildDescriptor descriptor(
      ProvisionRoomEpoch request, ActiveChildKind kind, String startedRunId) {
    return descriptor(request, kind, startedRunId, null, null);
  }

  private static ActiveChildDescriptor descriptor(
      ProvisionRoomEpoch request,
      ActiveChildKind kind,
       String startedRunId,
       String initiatorActorScopeHash,
       String respondentActorScopeHash) {
     return descriptor(
         request,
         kind,
         startedRunId,
         initiatorActorScopeHash,
         respondentActorScopeHash,
         null,
         null);
   }

   private static ActiveChildDescriptor descriptor(
       ProvisionRoomEpoch request,
       ActiveChildKind kind,
       String startedRunId,
       String initiatorActorScopeHash,
       String respondentActorScopeHash,
       Binding reviewOutcomeStartBinding,
       com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceParticipantBindingActivities.Binding
           evidenceParticipantBinding) {
    if (kind == ActiveChildKind.TARGET_TYPED_ROOM) {
      return new ActiveChildDescriptor(
          kind,
          request.selectionSchemaVersion(),
          request.writerMode(),
          request.caseWorkflowType(),
          request.caseWorkflowBuildId(),
          request.roomWorkflowType(),
          request.roomWorkflowBuildId(),
          request.roomType(),
          request.roomEpoch(),
          request.fencingToken(),
          request.roomWorkflowId(),
          startedRunId,
          initiatorActorScopeHash,
          respondentActorScopeHash,
           request.initialProcessRevision(),
           request.initialRoomRevision(),
           request.initialProcessRevision(),
           request.initialRoomRevision(),
           reviewOutcomeStartBinding,
           evidenceParticipantBinding);
    }
    return new ActiveChildDescriptor(
        kind,
        request.selectionSchemaVersion(),
        request.writerMode(),
        request.caseWorkflowType(),
        request.caseWorkflowBuildId(),
        request.roomWorkflowType(),
        request.roomWorkflowBuildId(),
        request.roomType(),
        request.roomEpoch(),
        request.fencingToken(),
        request.roomWorkflowId(),
        startedRunId,
        initiatorActorScopeHash,
        respondentActorScopeHash);
  }

  private void validateProvisionRequest(ProvisionRoomEpoch request) {
    if (Boolean.FALSE.equals(provisioningEnabled)) {
      throw protocolFailure(
          "ROOM_EPOCH_PROVISIONING_UNAVAILABLE",
          "room epoch provisioning is unavailable for this workflow history");
    }
    if (request == null) {
      throw protocolFailure(
          "ROOM_EPOCH_PROVISIONING_INVALID", "provisioning request must not be null");
    }
    requireWorkflowIdentity(request.tenantSurrogate(), request.caseId());
    if (!Workflow.getInfo().getWorkflowId().equals(request.caseWorkflowId())) {
      throw protocolFailure(
          "ROOM_EPOCH_CASE_WORKFLOW_ID_MISMATCH",
          "provisioning case workflow id does not match the running workflow");
    }
    if (tenantSurrogate != null
        && (!tenantSurrogate.equals(request.tenantSurrogate())
            || !caseId.equals(request.caseId()))) {
      throw protocolFailure(
          "CASE_PROCESS_SCOPE_MISMATCH", "workflow received provisioning for another case");
    }
    if (!request.currentRoom().equals(request.roomType().name())) {
      throw protocolFailure(
          "ROOM_EPOCH_CURRENT_ROOM_MISMATCH", "provisioning currentRoom does not match roomType");
    }
  }

  private void validateProvisioningOrder(
      ProvisionRoomEpoch request, String updateId, String payloadSha256) {
    requireProvisioningReconciled();
    validateAgainstCommittedProvisioning(request, updateId, payloadSha256);
    long highestFence = highestCommittedFencingToken();
    long highestEpoch = highestCommittedEpoch(request.roomType());
    for (PendingProvisioning pending : pendingProvisioningByUpdateId.values()) {
      if (pending.updateId().equals(updateId)) {
        requireSameProvisioningPayload(updateId, payloadSha256, pending.payloadSha256());
        continue;
      }
      highestFence = Math.max(highestFence, pending.request().fencingToken());
      if (pending.request().roomType() == request.roomType()) {
        highestEpoch = Math.max(highestEpoch, pending.request().roomEpoch());
      }
      if (sameRoomTuple(pending.request(), request)) {
        throw protocolFailure(
            "ROOM_EPOCH_TUPLE_CONFLICT",
            "room tuple is already pending with another provisioning payload");
      }
    }
    if (request.fencingToken() <= highestFence) {
      throw protocolFailure(
          "ROOM_EPOCH_FENCING_TOKEN_STALE",
          "new provisioning fencing token must increase globally");
    }
    if (request.roomEpoch() <= highestEpoch) {
      throw protocolFailure(
          "ROOM_EPOCH_NOT_MONOTONIC", "room epoch must increase within its room type");
    }
  }

  private void validateAgainstCommittedProvisioning(
      ProvisionRoomEpoch request, String updateId, String payloadSha256) {
    ProvisioningCommitment sameUpdate = provisioningCommitments.get(updateId);
    if (sameUpdate != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, sameUpdate.payloadSha256());
      return;
    }
    for (ProvisioningCommitment commitment : provisioningCommitments.values()) {
      if (sameRoomTuple(commitment.request(), request)) {
        throw protocolFailure(
            "ROOM_EPOCH_TUPLE_CONFLICT", "room tuple is already committed with another update id");
      }
    }
    if (activeRoomType == request.roomType() && activeRoomEpoch == request.roomEpoch()) {
      throw protocolFailure(
          activeFencingToken == 0
              ? "ROOM_EPOCH_ALREADY_ACTIVE_LEGACY"
              : "ROOM_EPOCH_TUPLE_CONFLICT",
          "active room tuple cannot be reprovisioned");
    }
    if (request.fencingToken() <= highestCommittedFencingToken()) {
      throw protocolFailure(
          "ROOM_EPOCH_FENCING_TOKEN_STALE",
          "new provisioning fencing token must increase globally");
    }
    if (request.roomEpoch() <= highestCommittedEpoch(request.roomType())) {
      throw protocolFailure(
          "ROOM_EPOCH_NOT_MONOTONIC", "room epoch must increase within its room type");
    }
  }

  private long highestCommittedFencingToken() {
    long highest = activeFencingToken;
    for (ProvisioningCommitment commitment : provisioningCommitments.values()) {
      highest = Math.max(highest, commitment.request().fencingToken());
    }
    return highest;
  }

  private long highestCommittedEpoch(
      com.example.dispute.workflow.contract.v1.ContractTypes.RoomType roomType) {
    long highest = highestProvisionedEpochs.getOrDefault(roomType, -1L);
    if (activeRoomType == roomType) {
      highest = activeRoomEpoch;
    }
    for (ClosedRoomTuple closedRoom : closedRooms) {
      if (closedRoom.roomType() == roomType) {
        highest = Math.max(highest, closedRoom.roomEpoch());
      }
    }
    for (ProvisioningCommitment commitment : provisioningCommitments.values()) {
      if (commitment.request().roomType() == roomType) {
        highest = Math.max(highest, commitment.request().roomEpoch());
      }
    }
    return highest;
  }

  private static boolean sameRoomTuple(ProvisionRoomEpoch left, ProvisionRoomEpoch right) {
    return left.roomType() == right.roomType() && left.roomEpoch() == right.roomEpoch();
  }

  private void requireProvisioningReconciled() {
    requireProvisioningReconciled(provisioningManualRecoveryRequired);
  }

  static void requireProvisioningReconciled(boolean manualRecoveryRequired) {
    if (manualRecoveryRequired) {
      throw protocolFailure(
          "ROOM_EPOCH_CHILD_RECONCILIATION_REQUIRED",
          "room epoch provisioning is blocked until child compensation is reconciled");
    }
  }

  private static void requireSameProvisioningPayload(
      String updateId, String incomingSha256, String committedSha256) {
    if (!Objects.equals(incomingSha256, committedSha256)) {
      throw protocolFailure(
          "ROOM_EPOCH_UPDATE_ID_CONFLICT",
          "update id " + updateId + " is already bound to another payload");
    }
  }

  private static void requireExpectedProvisioningUpdateId(
      String updateId, ProvisionRoomEpoch request) {
    if (!updateId.equals(request.updateId())) {
      throw protocolFailure(
          "ROOM_EPOCH_UPDATE_ID_MISMATCH",
          "Temporal update id does not match the provisioning payload");
    }
  }

  private static String currentUpdateId() {
    return Workflow.getCurrentUpdateInfo()
        .map(info -> info.getUpdateId())
        .orElseThrow(
            () ->
                protocolFailure(
                    "ROOM_EPOCH_UPDATE_CONTEXT_MISSING",
                    "provisioning must execute as a Temporal Update"));
  }

  private ProvisioningCommitment currentProvisioningCommitment() {
    ProvisioningCommitment current = null;
    for (ProvisioningCommitment commitment : provisioningCommitments.values()) {
      ProvisionRoomEpoch request = commitment.request();
      if (request.fencingToken() == activeFencingToken
          && request.roomType() == activeRoomType
          && request.roomEpoch() == activeRoomEpoch) {
        current = commitment;
      }
    }
    return current;
  }

  private void restoreAuthorityCheckpoint() {
    if (!authorityCheckpointEnabled) {
      return;
    }
    ProvisioningCommitment commitment = currentProvisioningCommitment();
    if (commitment != null) {
      Workflow.upsertMemo(Map.of(AUTHORITY_CHECKPOINT_MEMO_KEY, commitment.receipt()));
    }
  }

  private void trimProvisioningCommitments() {
    while (provisioningCommitments.size() > CaseProcessCarryState.MAX_PROVISIONING_COMMITMENTS) {
      String oldest = provisioningCommitments.keySet().iterator().next();
      provisioningCommitments.remove(oldest);
    }
  }

  private void drainCommandInbox() {
    while (commandInboxCount > 0) {
      PendingCommand pending = commandInbox.poll();
      if (pending == null) {
        return;
      }
      commandInboxCount--;
      CaseCommandRef command = pending.command();
      bindIdentity(command.tenantSurrogate(), command.caseId());
      highestObservedCommandSequence =
          Math.max(highestObservedCommandSequence, command.caseCommandSequence());
      if (command.caseCommandSequence() < nextCommandSequence) {
        replayChecks.addLast(pending);
        continue;
      }
      mergePendingCommand(pending);
    }
  }

  private void drainEventInbox() {
    while (eventInboxCount > 0) {
      CaseDomainEventRef event = eventInbox.poll();
      if (event == null) {
        return;
      }
      eventInboxCount--;
      bindIdentity(event.tenantSurrogate(), event.caseId());
      highestObservedEventSequence =
          Math.max(highestObservedEventSequence, event.caseEventSequence());
      if (event.caseEventSequence() < nextCaseEventSequence) {
        continue;
      }
      mergeBufferedEvent(event);
    }
  }

  private boolean processReplayCheck() {
    PendingCommand pending = replayChecks.peekFirst();
    if (pending == null || commandManualRecoveryRequired) {
      return false;
    }
    try {
      List<CaseCommandLedgerEntry> stored =
          loadCommandLedgerEntries(
              range(
                  pending.command().caseCommandSequence(),
                  pending.command().caseCommandSequence()));
      if (stored == null || stored.size() != 1) {
        markManualRecovery(
            SequenceStream.COMMAND,
            pending.command().caseCommandSequence(),
            "COMMAND_LEDGER_RESPONSE_INVALID");
        return true;
      }
      try {
        validateLoadedCommand(
            stored.getFirst().command(),
            pending.command().caseCommandSequence(),
            pending.command().caseCommandSequence());
      } catch (RuntimeException invalidResponse) {
        markManualRecovery(
            SequenceStream.COMMAND,
            pending.command().caseCommandSequence(),
            "COMMAND_LEDGER_RESPONSE_INVALID");
        return true;
      }
      if (sameCommand(stored.getFirst().command(), pending.command())) {
        replayChecks.removeFirst();
        completeReplayFromLedger(pending, stored.getFirst().state());
        commandRecoveryAttempts = 0;
        commandManualRecoveryRequired = false;
        clearRecoveryError(SequenceStream.COMMAND);
        return true;
      }
      replayChecks.removeFirst();
      pending.fail(
          protocolFailure(
              "CASE_PROCESS_COMMAND_REPLAY_CONFLICT",
              "replayed command does not match the Java command ledger"));
      recordProtocolError(
          "CASE_PROCESS_COMMAND_REPLAY_CONFLICT", RecoveryErrorOrigin.COMMAND);
      commandRecoveryAttempts = 0;
      return true;
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
      failGapRecovery(
          SequenceStream.COMMAND,
          pending.command().caseCommandSequence(),
          "COMMAND_REPLAY_LEDGER_UNAVAILABLE");
      return true;
    }
  }

  private boolean processTargetIntakeTerminalNoCommit() {
    TargetIntakeCommandTerminalNoCommit authority = terminalNoCommitInbox.peekFirst();
    if (authority == null || commandManualRecoveryRequired) {
      return false;
    }
    try {
      requireTargetIntakeTerminalNoCommit(authority);
      ConvergeTargetIntakeTerminalNoCommitResult result =
          commandLifecycleActivities.convergeTargetIntakeTerminalNoCommit(
              new ConvergeTargetIntakeTerminalNoCommit(
                  "converge-target-intake-terminal-no-commit.v1",
                  authority,
                  Workflow.getInfo().getWorkflowId(),
                  Workflow.getInfo().getFirstExecutionRunId(),
                  activeChildDescriptor.caseWorkflowBuildId()));
      if (result == null
          || !authority.equals(result.authority())
          || result.processRevision() > observedProcessRevision
          || result.roomRevision() > activeRoomRevision
          || result.lastCommandSequence() >= nextCommandSequence
          || result.lastCaseEventSequence() >= nextCaseEventSequence) {
        throw new IllegalArgumentException(
            "terminal-no-commit convergence returned conflicting authority");
      }
      terminalNoCommitInbox.removeFirst();
      return true;
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
      if (!isNonRetryableActivityFailure(failure)) {
        throw failure;
      }
      recordProtocolError(
          "TARGET_INTAKE_TERMINAL_NO_COMMIT_REJECTED", RecoveryErrorOrigin.COMMAND);
      commandManualRecoveryRequired = true;
      return true;
    } catch (RuntimeException failure) {
      recordProtocolError(
          "TARGET_INTAKE_TERMINAL_NO_COMMIT_INVALID", RecoveryErrorOrigin.COMMAND);
      commandManualRecoveryRequired = true;
      return true;
    }
  }

  private void requireTargetIntakeTerminalNoCommit(
      TargetIntakeCommandTerminalNoCommit authority) {
    ActiveChildDescriptor descriptor = activeChildDescriptor;
    if (tenantSurrogate == null
        || caseId == null
        || descriptor == null
        || descriptor.kind() != ActiveChildKind.TARGET_TYPED_ROOM
        || descriptor.writerMode() != WriterMode.TEMPORAL
        || activeRoomType != RoomType.INTAKE
        || descriptor.roomType() != RoomType.INTAKE
        || !tenantSurrogate.equals(authority.tenantSurrogate())
        || !caseId.equals(authority.caseId())
        || authority.roomType() != RoomType.INTAKE
        || activeRoomEpoch != authority.roomEpoch()
        || activeFencingToken != authority.fencingToken()
        || !activeChildWorkflowId.equals(authority.roomWorkflowId())
        || !activeChildWorkflowRunId.equals(authority.roomWorkflowRunId())
        || !descriptor.roomWorkflowBuildId().equals(authority.roomWorkflowBuildId())
        || !descriptor.caseWorkflowBuildId().equals(authority.caseBuildId())
        || descriptor.currentProcessRevision() == null
        || descriptor.currentRoomRevision() == null
        || descriptor.currentProcessRevision() != observedProcessRevision
        || descriptor.currentRoomRevision() != activeRoomRevision
        || observedProcessRevision < authority.newProcessRevision()
        || activeRoomRevision < authority.newRoomRevision()
        || authority.caseCommandSequence() >= nextCommandSequence
        || authority.lastCaseEventSequence() >= nextCaseEventSequence) {
      throw new IllegalArgumentException(
          "terminal-no-commit authority conflicts with active CaseProcess state");
    }
    ProcessedCommandIdentity recent = recentCommands.get(authority.commandId());
    if (recent != null
        && (recent.caseCommandSequence() != authority.caseCommandSequence()
            || !recent.requestHash().equals(authority.commandRequestHash()))) {
      throw new IllegalArgumentException(
          "terminal-no-commit authority conflicts with recent command identity");
    }
  }

  private boolean processNextCommand() {
    if (commandManualRecoveryRequired) {
      return false;
    }
    PendingCommand pending = orderedCommands.remove(nextCommandSequence);
    if (pending == null) {
      return false;
    }
    try {
      CaseCommandRef command = pending.command();
      validateProvisionedCommand(command, pending.authoritativeLedgerState());
      boolean commandLifecycleEnabled = commandLifecycleEnabled();
      if (!pending.ledgerState().routable()) {
        if (pending.ledgerState() == CaseCommandLedgerState.APPLIED) {
          recoverAppliedTargetTerminal(command);
        }
        consumeTerminalCommand(pending);
        return true;
      }
      if (commandLifecycleEnabled && deadlineElapsed(command, workflowNow())) {
        expireCommand(pending, workflowNow());
        return true;
      }
      RecordCaseCommandRouted routing = null;
      if (commandLifecycleEnabled) {
        routing = routing(command, workflowNow());
        RecordCaseCommandRoutedResult admission =
            commandLifecycleActivities.recordCaseCommandRouted(routing);
        CaseCommandLedgerState admissionTerminal = terminalLedgerState(admission.outcome());
        if (admissionTerminal != null) {
          if (admissionTerminal == CaseCommandLedgerState.APPLIED) {
            recoverAppliedTargetTerminal(command);
          }
          consumeTerminalCommand(pending, admissionTerminal);
          return true;
        }
      }
      ensureRoomChild(command);
      routeCommandToActiveChild(command);
      if (commandLifecycleEnabled) {
        RecordCaseCommandRoutedResult completion =
            commandLifecycleActivities.completeCaseCommandRouting(routing);
        CaseCommandLedgerState completionTerminal = terminalLedgerState(completion.outcome());
        if (completionTerminal != null && !completionTerminal.successfulTerminal()) {
          consumeTerminalCommand(pending, completionTerminal);
          return true;
        }
        completeTargetChildAfterDurableRouting(command);
      }
      observedProcessRevision =
          Math.max(observedProcessRevision, Math.incrementExact(command.expectedProcessRevision()));
      recentCommands.put(
          command.commandId(),
          new ProcessedCommandIdentity(
              command.commandId(), command.caseCommandSequence(), command.requestHash()));
      trimRecentCommands();
      nextCommandSequence++;
      processedCommandCount++;
      commandRecoveryAttempts = 0;
      commandManualRecoveryRequired = false;
      clearRecoveryError(SequenceStream.COMMAND);
      pending.complete();
    } catch (TypedChildOperationFailure failure) {
      orderedCommands.put(nextCommandSequence, pending);
      recordProtocolError(
          failure.errorCode(), typedFailureOrigin(failure.errorCode(), SequenceStream.COMMAND));
      commandManualRecoveryRequired = true;
      pending.fail(protocolFailure(failure.errorCode(), failure.getMessage()));
    } catch (ActivityFailure failure) {
      orderedCommands.put(nextCommandSequence, pending);
      rethrowIfCanceled(failure);
      if (isNonRetryableActivityFailure(failure)) {
        recordProtocolError(
            "CASE_PROCESS_COMMAND_LIFECYCLE_REJECTED", RecoveryErrorOrigin.COMMAND);
        commandManualRecoveryRequired = true;
        pending.fail(
            protocolFailure(
                "CASE_PROCESS_COMMAND_LIFECYCLE_REJECTED",
                "command lifecycle validation requires manual recovery"));
        return true;
      }
      recordProtocolError("CASE_PROCESS_COMMAND_ROUTING_FAILED", RecoveryErrorOrigin.COMMAND);
      throw failure;
    } catch (SignalExternalWorkflowException failure) {
      recordProtocolError("CASE_PROCESS_ROOM_ROUTING_FAILED", RecoveryErrorOrigin.COMMAND);
      commandManualRecoveryRequired = true;
      pending.fail(
          protocolFailure(
              "CASE_PROCESS_ROOM_ROUTING_FAILED",
              "room child workflow could not accept the command"));
    } catch (ApplicationFailure failure) {
      if (!failure.isNonRetryable()) {
        recordProtocolError("CASE_PROCESS_ROOM_ROUTING_FAILED", RecoveryErrorOrigin.COMMAND);
        commandManualRecoveryRequired = true;
        pending.fail(
            protocolFailure(
                "CASE_PROCESS_ROOM_ROUTING_FAILED",
                "room child workflow could not accept the command"));
        return true;
      }
      recordProtocolError(failure.getType(), RecoveryErrorOrigin.COMMAND);
      commandManualRecoveryRequired = true;
      pending.fail(failure);
    } catch (TemporalFailure failure) {
      if (failure instanceof CanceledFailure) {
        throw failure;
      }
      recordProtocolError("CASE_PROCESS_ROOM_ROUTING_FAILED", RecoveryErrorOrigin.COMMAND);
      commandManualRecoveryRequired = true;
      pending.fail(
          protocolFailure(
              "CASE_PROCESS_ROOM_ROUTING_FAILED",
              "room child workflow could not accept the command"));
    } catch (RuntimeException exception) {
      orderedCommands.put(nextCommandSequence, pending);
      recordProtocolError("CASE_PROCESS_COMMAND_ROUTING_FAILED", RecoveryErrorOrigin.COMMAND);
      throw exception;
    }
    return true;
  }

  private void consumeTerminalCommand(PendingCommand pending) {
    consumeTerminalCommand(pending, pending.ledgerState());
  }

  private void consumeTerminalCommand(PendingCommand pending, CaseCommandLedgerState ledgerState) {
    CaseCommandRef command = pending.command();
    recentCommands.put(
        command.commandId(),
        new ProcessedCommandIdentity(
            command.commandId(), command.caseCommandSequence(), command.requestHash()));
    trimRecentCommands();
    nextCommandSequence++;
    processedCommandCount++;
    commandRecoveryAttempts = 0;
    commandManualRecoveryRequired = false;
    clearRecoveryError(SequenceStream.COMMAND);
    if (ledgerState.successfulTerminal()) {
      pending.complete();
    } else {
      pending.fail(terminalCommandFailure(ledgerState));
    }
  }

  private void completeReplayFromLedger(
      PendingCommand pending, CaseCommandLedgerState ledgerState) {
    if (ledgerState.routable() || ledgerState.successfulTerminal()) {
      pending.complete();
      return;
    }
    pending.fail(terminalCommandFailure(ledgerState));
  }

  private static ApplicationFailure terminalCommandFailure(CaseCommandLedgerState ledgerState) {
    if (ledgerState == CaseCommandLedgerState.EXPIRED) {
      return protocolFailure(
          "CASE_PROCESS_COMMAND_DEADLINE_EXPIRED",
          "command is already terminally expired in the Java ledger");
    }
    return protocolFailure(
        "CASE_PROCESS_COMMAND_TERMINAL",
        "command is already terminal in the Java ledger: " + ledgerState.name());
  }

  private void expireCommand(PendingCommand pending, Instant expiredAt) {
    CaseCommandRef command = pending.command();
    var result =
        commandLifecycleActivities.expireCaseCommand(
            new ExpireCaseCommand(
                "expire-case-command.v1",
                command.tenantSurrogate(),
                command.caseId(),
                command.commandId(),
                command.caseCommandSequence(),
                command.requestHash(),
                command.deadlineAt(),
                expiredAt,
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId()));
    CaseCommandLedgerState terminal = terminalLedgerState(result.outcome());
    if (terminal == null) {
      throw protocolFailure(
          "CASE_PROCESS_COMMAND_LIFECYCLE_INVALID",
          "expiration Activity returned a non-terminal outcome");
    }
    consumeTerminalCommand(pending, terminal);
  }

  private RecordCaseCommandRouted routing(CaseCommandRef command, Instant routedAt) {
    return new RecordCaseCommandRouted(
        "record-case-command-routed.v1",
        command.tenantSurrogate(),
        command.caseId(),
        command.commandId(),
        command.caseCommandSequence(),
        command.requestHash(),
        command.roomType(),
        command.roomEpoch(),
        routedAt,
        Workflow.getInfo().getWorkflowId(),
        Workflow.getInfo().getRunId());
  }

  private static CaseCommandLedgerState terminalLedgerState(CommandLifecycleOutcome outcome) {
    return switch (outcome) {
      case ORCHESTRATION_ACCEPTED -> null;
      case SHADOW_COMPLETED, ALREADY_SHADOW_COMPLETED -> CaseCommandLedgerState.SHADOW_COMPLETED;
      case EXPIRED, ALREADY_EXPIRED -> CaseCommandLedgerState.EXPIRED;
      case ALREADY_APPLIED -> CaseCommandLedgerState.APPLIED;
      case ALREADY_REJECTED -> CaseCommandLedgerState.REJECTED;
      case ALREADY_FAILED -> CaseCommandLedgerState.FAILED;
    };
  }

  private static boolean commandLifecycleEnabled() {
    return Workflow.getVersion(COMMAND_DEADLINE_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
  }

  private static Instant workflowNow() {
    return Instant.ofEpochMilli(Workflow.currentTimeMillis());
  }

  private static boolean deadlineElapsed(CaseCommandRef command, Instant now) {
    return !command.deadlineAt().isAfter(now);
  }

  private boolean processNextEvent() {
    if (eventManualRecoveryRequired) {
      return false;
    }
    CaseDomainEventRef event = bufferedEvents.get(nextCaseEventSequence);
    if (event == null || waitsForFutureRoom(event)) {
      return false;
    }
    boolean globalTargetIntakeProjectionCandidate =
        isGlobalTargetIntakeProjectionReadyCandidate(
            activeChildDescriptor, activeRoomType, activeRoomEpoch, event);
    int globalTargetIntakeProjectionVersion =
        globalTargetIntakeProjectionCandidate
            ? Workflow.getVersion(
                TARGET_INTAKE_GLOBAL_PROJECTION_CURSOR_CHANGE_ID,
                Workflow.DEFAULT_VERSION,
                1)
            : Workflow.DEFAULT_VERSION;
    boolean globalTargetIntakeProjection =
        routesGlobalTargetIntakeProjectionReady(
            globalTargetIntakeProjectionVersion,
            activeChildDescriptor,
            activeRoomType,
            activeRoomEpoch,
            event);
    if (globalTargetIntakeProjection
        || (hasActiveChild()
            && activeRoomType == event.roomType()
            && activeRoomEpoch == event.roomEpoch())) {
      try {
        if (globalTargetIntakeProjection) {
          routeGlobalTargetIntakeProjectionReady(event);
        } else {
          routeEventToActiveChild(event);
        }
        completeTargetIntakeProjection(event);
      } catch (TypedChildOperationFailure failure) {
        recordProtocolError(
            failure.errorCode(), typedFailureOrigin(failure.errorCode(), SequenceStream.DOMAIN_EVENT));
        eventManualRecoveryRequired = true;
        return true;
      } catch (CanceledFailure failure) {
        throw failure;
      } catch (ActivityFailure failure) {
        rethrowIfCanceled(failure);
        recordProtocolError(
            "INTAKE_PROCESS_PROJECTION_COMPLETION_FAILED",
            RecoveryErrorOrigin.DOMAIN_EVENT);
        eventManualRecoveryRequired = true;
        return true;
      } catch (SignalExternalWorkflowException | TemporalFailure failure) {
        recordProtocolError(
            "CASE_PROCESS_ROOM_EVENT_ROUTING_FAILED", RecoveryErrorOrigin.DOMAIN_EVENT);
        eventManualRecoveryRequired = true;
        return true;
      }
    }
    bufferedEvents.remove(nextCaseEventSequence);
    nextCaseEventSequence++;
    processedEventCount++;
    eventRecoveryAttempts = 0;
    eventManualRecoveryRequired = false;
    if (nextCaseEventSequence > highestObservedEventSequence) {
      eventRecoveryForced = false;
    }
    clearRecoveryError(SequenceStream.DOMAIN_EVENT);
    return true;
  }

  private void completeTargetIntakeProjection(CaseDomainEventRef event) {
    if (!requiresTargetIntakeProjectionCompletion(
        targetIntakeProjectionCompletionVersion,
        activeChildDescriptor,
        activeRoomType,
        activeRoomEpoch,
        event)) {
      return;
    }
    CompleteConsumedIntakeProjectionCommand command =
        targetIntakeProjectionCompletionCommand(event);
    CompleteConsumedIntakeProjectionResult completed =
        processProjectionActivities.completeConsumedIntakeProjection(command);
    if (!consumedIntakeProjectionResultMatches(command, completed)) {
      throw new TypedChildOperationFailure(
          "INTAKE_PROCESS_PROJECTION_COMPLETION_INVALID",
          "projection completion receipt does not match the consumed Intake event",
          null);
    }
    observeTargetIntakeProjectionReadyHighWater(completed);
  }

  private CompleteConsumedIntakeProjectionCommand targetIntakeProjectionCompletionCommand(
      CaseDomainEventRef event) {
    return new CompleteConsumedIntakeProjectionCommand(
        "complete-consumed-intake-projection.v1",
        tenantSurrogate,
        caseId,
        event.eventId(),
        event.caseEventSequence(),
        event.eventType(),
        Math.max(0, nextCommandSequence - 1),
        activeRoomEpoch,
        activeFencingToken,
        observedProcessRevision,
        activeRoomRevision,
        Workflow.getInfo().getWorkflowId(),
        Workflow.getInfo().getFirstExecutionRunId(),
        activeChildWorkflowRunId);
  }

  private void observeTargetIntakeProjectionReadyHighWater(
      CompleteConsumedIntakeProjectionResult completed) {
    if (completed.readyEventId() == null && completed.readyEventSequence() == null) {
      return;
    }
    if (completed.readyEventId() == null || completed.readyEventSequence() == null) {
      throw new TypedChildOperationFailure(
          "INTAKE_PROCESS_PROJECTION_COMPLETION_INVALID",
          "projection completion receipt contains an incomplete ready event cursor",
          null);
    }
    if (Workflow.getVersion(
            TARGET_INTAKE_PROJECTION_READY_HIGH_WATER_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            1)
        != 1) {
      return;
    }
    highestObservedEventSequence =
        Math.max(highestObservedEventSequence, completed.readyEventSequence());
  }

  static boolean requiresTargetIntakeProjectionCompletion(
      int version,
      ActiveChildDescriptor descriptor,
      RoomType roomType,
      long roomEpoch,
      CaseDomainEventRef event) {
    return version == 1
        && descriptor != null
        && descriptor.kind() == ActiveChildKind.TARGET_TYPED_ROOM
        && roomType == RoomType.INTAKE
        && descriptor.roomType() == RoomType.INTAKE
        && event != null
        && event.roomType() == RoomType.INTAKE
        && event.roomEpoch() == roomEpoch
        && isFormalIntakeProjectionEvent(event.eventType());
  }

  static boolean routesGlobalTargetIntakeProjectionReady(
      int version,
      ActiveChildDescriptor descriptor,
      RoomType activeRoomType,
      long activeRoomEpoch,
      CaseDomainEventRef event) {
    return version == 1
        && isGlobalTargetIntakeProjectionReadyCandidate(
            descriptor, activeRoomType, activeRoomEpoch, event);
  }

  private static boolean isGlobalTargetIntakeProjectionReadyCandidate(
      ActiveChildDescriptor descriptor,
      RoomType activeRoomType,
      long activeRoomEpoch,
      CaseDomainEventRef event) {
    return descriptor != null
        && descriptor.kind() == ActiveChildKind.TARGET_TYPED_ROOM
        && activeRoomType == RoomType.INTAKE
        && descriptor.roomType() == RoomType.INTAKE
        && descriptor.roomEpoch() == activeRoomEpoch
        && event != null
        && event.roomType() == null
        && event.roomEpoch() == 0
        && "INTAKE_PROJECTION_READY".equals(event.eventType());
  }

  static boolean consumedIntakeProjectionResultMatches(
      CompleteConsumedIntakeProjectionCommand command,
      CompleteConsumedIntakeProjectionResult result) {
    return result != null
        && command.eventId().equals(result.eventId())
        && command.caseEventSequence() == result.caseEventSequence()
        && command.lastCommandSequence() == result.lastCommandSequence()
        && command.processRevision() == result.processRevision()
        && command.roomRevision() == result.roomRevision()
        && command.roomEpoch() == result.roomEpoch()
        && command.fencingToken() == result.fencingToken()
        && command.temporalWorkflowId().equals(result.temporalWorkflowId())
        && command.firstExecutionRunId().equals(result.firstExecutionRunId())
        && command.activeChildRunId().equals(result.activeChildRunId());
  }

  private void requireIntakeProjectionRecovery(
      CaseProcessIntakeProjectionRecoveryRequest request, boolean handlerRevalidation) {
    Objects.requireNonNull(request, "request must not be null");
    if (completedIntakeProjectionRecoveryRequest != null
        || completedIntakeProjectionRecoveryResult != null) {
      if (!request.equals(completedIntakeProjectionRecoveryRequest)
          || completedIntakeProjectionRecoveryResult == null) {
        throw new IllegalArgumentException("Intake projection recovery replay conflicts");
      }
      return;
    }
    if (activeIntakeProjectionRecovery != null
        && (!handlerRevalidation || !activeIntakeProjectionRecovery.equals(request))) {
      throw new IllegalStateException("Intake projection recovery is already running");
    }
    if (tenantSurrogate == null || caseId == null) {
      throw new IllegalStateException("Intake projection recovery requires an initialized case");
    }
    if (terminalTargetReviewCompleted) {
      throw new IllegalStateException("Intake projection recovery requires a nonterminal case");
    }
    if (targetIntakeProjectionCompletionVersion != 1) {
      throw new IllegalStateException("Intake projection recovery requires v1 completion authority");
    }
    if (!eventManualRecoveryRequired
        || !"INTAKE_PROCESS_PROJECTION_COMPLETION_FAILED".equals(protocolErrorCode)
        || protocolErrorOrigin != RecoveryErrorOrigin.DOMAIN_EVENT) {
      throw new IllegalStateException(
          "Intake projection recovery requires the exact projection failure state");
    }
    if (commandManualRecoveryRequired
        || provisioningManualRecoveryRequired
        || retrySequenceGapRequested
        || provisioningSwitchInProgress
        || provisioningInboxCount > 0
        || !pendingProvisioningByUpdateId.isEmpty()
        || commandInboxCount > 0
        || !orderedCommands.isEmpty()
        || !replayChecks.isEmpty()) {
      throw new IllegalStateException(
          "Intake projection recovery requires idle command and provisioning authority");
    }
    ActiveChildDescriptor descriptor = activeChildDescriptor;
    if (descriptor == null
        || descriptor.kind() != ActiveChildKind.TARGET_TYPED_ROOM
        || descriptor.writerMode() != WriterMode.TEMPORAL
        || descriptor.roomType() != RoomType.INTAKE
        || descriptor.roomEpoch() != activeRoomEpoch
        || descriptor.fencingToken() != activeFencingToken
        || !Objects.equals(descriptor.workflowId(), activeChildWorkflowId)
        || !Objects.equals(descriptor.startedRunId(), activeChildWorkflowRunId)
        || !Objects.equals(descriptor.currentProcessRevision(), observedProcessRevision)
        || !Objects.equals(descriptor.currentRoomRevision(), activeRoomRevision)
        || activeRoomType != RoomType.INTAKE) {
      throw new IllegalStateException(
          "Intake projection recovery active child authority is unavailable");
    }
    if (!Workflow.getInfo().getWorkflowId().equals(request.workflowId())
        || !Workflow.getInfo().getRunId().equals(request.workflowRunId())
        || !Workflow.getInfo().getFirstExecutionRunId().equals(request.firstExecutionRunId())
        || !tenantSurrogate.equals(request.tenantSurrogate())
        || !caseId.equals(request.caseId())
        || request.roomType() != activeRoomType
        || request.roomEpoch() != activeRoomEpoch
        || request.fencingToken() != activeFencingToken
        || !activeChildWorkflowId.equals(request.activeChildWorkflowId())
        || !activeChildWorkflowRunId.equals(request.activeChildRunId())
        || request.expectedProcessRevision() != observedProcessRevision
        || request.expectedRoomRevision() != activeRoomRevision
        || request.nextCommandSequence() != nextCommandSequence
        || request.nextCaseEventSequence() != nextCaseEventSequence
        || request.processedCommandCount() != processedCommandCount
        || request.processedEventCount() != processedEventCount) {
      throw new IllegalArgumentException(
          "Intake projection recovery request does not match current workflow authority");
    }
    CaseDomainEventRef head = bufferedEvents.get(nextCaseEventSequence);
    if (!request.event().equals(head)
        || !requiresTargetIntakeProjectionCompletion(
            targetIntakeProjectionCompletionVersion,
            descriptor,
            activeRoomType,
            activeRoomEpoch,
            head)) {
      throw new IllegalArgumentException(
          "Intake projection recovery does not match the buffered formal event");
    }
    CompleteConsumedIntakeProjectionCommand expectedCommand =
        targetIntakeProjectionCompletionCommand(head);
    if (!expectedCommand.equals(request.projectionCommand())) {
      throw new IllegalArgumentException(
          "Intake projection recovery command does not match current authority");
    }
    ProcessedCommandIdentity recent =
        recentCommands.get(request.recentCommand().commandId());
    long matchingSequenceCount =
        recentCommands.values().stream()
            .filter(
                identity ->
                    identity.caseCommandSequence()
                        == request.recentCommand().caseCommandSequence())
            .count();
    if (!request.recentCommand().equals(recent)
        || request.recentCommand().caseCommandSequence() != nextCommandSequence - 1
        || matchingSequenceCount != 1) {
      throw new IllegalArgumentException(
          "Intake projection recovery recent command authority is unavailable");
    }
  }

  private void clearIntakeProjectionRecoveryError() {
    if (!"INTAKE_PROCESS_PROJECTION_COMPLETION_FAILED".equals(protocolErrorCode)
        || protocolErrorOrigin != RecoveryErrorOrigin.DOMAIN_EVENT) {
      throw new IllegalStateException("Intake projection recovery error authority changed");
    }
    protocolErrorCode = null;
    protocolErrorOrigin = null;
  }

  private void awaitNoActiveIntakeProjectionRecovery() {
    if (activeIntakeProjectionRecovery != null) {
      Workflow.await(() -> activeIntakeProjectionRecovery == null);
    }
  }

  private void requireNoActiveIntakeProjectionRecovery() {
    if (activeIntakeProjectionRecovery != null) {
      throw new IllegalStateException("Intake projection recovery is already running");
    }
  }

  private static boolean isFormalIntakeProjectionEvent(String eventType) {
    return "TURN_NEEDS_INPUT".equals(eventType)
        || "INTAKE_TURN_NEEDS_INPUT".equals(eventType)
        || "TURN_READY_TO_CONFIRM".equals(eventType)
        || "INTAKE_TURN_READY_TO_CONFIRM".equals(eventType);
  }

  private void routeEventToActiveChild(CaseDomainEventRef event) {
    if (activeChildDescriptor.kind() == ActiveChildKind.GENERIC_ROOM_CONTROL) {
      if (activeRoomChild == null) {
        throw new TypedChildOperationFailure(
            "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
            "generic active child stub is missing",
            null);
      }
      Async.procedure(activeRoomChild::domainEventCommitted, event).get();
      return;
    }
    if (activeChildDescriptor.kind() == ActiveChildKind.TARGET_TYPED_ROOM) {
      validateTargetTypedDescriptor(activeChildDescriptor);
      validateTargetTypedActiveRevisions();
      if (activeTargetTypedChild == null) {
        throw new TypedChildOperationFailure(
            "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID",
            "target typed active child handle is missing",
            null);
      }
      try {
        applyTargetTypedRoomReceipt(activeTargetTypedChild.domainEventCommitted(event));
      } catch (TypedChildOperationFailure failure) {
        throw failure;
      } catch (CanceledFailure failure) {
        throw failure;
      } catch (RuntimeException failure) {
        throw new TypedChildOperationFailure(
            "TARGET_TYPED_ROOM_EVENT_DISPATCH_FAILED",
            "target typed child could not accept the domain event",
            failure);
      }
      return;
    }
    ActiveChildBinding expected = activeBinding();
    if (activeIntakeChild == null) {
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
          "typed Intake active child stub is missing",
          null);
    }
    DomainEventBinding binding;
    try {
      binding =
          bindIntakeChildDomainEvent(
              new DomainEventRequest("intake-child-domain-event-request.v1", event, expected));
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_BRIDGE_EVENT_FAILED",
          "typed Intake domain event binding Activity failed",
          failure);
    }
    validateDomainEventBinding(binding, event, expected);
    try {
      Async.procedure(activeIntakeChild::domainEventCommitted, binding.event()).get();
      observedProcessRevision = Math.max(observedProcessRevision, binding.processRevision());
      activeRoomRevision = binding.roomRevision();
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (SignalExternalWorkflowException | TemporalFailure failure) {
      throw new TypedChildOperationFailure(
          "INTAKE_CHILD_EVENT_SIGNAL_FAILED",
          "typed Intake child could not accept the domain event",
          failure);
    }
  }

  private void routeGlobalTargetIntakeProjectionReady(CaseDomainEventRef event) {
    validateTargetTypedDescriptor(activeChildDescriptor);
    validateTargetTypedActiveRevisions();
    if (activeTargetTypedChild == null) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID",
          "target typed active child handle is missing",
          null);
    }
    try {
      applyTargetTypedRoomReceipt(
          activeTargetTypedChild.globalIntakeProjectionReady(event));
    } catch (TypedChildOperationFailure failure) {
      throw failure;
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new TypedChildOperationFailure(
          "TARGET_TYPED_ROOM_EVENT_DISPATCH_FAILED",
          "target Intake child could not accept the global projection cursor",
          failure);
    }
  }

  private boolean waitsForFutureRoom(CaseDomainEventRef event) {
    if (!isRoomMismatch(event) || !futureRoomEventRetentionEnabled()) {
      return false;
    }
    ClosedRoomTuple eventRoom = new ClosedRoomTuple(event.roomType(), event.roomEpoch());
    return !closedRooms.contains(eventRoom);
  }

  private boolean canProcessNextEvent() {
    CaseDomainEventRef event = bufferedEvents.get(nextCaseEventSequence);
    if (event == null) {
      return false;
    }
    if (!isRoomMismatch(event) || futureRoomEventRetentionEnabled == null) {
      return true;
    }
    return !futureRoomEventRetentionEnabled
        || closedRooms.contains(new ClosedRoomTuple(event.roomType(), event.roomEpoch()));
  }

  private boolean isRoomMismatch(CaseDomainEventRef event) {
    return event.roomType() != null
        && (!hasActiveChild()
            || activeRoomType != event.roomType()
            || activeRoomEpoch != event.roomEpoch());
  }

  private boolean futureRoomEventRetentionEnabled() {
    if (futureRoomEventRetentionEnabled == null) {
      futureRoomEventRetentionEnabled =
          Workflow.getVersion(FUTURE_ROOM_EVENT_RETENTION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
              == 1;
    }
    return futureRoomEventRetentionEnabled;
  }

  private boolean recoverCommandGap() {
    if (commandManualRecoveryRequired || tenantSurrogate == null || !hasCommandGap()) {
      return false;
    }
    long toSequence =
        Math.min(highestObservedCommandSequence, nextCommandSequence + LOAD_BATCH_SIZE - 1L);
    try {
      List<CaseCommandLedgerEntry> loaded =
          loadCommandLedgerEntries(range(nextCommandSequence, toSequence));
      boolean progress = mergeLoadedCommands(loaded, nextCommandSequence, toSequence);
      if (commandManualRecoveryRequired) {
        return true;
      } else if (progress) {
        commandRecoveryAttempts = 0;
      } else {
        failGapRecovery(
            SequenceStream.COMMAND, highestObservedCommandSequence, "COMMAND_SEQUENCE_NOT_FOUND");
      }
      return true;
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
      failGapRecovery(
          SequenceStream.COMMAND, highestObservedCommandSequence, "COMMAND_LEDGER_UNAVAILABLE");
      return true;
    }
  }

  private boolean recoverEventGap() {
    if (eventManualRecoveryRequired || tenantSurrogate == null || !hasEventGap()) {
      return false;
    }
    long toSequence =
        Math.min(highestObservedEventSequence, nextCaseEventSequence + LOAD_BATCH_SIZE - 1L);
    try {
      List<CaseDomainEventRef> loaded =
          ledgerActivities.loadDomainEvents(range(nextCaseEventSequence, toSequence));
      boolean progress = mergeLoadedEvents(loaded, nextCaseEventSequence, toSequence);
      if (eventManualRecoveryRequired) {
        return true;
      } else if (progress) {
        eventRecoveryAttempts = 0;
      } else {
        failGapRecovery(
            SequenceStream.DOMAIN_EVENT,
            highestObservedEventSequence,
            "DOMAIN_EVENT_SEQUENCE_NOT_FOUND");
      }
      return true;
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
      failGapRecovery(
          SequenceStream.DOMAIN_EVENT,
          highestObservedEventSequence,
          "DOMAIN_EVENT_LEDGER_UNAVAILABLE");
      return true;
    }
  }

  private boolean mergeLoadedCommands(
      List<CaseCommandLedgerEntry> loaded, long fromSequence, long toSequence) {
    if (loaded == null || loaded.size() > toSequence - fromSequence + 1) {
      markManualRecovery(SequenceStream.COMMAND, toSequence, "COMMAND_LEDGER_RESPONSE_INVALID");
      return false;
    }
    List<CaseCommandLedgerEntry> ordered;
    try {
      ordered =
          loaded.stream()
              .sorted(Comparator.comparingLong(entry -> entry.command().caseCommandSequence()))
              .toList();
      long previousSequence = -1;
      for (CaseCommandLedgerEntry entry : ordered) {
        CaseCommandRef command = entry.command();
        validateLoadedCommand(command, fromSequence, toSequence);
        if (command.caseCommandSequence() == previousSequence) {
          throw new IllegalArgumentException("command ledger returned a duplicate sequence");
        }
        previousSequence = command.caseCommandSequence();
      }
    } catch (RuntimeException invalidResponse) {
      markManualRecovery(SequenceStream.COMMAND, toSequence, "COMMAND_LEDGER_RESPONSE_INVALID");
      return false;
    }
    ordered.forEach(
        entry -> {
          CaseCommandRef command = entry.command();
          highestObservedCommandSequence =
              Math.max(highestObservedCommandSequence, command.caseCommandSequence());
          mergePendingCommand(PendingCommand.recovered(command, entry.state()));
        });
    return orderedCommands.containsKey(nextCommandSequence);
  }

  private List<CaseCommandLedgerEntry> loadCommandLedgerEntries(LoadSequenceRange request) {
    if (commandLedgerStateEnabled()) {
      return ledgerActivities.loadCaseCommandLedgerEntries(request);
    }
    List<CaseCommandRef> legacy = ledgerActivities.loadCaseCommands(request);
    if (legacy == null) {
      return null;
    }
    return legacy.stream()
        .map(
            command ->
                new CaseCommandLedgerEntry(
                    "case-command-ledger-entry.v1",
                    command,
                    CaseCommandLedgerState.PENDING_ORCHESTRATION))
        .toList();
  }

  private static boolean commandLedgerStateEnabled() {
    return Workflow.getVersion(COMMAND_LEDGER_STATE_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
  }

  private boolean mergeLoadedEvents(
      List<CaseDomainEventRef> loaded, long fromSequence, long toSequence) {
    if (loaded == null || loaded.size() > toSequence - fromSequence + 1) {
      markManualRecovery(
          SequenceStream.DOMAIN_EVENT, toSequence, "DOMAIN_EVENT_LEDGER_RESPONSE_INVALID");
      return false;
    }
    List<CaseDomainEventRef> ordered;
    try {
      ordered =
          loaded.stream()
              .sorted(Comparator.comparingLong(CaseDomainEventRef::caseEventSequence))
              .toList();
      long previousSequence = -1;
      for (CaseDomainEventRef event : ordered) {
        validateLoadedEvent(event, fromSequence, toSequence);
        if (event.caseEventSequence() == previousSequence) {
          throw new IllegalArgumentException("domain event ledger returned a duplicate sequence");
        }
        previousSequence = event.caseEventSequence();
      }
    } catch (RuntimeException invalidResponse) {
      markManualRecovery(
          SequenceStream.DOMAIN_EVENT, toSequence, "DOMAIN_EVENT_LEDGER_RESPONSE_INVALID");
      return false;
    }
    ordered.forEach(
        event -> {
          highestObservedEventSequence =
              Math.max(highestObservedEventSequence, event.caseEventSequence());
          mergeBufferedEvent(event);
        });
    return bufferedEvents.containsKey(nextCaseEventSequence);
  }

  private void mergePendingCommand(PendingCommand incoming) {
    long sequence = incoming.command().caseCommandSequence();
    PendingCommand existing = orderedCommands.get(sequence);
    if (existing == null) {
      orderedCommands.put(sequence, incoming);
      return;
    }
    if (!sameCommand(existing.command(), incoming.command())) {
      incoming.fail(
          protocolFailure(
              "CASE_PROCESS_COMMAND_SEQUENCE_CONFLICT",
              "one command sequence is bound to different commands"));
      recordProtocolError(
          "CASE_PROCESS_COMMAND_SEQUENCE_CONFLICT", RecoveryErrorOrigin.COMMAND);
      commandManualRecoveryRequired = true;
      return;
    }
    existing.absorb(incoming);
  }

  private void mergeBufferedEvent(CaseDomainEventRef incoming) {
    long sequence = incoming.caseEventSequence();
    CaseDomainEventRef existing = bufferedEvents.get(sequence);
    if (existing != null) {
      if (!sameEvent(existing, incoming)) {
        recordProtocolError(
            "CASE_PROCESS_EVENT_SEQUENCE_CONFLICT", RecoveryErrorOrigin.DOMAIN_EVENT);
        eventManualRecoveryRequired = true;
      }
      return;
    }
    if (bufferedEvents.size() >= CaseProcessCarryState.MAX_BUFFERED_EVENTS) {
      Map.Entry<Long, CaseDomainEventRef> last = bufferedEvents.lastEntry();
      if (last != null && sequence < last.getKey()) {
        bufferedEvents.pollLastEntry();
        bufferedEvents.put(sequence, incoming);
      } else {
        eventRecoveryForced = true;
      }
      return;
    }
    bufferedEvents.put(sequence, incoming);
  }

  private void ensureRoomChild(CaseCommandRef command) {
    String desiredChildId =
        CaseProcessWorkflowProtocol.roomWorkflowId(
            command.caseId(), command.roomType(), command.roomEpoch());
    if (desiredChildId.equals(activeChildWorkflowId)) {
      return;
    }
    if (provisioningEnabled) {
      throw protocolFailure(
          "ROOM_EPOCH_COMMAND_TUPLE_MISMATCH",
          "fenced workflow cannot create or reactivate a child from a command");
    }
    RoomControlWorkflow child =
        Workflow.newChildWorkflowStub(
            RoomControlWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(desiredChildId)
                .setTaskQueue(ROOM_CONTROL_TASK_QUEUE)
                .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setParentClosePolicy(PARENT_CLOSE_POLICY_ABANDON)
                .build());
    RoomControlStart start =
        new RoomControlStart(
            "room-control-start.v1",
            command.tenantSurrogate(),
            command.caseId(),
            command.roomType(),
            command.roomEpoch(),
            Workflow.getInfo().getWorkflowId(),
            command.caseCommandSequence(),
            nextCaseEventSequence);
    Promise<Void> childCompletion = Async.procedure(child::run, start);
    childCompletion.exceptionally(failure -> null);
    WorkflowExecution childExecution = Workflow.getWorkflowExecution(child).get();
    String retirementError = null;
    if (hasActiveChild()) {
      retirementError = retireActiveChild("ROOM_CONTROL_REPLACED");
    }
    activeRoomChild = child;
    activeIntakeChild = null;
    activeTargetTypedChild = null;
    activeRoomType = command.roomType();
    activeRoomEpoch = command.roomEpoch();
    activeChildWorkflowId = desiredChildId;
    activeChildWorkflowRunId = childExecution.getRunId();
    activeFencingToken = 0;
    activeRoomRevision = 0;
    activeChildDescriptor =
        new ActiveChildDescriptor(
            ActiveChildKind.GENERIC_ROOM_CONTROL,
            SELECTION_V1,
            WriterMode.LEGACY,
            CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
            "legacy-case-control.v1",
            null,
            null,
            command.roomType(),
            command.roomEpoch(),
            0,
            desiredChildId,
            childExecution.getRunId());
    if (retirementError != null) {
      recordProtocolError(retirementError, RecoveryErrorOrigin.COMMAND);
    }
  }

  private String retireActiveChild(String reason) {
    ActiveChildDescriptor retiring = activeChildDescriptor;
    String retirementError = null;
    if (retiring.kind() == ActiveChildKind.GENERIC_ROOM_CONTROL) {
      try {
        activeRoomChild.close(reason);
      } catch (CanceledFailure failure) {
        throw failure;
      } catch (SignalExternalWorkflowException | TemporalFailure failure) {
        retirementError = "ROOM_CONTROL_CLOSE_FAILED";
      }
    } else if (retiring.kind() == ActiveChildKind.TYPED_INTAKE) {
      try {
        Workflow.newUntypedExternalWorkflowStub(retiring.workflowId()).cancel(reason);
      } catch (CanceledFailure failure) {
        throw failure;
      } catch (RuntimeException failure) {
        retirementError = "INTAKE_ROOM_EXTERNAL_CANCEL_FAILED";
      }
    } else {
      try {
        if (activeTargetTypedChild == null) {
          throw protocolFailure(
              "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID",
              "target typed active child handle is missing during retirement");
        }
        activeTargetTypedChild.close(reason);
      } catch (CanceledFailure failure) {
        throw failure;
      } catch (RuntimeException failure) {
        retirementError = "TARGET_TYPED_ROOM_CLOSE_FAILED";
      }
    }
    rememberClosedRoom(retiring.roomType(), retiring.roomEpoch());
    return retirementError;
  }

  static CompensationOutcome cancelUncommittedChild(
      WorkflowExecution execution, String reason) {
    CompensationOutcome[] outcome = new CompensationOutcome[1];
    CancellationScope compensation =
        Workflow.newDetachedCancellationScope(
            () -> {
              try {
                Workflow.newUntypedExternalWorkflowStub(execution).cancel(reason);
                outcome[0] = CompensationOutcome.reconciled();
              } catch (CancelExternalWorkflowException alreadyClosedOrMissing) {
                outcome[0] = CompensationOutcome.reconciled();
              } catch (RuntimeException failure) {
                outcome[0] = CompensationOutcome.manualRecovery(failure);
              }
            });
    try {
      compensation.run();
    } catch (CancelExternalWorkflowException alreadyClosedOrMissing) {
      return CompensationOutcome.reconciled();
    } catch (RuntimeException failure) {
      return CompensationOutcome.manualRecovery(failure);
    }
    return outcome[0] == null
        ? CompensationOutcome.manualRecovery(
            new IllegalStateException("child compensation produced no outcome"))
        : outcome[0];
  }

  private void compensateChildrenAfterParentCancellation(CanceledFailure parentCancellation) {
    List<WorkflowExecution> targets = new ArrayList<>(2);
    if (uncommittedChild != null) {
      targets.add(uncommittedChild.execution());
    }
    WorkflowExecution activeExecution = activeChildExecution();
    addCompensationTarget(targets, activeExecution);
    for (UnreconciledChildExecution unreconciled : unreconciledChildren) {
      addCompensationTarget(
          targets,
          WorkflowExecution.newBuilder()
              .setWorkflowId(unreconciled.workflowId())
              .setRunId(unreconciled.workflowRunId())
              .build());
    }
    CompensationBatchOutcome batch =
        compensateChildren(
            targets,
            new ArrayList<>(unreconciledChildren),
            target -> cancelUncommittedChild(target, "CASE_PROCESS_PARENT_CANCELED"));
    unreconciledChildren.clear();
    unreconciledChildren.addAll(batch.unreconciledChildren());
    if (!batch.failures().isEmpty()) {
      provisioningManualRecoveryRequired = true;
      batch.failures().forEach(parentCancellation::addSuppressed);
    }
    uncommittedChild = null;
  }

  private static void addCompensationTarget(
      List<WorkflowExecution> targets, WorkflowExecution candidate) {
    if (candidate != null
        && targets.stream().noneMatch(target -> sameExecution(target, candidate))) {
      targets.add(candidate);
    }
  }

  static CompensationBatchOutcome compensateChildren(
      List<WorkflowExecution> targets,
      List<UnreconciledChildExecution> existing,
      Function<WorkflowExecution, CompensationOutcome> compensation) {
    Objects.requireNonNull(targets, "compensation targets must not be null");
    Objects.requireNonNull(compensation, "compensation function must not be null");
    List<UnreconciledChildExecution> unresolved = List.copyOf(existing);
    List<RuntimeException> failures = new ArrayList<>();
    LinkedHashSet<WorkflowExecution> distinctTargets = new LinkedHashSet<>(targets);
    for (WorkflowExecution target : distinctTargets) {
      CompensationOutcome outcome = compensation.apply(target);
      if (outcome.requiresManualRecovery()) {
        unresolved = withUnreconciledChild(unresolved, target);
        failures.add(outcome.failure());
      } else {
        unresolved = withoutUnreconciledChild(unresolved, target);
      }
    }
    return new CompensationBatchOutcome(unresolved, failures);
  }

  private static List<UnreconciledChildExecution> withoutUnreconciledChild(
      List<UnreconciledChildExecution> existing, WorkflowExecution reconciled) {
    return existing.stream()
        .filter(
            child ->
                !child.workflowId().equals(reconciled.getWorkflowId())
                    || !child.workflowRunId().equals(reconciled.getRunId()))
        .toList();
  }

  private WorkflowExecution activeChildExecution() {
    if (activeChildWorkflowId == null) {
      return null;
    }
    WorkflowExecution.Builder execution =
        WorkflowExecution.newBuilder().setWorkflowId(activeChildWorkflowId);
    if (activeChildWorkflowRunId != null && !activeChildWorkflowRunId.isBlank()) {
      execution.setRunId(activeChildWorkflowRunId);
    }
    return execution.build();
  }

  private static boolean sameExecution(WorkflowExecution left, WorkflowExecution right) {
    return left.getWorkflowId().equals(right.getWorkflowId())
        && left.getRunId().equals(right.getRunId());
  }

  private void markProvisioningManualRecovery(WorkflowExecution execution) {
    provisioningManualRecoveryRequired = true;
    if (execution == null) {
      return;
    }
    List<UnreconciledChildExecution> updated =
        withUnreconciledChild(new ArrayList<>(unreconciledChildren), execution);
    unreconciledChildren.clear();
    unreconciledChildren.addAll(updated);
  }

  static List<UnreconciledChildExecution> withUnreconciledChild(
      List<UnreconciledChildExecution> existing, WorkflowExecution execution) {
    Objects.requireNonNull(existing, "existing unreconciled children must not be null");
    Objects.requireNonNull(execution, "unreconciled child execution must not be null");
    UnreconciledChildExecution candidate =
        new UnreconciledChildExecution(execution.getWorkflowId(), execution.getRunId());
    LinkedHashSet<UnreconciledChildExecution> updated = new LinkedHashSet<>(existing);
    updated.add(candidate);
    if (updated.size() > CaseProcessCarryState.MAX_UNRECONCILED_CHILDREN) {
      throw new IllegalStateException("unreconciled child identity capacity exceeded");
    }
    return List.copyOf(updated);
  }

  private void clearUncommittedChild(StartedChild started) {
    if (uncommittedChild == started) {
      uncommittedChild = null;
    }
  }

  private void rememberClosedRoom(
      com.example.dispute.workflow.contract.v1.ContractTypes.RoomType roomType, long roomEpoch) {
    if (roomType == null || roomEpoch < 0) {
      return;
    }
    closedRooms.add(new ClosedRoomTuple(roomType, roomEpoch));
    while (closedRooms.size() > CaseProcessCarryState.MAX_CLOSED_ROOMS) {
      ClosedRoomTuple oldest = closedRooms.iterator().next();
      closedRooms.remove(oldest);
    }
  }

  private void failGapRecovery(SequenceStream stream, long highestObserved, String reasonCode) {
    int attempts;
    if (stream == SequenceStream.COMMAND) {
      attempts = ++commandRecoveryAttempts;
    } else {
      attempts = ++eventRecoveryAttempts;
    }
    if (attempts >= MAX_GAP_RECOVERY_ATTEMPTS) {
      markManualRecovery(stream, highestObserved, reasonCode);
      return;
    }
    Workflow.sleep(GAP_RETRY_DELAY.multipliedBy(attempts));
  }

  private void markManualRecovery(SequenceStream stream, long highestObserved, String reasonCode) {
    if (stream == SequenceStream.COMMAND) {
      commandManualRecoveryRequired = true;
      commandRecoveryAttempts = Math.max(commandRecoveryAttempts, 1);
      recordProtocolError(reasonCode, RecoveryErrorOrigin.COMMAND);
    } else {
      eventManualRecoveryRequired = true;
      eventRecoveryAttempts = Math.max(eventRecoveryAttempts, 1);
      recordProtocolError(reasonCode, RecoveryErrorOrigin.DOMAIN_EVENT);
    }
    reportGap(stream, highestObserved, reasonCode);
  }

  private void reportGap(SequenceStream stream, long highestObserved, String reasonCode) {
    long expected = stream == SequenceStream.COMMAND ? nextCommandSequence : nextCaseEventSequence;
    int attempts =
        stream == SequenceStream.COMMAND ? commandRecoveryAttempts : eventRecoveryAttempts;
    try {
      ledgerActivities.reportSequenceGap(
          new SequenceGapReport(
              "sequence-gap-report.v1",
              tenantSurrogate,
              caseId,
              Workflow.getInfo().getWorkflowId(),
              Workflow.getInfo().getRunId(),
              stream,
              expected,
              Math.max(expected, highestObserved),
              Math.max(1, attempts),
              reasonCode));
    } catch (ActivityFailure failure) {
      rethrowIfCanceled(failure);
    }
  }

  private void applyManualRecoveryRequest() {
    if (!retrySequenceGapRequested) {
      return;
    }
    retrySequenceGapRequested = false;
    commandManualRecoveryRequired = false;
    eventManualRecoveryRequired = false;
    commandRecoveryAttempts = 0;
    eventRecoveryAttempts = 0;
  }

  private void clearRecoveryError(SequenceStream stream) {
    if (protocolErrorCode == null) {
      return;
    }
    RecoveryErrorOrigin expectedOrigin =
        stream == SequenceStream.COMMAND
            ? RecoveryErrorOrigin.COMMAND
            : RecoveryErrorOrigin.DOMAIN_EVENT;
    if (protocolErrorOrigin != expectedOrigin) {
      return;
    }
    boolean recoverable =
        switch (stream) {
          case COMMAND ->
              protocolErrorCode.equals("COMMAND_SEQUENCE_NOT_FOUND")
                  || protocolErrorCode.equals("COMMAND_LEDGER_UNAVAILABLE")
                  || protocolErrorCode.equals("COMMAND_LEDGER_RESPONSE_INVALID")
                  || protocolErrorCode.equals("COMMAND_REPLAY_LEDGER_UNAVAILABLE")
                  || protocolErrorCode.equals("CASE_PROCESS_ROOM_ROUTING_FAILED")
                  || protocolErrorCode.equals("INTAKE_CHILD_BRIDGE_COMMAND_FAILED")
                  || protocolErrorCode.equals("INTAKE_CHILD_BRIDGE_COMMAND_BINDING_INVALID")
                  || protocolErrorCode.equals("INTAKE_CHILD_COMMAND_SIGNAL_FAILED")
                  || protocolErrorCode.equals("INTAKE_CHILD_ACTIVE_BINDING_INVALID");
          case DOMAIN_EVENT ->
              protocolErrorCode.equals("DOMAIN_EVENT_SEQUENCE_NOT_FOUND")
                  || protocolErrorCode.equals("DOMAIN_EVENT_LEDGER_UNAVAILABLE")
                  || protocolErrorCode.equals("DOMAIN_EVENT_LEDGER_RESPONSE_INVALID")
                  || protocolErrorCode.equals("CASE_PROCESS_EVENT_INBOX_FULL")
                  || protocolErrorCode.equals("CASE_PROCESS_ROOM_EVENT_ROUTING_FAILED")
                  || protocolErrorCode.equals("INTAKE_CHILD_BRIDGE_EVENT_FAILED")
                  || protocolErrorCode.equals("INTAKE_CHILD_BRIDGE_EVENT_BINDING_INVALID")
                  || protocolErrorCode.equals("INTAKE_CHILD_EVENT_SIGNAL_FAILED")
                  || protocolErrorCode.equals("INTAKE_CHILD_ACTIVE_BINDING_INVALID");
        };
    if (recoverable) {
      protocolErrorCode = null;
      protocolErrorOrigin = null;
    }
  }

  private void clearRecoveryError(RecoveryErrorOrigin origin) {
    if (protocolErrorOrigin == origin) {
      protocolErrorCode = null;
      protocolErrorOrigin = null;
    }
  }

  private void recordProtocolError(String errorCode, RecoveryErrorOrigin origin) {
    if (protocolErrorOrigin == RecoveryErrorOrigin.PROVISIONING
        && origin != RecoveryErrorOrigin.PROVISIONING) {
      return;
    }
    protocolErrorCode = errorCode;
    protocolErrorOrigin = origin;
  }

  private static RecoveryErrorOrigin typedFailureOrigin(
      String errorCode, SequenceStream fallbackStream) {
    if ("INTAKE_CHILD_ACTIVE_BINDING_INVALID".equals(errorCode)
        || "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID".equals(errorCode)) {
      return RecoveryErrorOrigin.PROVISIONING;
    }
    return fallbackStream == SequenceStream.COMMAND
        ? RecoveryErrorOrigin.COMMAND
        : RecoveryErrorOrigin.DOMAIN_EVENT;
  }

  static RecoveryErrorOrigin inferLegacyErrorOrigin(String errorCode) {
    if (errorCode == null) {
      return null;
    }
    if ("INTAKE_CHILD_ACTIVE_BINDING_INVALID".equals(errorCode)
        || "TARGET_TYPED_ROOM_ACTIVE_BINDING_INVALID".equals(errorCode)
        || "TARGET_TYPED_ROOM_DISPATCHER_UNAVAILABLE".equals(errorCode)
        || "TARGET_TYPED_ROOM_CLOSE_FAILED".equals(errorCode)
        || "ROOM_CONTROL_CLOSE_FAILED".equals(errorCode)
        || "INTAKE_ROOM_EXTERNAL_CANCEL_FAILED".equals(errorCode)
        || errorCode.startsWith("ROOM_EPOCH_")
        || errorCode.startsWith("INTAKE_CHILD_BRIDGE_START_")
        || errorCode.startsWith("INTAKE_CHILD_SELECTION_")
        || errorCode.startsWith("INTAKE_CHILD_WRITER_")
        || errorCode.startsWith("INTAKE_CHILD_ROOM_TYPE_")
        || errorCode.startsWith("INTAKE_CHILD_CASE_WORKFLOW_")
        || errorCode.startsWith("INTAKE_CHILD_WORKFLOW_")) {
      return RecoveryErrorOrigin.PROVISIONING;
    }
    if (errorCode.contains("EVENT") || errorCode.startsWith("DOMAIN_EVENT_")) {
      return RecoveryErrorOrigin.DOMAIN_EVENT;
    }
    if (errorCode.contains("COMMAND") || errorCode.startsWith("INTAKE_CHILD_BRIDGE_COMMAND_")) {
      return RecoveryErrorOrigin.COMMAND;
    }
    return RecoveryErrorOrigin.SYSTEM;
  }

  private boolean hasWork() {
    if (activeIntakeProjectionRecovery != null) {
      return false;
    }
    return (provisioningInboxCount > 0 && canSwitchRoomEpoch())
        || commandInboxCount > 0
        || eventInboxCount > 0
        || !replayChecks.isEmpty()
        || (!commandManualRecoveryRequired && !terminalNoCommitInbox.isEmpty())
        || (!commandManualRecoveryRequired && orderedCommands.containsKey(nextCommandSequence))
        || (!eventManualRecoveryRequired && canProcessNextEvent())
        || (!commandManualRecoveryRequired && hasCommandGap())
        || (!eventManualRecoveryRequired && hasEventGap())
        || retrySequenceGapRequested
        || (shouldContinueAsNew() && canContinueAsNew());
  }

  private boolean canSwitchRoomEpoch() {
    return commandInboxCount == 0
        && eventInboxCount == 0
        && orderedCommands.isEmpty()
        && replayChecks.isEmpty()
        && terminalNoCommitInbox.isEmpty()
        && bufferedEvents.isEmpty()
        && !hasCommandGap()
        && !hasEventGap();
  }

  private boolean hasCommandGap() {
    return highestObservedCommandSequence >= nextCommandSequence
        && !orderedCommands.containsKey(nextCommandSequence);
  }

  private boolean hasEventGap() {
    return (eventRecoveryForced || highestObservedEventSequence >= nextCaseEventSequence)
        && !bufferedEvents.containsKey(nextCaseEventSequence);
  }

  private boolean shouldContinueAsNew() {
    return continueAsNewRequested
        || (runMaxAgeTimer != null && runMaxAgeTimer.isCompleted())
        || Workflow.getInfo().isContinueAsNewSuggested()
        || Workflow.getInfo().getHistoryLength() >= HISTORY_EVENT_LIMIT;
  }

  private boolean canContinueAsNew() {
    return activeIntakeProjectionRecovery == null
        && provisioningInboxCount == 0
        && pendingProvisioningByUpdateId.isEmpty()
        && commandInboxCount == 0
        && eventInboxCount == 0
        && orderedCommands.isEmpty()
        && replayChecks.isEmpty()
        && terminalNoCommitInbox.isEmpty()
        && Workflow.isEveryHandlerFinished();
  }

  private void continueAsNew() {
    Workflow.await(Workflow::isEveryHandlerFinished);
    CaseProcessCarryState carry =
        new CaseProcessCarryState(
            "case-process-carry-state.v1",
            tenantSurrogate,
            caseId,
            activeRoomType,
            activeRoomEpoch,
            activeChildWorkflowId,
            observedProcessRevision,
            nextCommandSequence,
            nextCaseEventSequence,
            processedCommandCount,
            processedEventCount,
            new ArrayList<>(recentCommands.values()),
            new ArrayList<>(bufferedEvents.values()),
            highestObservedEventSequence,
            runGeneration + 1,
            commandRecoveryAttempts,
            eventRecoveryAttempts,
            commandManualRecoveryRequired,
            eventManualRecoveryRequired,
            protocolErrorCode,
            new ArrayList<>(closedRooms),
            activeFencingToken,
            activeChildWorkflowRunId,
            new ArrayList<>(provisioningCommitments.values()),
            highestProvisionedEpochs.entrySet().stream()
                .map(entry -> new ProvisionedRoomEpochHighWater(entry.getKey(), entry.getValue()))
                .toList(),
            activeChildDescriptor,
            hasActiveChild() ? activeRoomRevision : null,
            protocolErrorOrigin,
            provisioningManualRecoveryRequired,
            new ArrayList<>(unreconciledChildren),
            lastTargetRoomProgress);
    ContinueAsNewOptions options =
        ContinueAsNewOptions.newBuilder()
            .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
            .setMemo(Map.of(CARRY_STATE_MEMO_KEY, carry))
            .build();
    // The optional input preserves zero-input starts while carrying state on test servers
    // that do not propagate Continue-As-New Memo; Memo remains the operational copy.
    Workflow.continueAsNew(options, carry);
  }

  private LoadSequenceRange range(long fromSequence, long toSequence) {
    return new LoadSequenceRange(
        "load-sequence-range.v1",
        tenantSurrogate,
        caseId,
        fromSequence,
        toSequence,
        Math.toIntExact(toSequence - fromSequence + 1));
  }

  private void bindIdentity(String incomingTenant, String incomingCaseId) {
    if (tenantSurrogate == null) {
      tenantSurrogate = incomingTenant;
      caseId = incomingCaseId;
      requireWorkflowIdentity(incomingTenant, incomingCaseId);
      return;
    }
    if (!tenantSurrogate.equals(incomingTenant) || !caseId.equals(incomingCaseId)) {
      throw protocolFailure(
          "CASE_PROCESS_SCOPE_MISMATCH", "workflow received an envelope for another case");
    }
  }

  private void requireWorkflowIdentity(String incomingTenant, String incomingCaseId) {
    String expected = CaseProcessWorkflowProtocol.caseWorkflowId(incomingTenant, incomingCaseId);
    if (!Workflow.getInfo().getWorkflowId().equals(expected)) {
      throw protocolFailure(
          "CASE_PROCESS_WORKFLOW_ID_MISMATCH", "workflow id does not match the command scope");
    }
  }

  private void validateCommandEnvelope(CaseCommandRef command) {
    if (command == null) {
      throw protocolFailure("CASE_PROCESS_COMMAND_INVALID", "command must not be null");
    }
    requireWorkflowIdentity(command.tenantSurrogate(), command.caseId());
    if (tenantSurrogate != null
        && (!tenantSurrogate.equals(command.tenantSurrogate())
            || !caseId.equals(command.caseId()))) {
      throw protocolFailure(
          "CASE_PROCESS_SCOPE_MISMATCH", "workflow received a command for another case");
    }
    if (command.caseCommandSequence() < 1
        || command.roomEpoch() < 0
        || command.expectedProcessRevision() < 0
        || command.payloadRef().sizeBytes() < 0
        || !SHA256.matcher(command.payloadRef().sha256()).matches()
        || !SHA256.matcher(command.requestHash()).matches()
        || !TRACEPARENT.matcher(command.traceparent()).matches()
        || !command.deadlineAt().isAfter(command.occurredAt())) {
      throw protocolFailure(
          "CASE_PROCESS_COMMAND_INVALID", "command envelope failed workflow validation");
    }
  }

  private void validateProvisionedCommand(CaseCommandRef command) {
    validateProvisionedCommand(command, false);
  }

  private void validateProvisionedCommand(CaseCommandRef command, boolean authoritativeRecovery) {
    if (provisioningEnabled == null) {
      if (Workflow.isReplaying()) {
        return;
      }
      throw protocolFailure(
          "ROOM_EPOCH_NOT_PROVISIONED",
          "a command cannot start an unprovisioned case workflow");
    }
    if (!provisioningEnabled) {
      return;
    }
    if (provisioningSwitchInProgress) {
      throw protocolFailure(
          "ROOM_EPOCH_SWITCH_IN_PROGRESS",
          "command admission is closed while the fenced child is switching");
    }
    ProvisioningCommitment commitment = currentProvisioningCommitment();
    if (commitment == null) {
      throw protocolFailure(
          "ROOM_EPOCH_NOT_PROVISIONED",
          "command admission requires a current provisioning commitment");
    }
    ProvisionRoomEpoch provisioned = commitment.request();
    if (!provisioned.tenantSurrogate().equals(command.tenantSurrogate())
        || !provisioned.caseId().equals(command.caseId())
        || provisioned.roomType() != command.roomType()
        || provisioned.roomEpoch() != command.roomEpoch()) {
      throw protocolFailure(
          "ROOM_EPOCH_COMMAND_TUPLE_MISMATCH",
          "command does not match the currently provisioned room tuple");
    }
    boolean historicalOrRecovered =
        authoritativeRecovery || command.caseCommandSequence() < nextCommandSequence;
    boolean futureBuffered = command.caseCommandSequence() > nextCommandSequence;
    boolean revisionMismatch =
        historicalOrRecovered
            ? command.expectedProcessRevision() > observedProcessRevision
            : futureBuffered
                ? command.expectedProcessRevision() < observedProcessRevision
                : command.expectedProcessRevision() != observedProcessRevision;
    if (revisionMismatch) {
      throw protocolFailure(
          "ROOM_EPOCH_COMMAND_REVISION_MISMATCH",
          "command expected process revision does not match fenced authority");
    }
  }

  private String eventValidationError(CaseDomainEventRef event) {
    if (event == null) {
      return "CASE_PROCESS_EVENT_INVALID";
    }
    String expected =
        CaseProcessWorkflowProtocol.caseWorkflowId(event.tenantSurrogate(), event.caseId());
    if (!Workflow.getInfo().getWorkflowId().equals(expected)) {
      return "CASE_PROCESS_WORKFLOW_ID_MISMATCH";
    }
    if (tenantSurrogate != null
        && (!tenantSurrogate.equals(event.tenantSurrogate()) || !caseId.equals(event.caseId()))) {
      return "CASE_PROCESS_SCOPE_MISMATCH";
    }
    return null;
  }

  private void validateLoadedCommand(CaseCommandRef command, long fromSequence, long toSequence) {
    validateCommandEnvelope(command);
    if (command.caseCommandSequence() < fromSequence
        || command.caseCommandSequence() > toSequence) {
      throw protocolFailure(
          "COMMAND_LEDGER_RESPONSE_INVALID", "command ledger returned an out-of-range command");
    }
  }

  private void validateLoadedEvent(CaseDomainEventRef event, long fromSequence, long toSequence) {
    String error = eventValidationError(event);
    if (error != null
        || event.caseEventSequence() < fromSequence
        || event.caseEventSequence() > toSequence) {
      throw protocolFailure(
          "DOMAIN_EVENT_LEDGER_RESPONSE_INVALID", "domain event ledger returned an invalid event");
    }
  }

  private String blockedReason() {
    if (provisioningManualRecoveryRequired) {
      return "PROVISIONING_MANUAL_RECOVERY";
    }
    if (commandManualRecoveryRequired) {
      return replayChecks.isEmpty()
          ? "COMMAND_GAP_MANUAL_RECOVERY"
          : "COMMAND_REPLAY_MANUAL_RECOVERY";
    }
    if (eventManualRecoveryRequired) {
      return "EVENT_GAP_MANUAL_RECOVERY";
    }
    if (hasCommandGap()) {
      return "COMMAND_GAP";
    }
    if (hasEventGap()) {
      return "EVENT_GAP";
    }
    CaseDomainEventRef nextEvent = bufferedEvents.get(nextCaseEventSequence);
    if (Boolean.TRUE.equals(futureRoomEventRetentionEnabled)
        && nextEvent != null
        && isRoomMismatch(nextEvent)
        && !closedRooms.contains(
            new ClosedRoomTuple(nextEvent.roomType(), nextEvent.roomEpoch()))) {
      return "FUTURE_ROOM_EVENT";
    }
    return protocolErrorCode == null ? "NONE" : "PROTOCOL_ERROR";
  }

  private void trimRecentCommands() {
    while (recentCommands.size() > CaseProcessCarryState.MAX_RECENT_COMMANDS) {
      String first = recentCommands.keySet().iterator().next();
      recentCommands.remove(first);
    }
  }

  private static boolean sameCommand(CaseCommandRef left, CaseCommandRef right) {
    return left.commandId().equals(right.commandId())
        && left.caseCommandSequence() == right.caseCommandSequence()
        && left.requestHash().equals(right.requestHash());
  }

  private static boolean sameEvent(CaseDomainEventRef left, CaseDomainEventRef right) {
    return left.eventId().equals(right.eventId())
        && left.caseEventSequence() == right.caseEventSequence()
        && left.payloadRef().sha256().equals(right.payloadRef().sha256());
  }

  private static ApplicationFailure protocolFailure(String type, String message) {
    return ApplicationFailure.newNonRetryableFailure(message, type);
  }

  private static ApplicationFailure intakeProjectionRecoveryFailure(RuntimeException failure) {
    return ApplicationFailure.newNonRetryableFailureWithCause(
        "Intake projection completion recovery failed after acceptance",
        INTAKE_PROJECTION_RECOVERY_FAILURE,
        failure);
  }

  static void rethrowIfCanceled(ActivityFailure failure) {
    Throwable current = failure.getCause();
    while (current != null) {
      if (current instanceof CanceledFailure canceled) {
        throw canceled;
      }
      current = current.getCause();
    }
  }

  static ApplicationFailure failClosedProvisioningRuntime(RuntimeException failure) {
    return ApplicationFailure.newNonRetryableFailureWithCause(
        "room epoch provisioning failed: " + failure.getMessage(),
        "ROOM_EPOCH_PROVISIONING_RUNTIME_FAILURE",
        failure);
  }

  private static boolean isNonRetryableActivityFailure(ActivityFailure failure) {
    return failure.getCause() instanceof ApplicationFailure applicationFailure
        && applicationFailure.isNonRetryable();
  }

  private record StartedChild(
      RoomControlWorkflow genericChild,
      IntakeRoomWorkflow typedIntakeChild,
      TargetTypedRoomChildHandle targetTypedChild,
      WorkflowExecution execution,
      String initiatorActorScopeHash,
      String respondentActorScopeHash) {}

  /** Target-only child adapter surface; implementations must remain deterministic Workflow code. */
  protected interface TargetTypedRoomChildHandle {

    WorkflowExecution execution();

    TargetTypedRoomDispatchReceipt commandAccepted(CaseCommandRef command);

    TargetTypedRoomDispatchReceipt domainEventCommitted(CaseDomainEventRef event);

    default TargetTypedRoomDispatchReceipt globalIntakeProjectionReady(
        CaseDomainEventRef event) {
      throw new IllegalArgumentException(
          "target typed-room handle does not accept a global Intake projection cursor");
    }

    /** Runs only after the parent has durably completed command routing. */
    default TargetTypedRoomDispatchReceipt postRouting(CaseCommandRef command) {
      return null;
    }

    /** Recovers a B-owned terminal target receipt after the command ledger already says APPLIED. */
    default TargetTypedRoomDispatchReceipt recoverAppliedTerminal(CaseCommandRef command) {
      return null;
    }

    /** A target room may close its child as the durable result of its post-routing hook. */
    default boolean terminalAfterPostRouting() {
      return false;
    }

    /** The source target room closed, but the case awaits a real next-room provisioning update. */
    default boolean sourceTransitionAfterPostRouting() {
      return false;
    }

    /** Exact durable DB receipt for a terminal post-routing transition, if one occurred. */
    default TargetRoomProgressReceipt terminalProgressReceipt() {
      return null;
    }

    String initiatorActorScopeHash();

    String respondentActorScopeHash();

    default Binding reviewOutcomeStartBinding() {
      return null;
    }

    default com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceParticipantBindingActivities.Binding
        evidenceParticipantBinding() {
      return null;
    }

    void close(String reason);
  }

  /** Exact fenced revisions returned by the target-only typed-room dispatcher. */
  protected record TargetTypedRoomDispatchReceipt(
      RoomType roomType,
      long roomEpoch,
      long fencingToken,
      long processRevision,
      long roomRevision) {

    public TargetTypedRoomDispatchReceipt {
      Objects.requireNonNull(roomType, "roomType must not be null");
      if (roomEpoch < 0
          || fencingToken < 1
          || processRevision < 0
          || roomRevision < 0) {
        throw new IllegalArgumentException("target typed room receipt coordinates are invalid");
      }
    }
  }

  static record CompensationOutcome(boolean requiresManualRecovery, RuntimeException failure) {

    private static CompensationOutcome reconciled() {
      return new CompensationOutcome(false, null);
    }

    private static CompensationOutcome manualRecovery(RuntimeException failure) {
      return new CompensationOutcome(true, Objects.requireNonNull(failure));
    }
  }

  static record CompensationBatchOutcome(
      List<UnreconciledChildExecution> unreconciledChildren,
      List<RuntimeException> failures) {

    CompensationBatchOutcome {
      unreconciledChildren = List.copyOf(unreconciledChildren);
      failures = List.copyOf(failures);
    }
  }

  private static final class TypedChildOperationFailure extends RuntimeException {
    private final String errorCode;

    private TypedChildOperationFailure(String errorCode, String message, Throwable cause) {
      super(message, cause);
      this.errorCode = errorCode;
    }

    String errorCode() {
      return errorCode;
    }
  }

  private record PendingProvisioning(
      String updateId,
      String payloadSha256,
      ProvisionRoomEpoch request,
      CompletablePromise<ProvisionRoomEpochReceipt> completion) {

    private PendingProvisioning {
      Objects.requireNonNull(updateId);
      Objects.requireNonNull(payloadSha256);
      Objects.requireNonNull(request);
      Objects.requireNonNull(completion);
    }

    void complete(ProvisionRoomEpochReceipt receipt) {
      completion.complete(receipt);
    }

    void fail(RuntimeException failure) {
      completion.completeExceptionally(failure);
    }
  }

  private static final class PendingCommand {
    private final CaseCommandRef command;
    private CaseCommandLedgerState ledgerState;
    private boolean authoritativeLedgerState;
    private final List<CompletablePromise<Void>> completions;

    private PendingCommand(
        CaseCommandRef command,
        CaseCommandLedgerState ledgerState,
        boolean authoritativeLedgerState,
        List<CompletablePromise<Void>> completions) {
      this.command = Objects.requireNonNull(command);
      this.ledgerState = Objects.requireNonNull(ledgerState);
      this.authoritativeLedgerState = authoritativeLedgerState;
      this.completions = completions;
    }

    static PendingCommand live(CaseCommandRef command, CompletablePromise<Void> completion) {
      return new PendingCommand(
          command,
          CaseCommandLedgerState.PENDING_ORCHESTRATION,
          false,
          new ArrayList<>(List.of(completion)));
    }

    static PendingCommand recovered(CaseCommandRef command, CaseCommandLedgerState ledgerState) {
      return new PendingCommand(command, ledgerState, true, new ArrayList<>());
    }

    CaseCommandRef command() {
      return command;
    }

    CaseCommandLedgerState ledgerState() {
      return ledgerState;
    }

    boolean authoritativeLedgerState() {
      return authoritativeLedgerState;
    }

    void absorb(PendingCommand other) {
      completions.addAll(other.completions);
      if (other.authoritativeLedgerState) {
        ledgerState = other.ledgerState;
        authoritativeLedgerState = true;
      }
    }

    void complete() {
      completions.forEach(completion -> completion.complete(null));
    }

    void fail(RuntimeException failure) {
      completions.forEach(completion -> completion.completeExceptionally(failure));
    }
  }
}

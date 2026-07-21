package com.example.dispute.workflow.temporal.caseprocess;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE;
import static io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ClosedRoomTuple;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ProvisionedRoomEpochHighWater;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceStream;
import com.example.dispute.workflow.temporal.room.common.RoomControlStart;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
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
  private static final String AUTHORITY_CHECKPOINT_CHANGE_ID =
      "case-process-authority-checkpoint-v1";
  private static final String AUTHORITY_CHECKPOINT_MEMO_KEY =
      "case_process_authority_checkpoint_v1";
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
  private final WorkflowQueue<PendingCommand> commandInbox = Workflow.newQueue(INBOX_CAPACITY);
  private final WorkflowQueue<CaseDomainEventRef> eventInbox = Workflow.newQueue(INBOX_CAPACITY);
  private final WorkflowQueue<PendingProvisioning> provisioningInbox =
      Workflow.newQueue(INBOX_CAPACITY);
  private final NavigableMap<Long, PendingCommand> orderedCommands = new TreeMap<>();
  private final ArrayDeque<PendingCommand> replayChecks = new ArrayDeque<>();
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

  private String tenantSurrogate;
  private String caseId;
  private com.example.dispute.workflow.contract.v1.ContractTypes.RoomType activeRoomType;
  private long activeRoomEpoch = -1;
  private String activeChildWorkflowId;
  private String activeChildWorkflowRunId;
  private long activeFencingToken;
  private RoomControlWorkflow activeRoomChild;
  private long observedProcessRevision;
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
  private boolean eventRecoveryForced;
  private boolean retrySequenceGapRequested;
  private boolean continueAsNewRequested;
  private Boolean futureRoomEventRetentionEnabled;
  private Boolean provisioningEnabled;
  private boolean authorityCheckpointEnabled;
  private boolean provisioningSwitchInProgress;
  private String protocolErrorCode;
  private Promise<Void> runMaxAgeTimer;

  @Override
  public void run(CaseProcessCarryState carryState) {
    restoreCarryState(carryState);
    provisioningEnabled =
        Workflow.getVersion(ROOM_EPOCH_PROVISION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
    authorityCheckpointEnabled =
        Workflow.getVersion(AUTHORITY_CHECKPOINT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1) == 1;
    restoreAuthorityCheckpoint();
    runMaxAgeTimer = Workflow.newTimer(RUN_MAX_AGE);
    while (true) {
      drainCommandInbox();
      drainEventInbox();
      applyManualRecoveryRequest();

      if (processReplayCheck()) {
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
    validateProvisionRequest(request);
    String updateId = currentUpdateId();
    String payloadSha256 = request.payloadSha256();
    requireExpectedProvisioningUpdateId(updateId, request);
    ProvisioningCommitment committed = provisioningCommitments.get(updateId);
    if (committed != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, committed.payloadSha256());
      return committed.receipt();
    }
    PendingProvisioning existing = pendingProvisioningByUpdateId.get(updateId);
    if (existing != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, existing.payloadSha256());
      return existing.completion().get();
    }
    validateProvisioningOrder(request, updateId, payloadSha256);
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
    validateProvisionRequest(request);
    String updateId = currentUpdateId();
    String payloadSha256 = request.payloadSha256();
    requireExpectedProvisioningUpdateId(updateId, request);
    ProvisioningCommitment committed = provisioningCommitments.get(updateId);
    if (committed != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, committed.payloadSha256());
      return;
    }
    PendingProvisioning pending = pendingProvisioningByUpdateId.get(updateId);
    if (pending != null) {
      requireSameProvisioningPayload(updateId, payloadSha256, pending.payloadSha256());
      return;
    }
    validateProvisioningOrder(request, updateId, payloadSha256);
  }

  @Override
  public void domainEventCommitted(CaseDomainEventRef event) {
    String validationError = eventValidationError(event);
    if (validationError != null) {
      protocolErrorCode = validationError;
      return;
    }
    highestObservedEventSequence =
        Math.max(highestObservedEventSequence, event.caseEventSequence());
    if (event.caseEventSequence() < nextCaseEventSequence) {
      return;
    }
    if (!eventInbox.offer(event)) {
      eventRecoveryForced = true;
      protocolErrorCode = "CASE_PROCESS_EVENT_INBOX_FULL";
      return;
    }
    eventInboxCount++;
  }

  @Override
  public void retrySequenceGap() {
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
            : currentProvisioningCommitment().payloadSha256());
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
    protocolErrorCode = carry.protocolErrorCode();
    carry.recentCommands().forEach(identity -> recentCommands.put(identity.commandId(), identity));
    carry.bufferedEvents().forEach(event -> bufferedEvents.put(event.caseEventSequence(), event));
    closedRooms.addAll(carry.closedRooms());
    carry
        .provisioningCommitments()
        .forEach(commitment -> provisioningCommitments.put(commitment.updateId(), commitment));
    carry
        .highestProvisionedEpochs()
        .forEach(
            highWater -> highestProvisionedEpochs.put(highWater.roomType(), highWater.roomEpoch()));
    if (activeChildWorkflowId != null) {
      activeRoomChild =
          Workflow.newExternalWorkflowStub(RoomControlWorkflow.class, activeChildWorkflowId);
    }
    if (tenantSurrogate != null) {
      requireWorkflowIdentity(tenantSurrogate, caseId);
    }
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
    try {
      ProvisionRoomEpoch request = pending.request();
      validateAgainstCommittedProvisioning(request, pending.updateId(), pending.payloadSha256());
      if (request.firstCommandSequence() != nextCommandSequence
          || request.firstCaseEventSequence() != nextCaseEventSequence) {
        throw protocolFailure(
            "ROOM_EPOCH_SEQUENCE_BOUNDARY_CONFLICT",
            "provisioning sequence boundary does not match the case workflow");
      }
      boolean initialBootstrap =
          provisioningCommitments.isEmpty()
              && activeRoomChild == null
              && processedCommandCount == 0
              && processedEventCount == 0;
      if ((!initialBootstrap && request.initialProcessRevision() != observedProcessRevision)
          || (initialBootstrap && request.initialProcessRevision() < observedProcessRevision)) {
        throw protocolFailure(
            "ROOM_EPOCH_PROCESS_REVISION_CONFLICT",
            "provisioning process revision does not match case authority");
      }
      bindIdentity(request.tenantSurrogate(), request.caseId());

      RoomControlWorkflow child =
          Workflow.newChildWorkflowStub(
              RoomControlWorkflow.class,
              ChildWorkflowOptions.newBuilder()
                  .setWorkflowId(request.roomWorkflowId())
                  .setTaskQueue(ROOM_CONTROL_TASK_QUEUE)
                  .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                  .setParentClosePolicy(PARENT_CLOSE_POLICY_ABANDON)
                  .build());
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
              pending.payloadSha256());
      Promise<Void> childCompletion = Async.procedure(child::run, start);
      childCompletion.exceptionally(failure -> null);
      WorkflowExecution childExecution = Workflow.getWorkflowExecution(child).get();

      if (activeRoomChild != null) {
        closeActiveRoomChild("ROOM_CONTROL_REPLACED_BY_PROVISIONING");
      }
      activeRoomChild = child;
      activeRoomType = request.roomType();
      activeRoomEpoch = request.roomEpoch();
      activeChildWorkflowId = request.roomWorkflowId();
      activeChildWorkflowRunId = childExecution.getRunId();
      activeFencingToken = request.fencingToken();
      observedProcessRevision = request.initialProcessRevision();

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
              childExecution.getRunId(),
              pending.payloadSha256());
      ProvisioningCommitment commitment =
          new ProvisioningCommitment(pending.updateId(), pending.payloadSha256(), request, receipt);
      provisioningCommitments.put(pending.updateId(), commitment);
      highestProvisionedEpochs.merge(request.roomType(), request.roomEpoch(), Math::max);
      trimProvisioningCommitments();
      if (authorityCheckpointEnabled) {
        Workflow.upsertMemo(Map.of(AUTHORITY_CHECKPOINT_MEMO_KEY, receipt));
      }
      pending.complete(receipt);
    } catch (ApplicationFailure failure) {
      protocolErrorCode = failure.getType();
      pending.fail(failure);
    } catch (ChildWorkflowFailure failure) {
      ApplicationFailure conflict =
          protocolFailure(
              "ROOM_EPOCH_CHILD_START_CONFLICT",
              "room child workflow id is already bound to another execution");
      protocolErrorCode = conflict.getType();
      pending.fail(conflict);
    } finally {
      provisioningSwitchInProgress = false;
      pendingProvisioningByUpdateId.remove(pending.updateId());
    }
    return true;
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
      protocolErrorCode = "CASE_PROCESS_COMMAND_REPLAY_CONFLICT";
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
          consumeTerminalCommand(pending, admissionTerminal);
          return true;
        }
      }
      ensureRoomChild(command);
      activeRoomChild.commandAccepted(command);
      if (commandLifecycleEnabled) {
        RecordCaseCommandRoutedResult completion =
            commandLifecycleActivities.completeCaseCommandRouting(routing);
        CaseCommandLedgerState completionTerminal = terminalLedgerState(completion.outcome());
        if (completionTerminal != null && !completionTerminal.successfulTerminal()) {
          consumeTerminalCommand(pending, completionTerminal);
          return true;
        }
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
    } catch (ActivityFailure failure) {
      orderedCommands.put(nextCommandSequence, pending);
      rethrowIfCanceled(failure);
      if (isNonRetryableActivityFailure(failure)) {
        protocolErrorCode = "CASE_PROCESS_COMMAND_LIFECYCLE_REJECTED";
        commandManualRecoveryRequired = true;
        pending.fail(
            protocolFailure(
                "CASE_PROCESS_COMMAND_LIFECYCLE_REJECTED",
                "command lifecycle validation requires manual recovery"));
        return true;
      }
      protocolErrorCode = "CASE_PROCESS_COMMAND_ROUTING_FAILED";
      throw failure;
    } catch (SignalExternalWorkflowException failure) {
      protocolErrorCode = "CASE_PROCESS_ROOM_ROUTING_FAILED";
      commandManualRecoveryRequired = true;
      pending.fail(
          protocolFailure(
              "CASE_PROCESS_ROOM_ROUTING_FAILED",
              "room child workflow could not accept the command"));
    } catch (ApplicationFailure failure) {
      if (!failure.isNonRetryable()) {
        protocolErrorCode = "CASE_PROCESS_ROOM_ROUTING_FAILED";
        commandManualRecoveryRequired = true;
        pending.fail(
            protocolFailure(
                "CASE_PROCESS_ROOM_ROUTING_FAILED",
                "room child workflow could not accept the command"));
        return true;
      }
      protocolErrorCode = failure.getType();
      commandManualRecoveryRequired = true;
      pending.fail(failure);
    } catch (TemporalFailure failure) {
      if (failure instanceof CanceledFailure) {
        throw failure;
      }
      protocolErrorCode = "CASE_PROCESS_ROOM_ROUTING_FAILED";
      commandManualRecoveryRequired = true;
      pending.fail(
          protocolFailure(
              "CASE_PROCESS_ROOM_ROUTING_FAILED",
              "room child workflow could not accept the command"));
    } catch (RuntimeException exception) {
      orderedCommands.put(nextCommandSequence, pending);
      protocolErrorCode = "CASE_PROCESS_COMMAND_ROUTING_FAILED";
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
    if (activeRoomChild != null
        && activeRoomType == event.roomType()
        && activeRoomEpoch == event.roomEpoch()) {
      try {
        Async.procedure(activeRoomChild::domainEventCommitted, event).get();
      } catch (CanceledFailure failure) {
        throw failure;
      } catch (SignalExternalWorkflowException | TemporalFailure failure) {
        protocolErrorCode = "CASE_PROCESS_ROOM_EVENT_ROUTING_FAILED";
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
        && (activeRoomChild == null
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
      protocolErrorCode = "CASE_PROCESS_COMMAND_SEQUENCE_CONFLICT";
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
        protocolErrorCode = "CASE_PROCESS_EVENT_SEQUENCE_CONFLICT";
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
    if (activeRoomChild != null) {
      closeActiveRoomChild("ROOM_CONTROL_REPLACED");
    }
    activeRoomChild = child;
    activeRoomType = command.roomType();
    activeRoomEpoch = command.roomEpoch();
    activeChildWorkflowId = desiredChildId;
    activeChildWorkflowRunId = childExecution.getRunId();
    activeFencingToken = 0;
  }

  private void closeActiveRoomChild(String reason) {
    try {
      activeRoomChild.close(reason);
    } catch (CanceledFailure failure) {
      throw failure;
    } catch (SignalExternalWorkflowException | TemporalFailure failure) {
      protocolErrorCode = "ROOM_CONTROL_CLOSE_FAILED";
    }
    rememberClosedRoom(activeRoomType, activeRoomEpoch);
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
      protocolErrorCode = reasonCode;
    } else {
      eventManualRecoveryRequired = true;
      eventRecoveryAttempts = Math.max(eventRecoveryAttempts, 1);
      protocolErrorCode = reasonCode;
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
    boolean recoverable =
        switch (stream) {
          case COMMAND ->
              protocolErrorCode.equals("COMMAND_SEQUENCE_NOT_FOUND")
                  || protocolErrorCode.equals("COMMAND_LEDGER_UNAVAILABLE")
                  || protocolErrorCode.equals("COMMAND_LEDGER_RESPONSE_INVALID")
                  || protocolErrorCode.equals("COMMAND_REPLAY_LEDGER_UNAVAILABLE");
          case DOMAIN_EVENT ->
              protocolErrorCode.equals("DOMAIN_EVENT_SEQUENCE_NOT_FOUND")
                  || protocolErrorCode.equals("DOMAIN_EVENT_LEDGER_UNAVAILABLE")
                  || protocolErrorCode.equals("DOMAIN_EVENT_LEDGER_RESPONSE_INVALID")
                  || protocolErrorCode.equals("CASE_PROCESS_EVENT_INBOX_FULL")
                  || protocolErrorCode.equals("CASE_PROCESS_ROOM_EVENT_ROUTING_FAILED");
        };
    if (recoverable) {
      protocolErrorCode = null;
    }
  }

  private boolean hasWork() {
    return (provisioningInboxCount > 0 && canSwitchRoomEpoch())
        || commandInboxCount > 0
        || eventInboxCount > 0
        || !replayChecks.isEmpty()
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
    return provisioningInboxCount == 0
        && pendingProvisioningByUpdateId.isEmpty()
        && commandInboxCount == 0
        && eventInboxCount == 0
        && orderedCommands.isEmpty()
        && replayChecks.isEmpty()
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
                .toList());
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

  private static void rethrowIfCanceled(ActivityFailure failure) {
    if (failure.getCause() instanceof CanceledFailure) {
      throw failure;
    }
  }

  private static boolean isNonRetryableActivityFailure(ActivityFailure failure) {
    return failure.getCause() instanceof ApplicationFailure applicationFailure
        && applicationFailure.isNonRetryable();
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

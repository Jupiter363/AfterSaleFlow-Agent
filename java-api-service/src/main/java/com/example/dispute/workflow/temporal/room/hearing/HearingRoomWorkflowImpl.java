package com.example.dispute.workflow.temporal.room.hearing;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.workflow.contract.v1.AgentRunWorkflowIds;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingFormalizationActivities;
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunFinalizationReceipt;
import com.example.dispute.workflow.targete2e.temporal.room.hearing.TargetHearingBootstrapActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic 15-stage Hearing process kernel. It contains no domain or model side effects. */
public final class HearingRoomWorkflowImpl implements HearingRoomWorkflow {

  private static final int MAX_ACCEPTED_RECEIPTS = 64;
  private static final int MAX_INBOX_RECEIPTS = 64;
  private static final int MAX_PENDING_RECEIPTS = 64;
  private static final int MAX_AGENT_RUN_FINALIZATION_RECEIPTS = 64;
  private static final String HANDOFF_OPERATION_KEY_CHANGE_ID =
      "target-hearing-exact-handoff-operation-key-v1";
  private static final String PARTY_TIMEOUT_FORMALIZATION_CHANGE_ID =
      "target-hearing-party-timeout-formalization-v1";
  private final TargetHearingFormalizationActivities formalization =
      Workflow.newActivityStub(
          TargetHearingFormalizationActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setScheduleToCloseTimeout(Duration.ofMinutes(5))
              .build());
  private final TargetHearingBootstrapActivities activation =
      Workflow.newActivityStub(
          TargetHearingBootstrapActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setScheduleToCloseTimeout(Duration.ofMinutes(30))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofMillis(250))
                      .setMaximumInterval(Duration.ofSeconds(5))
                      .setDoNotRetry(TargetHearingBootstrapActivities.ACTIVATION_INVALID)
                      .build())
              .build());

  private final ArrayDeque<WorkflowEvent> inbox = new ArrayDeque<>();
  private final Map<String, Object> observedReceipts = new LinkedHashMap<>();
  private final Map<String, HearingPartyCommand> observedPartyCommands = new LinkedHashMap<>();
  private final Map<String, TargetRoomAgentRunFinalizationReceipt> agentRunFinalizationReceipts =
      new LinkedHashMap<>();
  private final Map<Long, String> observedEventSequences = new LinkedHashMap<>();
  private final TreeMap<Long, WorkflowEvent> pendingReceipts = new TreeMap<>();
  private final Map<String, HearingPartyTerminalReceipt> partyTerminals = new LinkedHashMap<>();
  private final Set<String> timeoutRequired = new LinkedHashSet<>();
  private final List<String> orderedOperationKeys = new ArrayList<>();

  private HearingRoomStart start;
  private HearingWorkflowStage stage;
  private int stageSequence;
  private String status = "NOT_STARTED";
  private Instant stageOpenedAt;
  private Instant stageDeadlineAt;
  private boolean deadlineReached;
  private long processRevision;
  private long roomRevision;
  private long lastCommittedEventSequence;
  private long duplicateSignalCount;
  private long rejectedSignalCount;
  private String protocolErrorCode;
  private String lastReceiptId;
  private String lastReceiptHash;
  private String agentResultReceiptId;
  private String handoffReceiptId;
  private String handoffReceiptHash;
  private String handoffParentId;
  private String handoffParentHash;
  private CancellationScope activeTimerScope;
  private Promise<Void> activeTimer;
  private Promise<Void> activeTimerCallback;

  @Override
  public HearingRoomSnapshot run(HearingRoomStart start) {
    if (this.start != null) {
      throw new IllegalStateException("Hearing room workflow was initialized more than once");
    }
    this.start = Objects.requireNonNull(start, "start must not be null");
    stage = HearingWorkflowStage.COURT_PREPARING;
    stageSequence = stage.sequence();
    stageOpenedAt = start.openedAt();
    processRevision = start.initialProcessRevision();
    roomRevision = start.initialRoomRevision();
    if (isTargetChild()) {
      status = "ACTIVATING";
      activation.awaitActivation(
          new TargetHearingBootstrapActivities.ActivationRequest(
              start.tenantSurrogate(),
              start.caseId(),
              start.flowInstanceId(),
              start.epochId(),
              start.roomEpoch(),
              start.fencingToken(),
              start.initialProcessRevision(),
              start.initialRoomRevision(),
              Workflow.getInfo().getWorkflowId(),
              Workflow.getInfo().getRunId(),
              start.workflowBuildId()));
    }
    status = "RUNNING";

    while (stage != HearingWorkflowStage.CLOSED && "RUNNING".equals(status)) {
      schedulePartyDeadlineIfRequired();
      if (inbox.isEmpty() && isTargetChild() && formalizationRequired()) {
        executeFormalization();
        continue;
      }
      Workflow.await(() -> !inbox.isEmpty());
      drainInboxInHistoryOrder();
    }
    if ("FAILED".equals(status)) {
      cancelActiveTimer();
    }
    Workflow.await(Workflow::isEveryHandlerFinished);
    return state();
  }

  @Override
  public void stageCompleted(HearingStageReceipt receipt) {
    enqueueReceipt(
        WorkflowEvent.stage(Objects.requireNonNull(receipt, "receipt must not be null")));
  }

  @Override
  public void partyTerminal(HearingPartyTerminalReceipt receipt) {
    enqueueReceipt(
        WorkflowEvent.party(Objects.requireNonNull(receipt, "receipt must not be null")));
  }

  @Override
  public void partyCommandAccepted(HearingPartyCommand command) {
    enqueueReceipt(
        WorkflowEvent.partyCommand(Objects.requireNonNull(command, "command must not be null")));
  }

  @Override
  public void agentRunFinalized(TargetRoomAgentRunFinalizationReceipt receipt) {
    enqueueReceipt(
        WorkflowEvent.agentRunFinalization(
            Objects.requireNonNull(receipt, "receipt must not be null")));
  }

  @Override
  public HearingRoomSnapshot state() {
    Map<String, HearingPartyTerminalReceipt.TerminalStatus> terminalProjection =
        new LinkedHashMap<>();
    partyTerminals.forEach(
        (participantId, receipt) -> terminalProjection.put(participantId, receipt.terminalStatus()));
    return new HearingRoomSnapshot(
        "hearing-room-snapshot.v1",
        start == null ? null : start.tenantSurrogate(),
        start == null ? null : start.caseId(),
        start == null ? null : start.roomId(),
        start == null ? null : start.flowInstanceId(),
        start == null ? null : start.epochId(),
        start == null ? null : start.writerMode(),
        start == null ? 0 : start.roomEpoch(),
        start == null ? 0 : start.fencingToken(),
        stage,
        stageSequence,
        status,
        stageOpenedAt,
        stageDeadlineAt,
        deadlineReached,
        terminalProjection,
        new ArrayList<>(timeoutRequired),
        orderedOperationKeys,
        processRevision,
        roomRevision,
        lastCommittedEventSequence,
        duplicateSignalCount,
        rejectedSignalCount,
        protocolErrorCode,
        new ArrayList<>(pendingReceipts.keySet()),
        observedReceipts.size(),
        lastReceiptId,
        lastReceiptHash,
        agentResultReceiptId,
        handoffReceiptId,
        handoffReceiptHash,
        new ArrayList<>(agentRunFinalizationReceipts.values()));
  }

  private void drainInboxInHistoryOrder() {
    while (stage != HearingWorkflowStage.CLOSED
        && "RUNNING".equals(status)
        && !inbox.isEmpty()) {
      WorkflowEvent event = inbox.removeFirst();
      if (event.timerStage() != null) {
        processDeadline(event.timerStage(), event.timerSequence());
      } else if (event.agentRunFinalizationReceipt() != null) {
        processAgentRunFinalizationReceipt(event.agentRunFinalizationReceipt());
      } else if (event.partyCommand() != null) {
        processPartyCommand(event.partyCommand());
      } else {
        queueCommittedReceipt(event);
      }
      drainCommittedReceipts();
    }
  }

  private void enqueueReceipt(WorkflowEvent event) {
    if (inbox.size() >= MAX_INBOX_RECEIPTS) {
      reject("HEARING_RECEIPT_INBOX_LIMIT");
      return;
    }
    inbox.addLast(event);
  }

  private void queueCommittedReceipt(WorkflowEvent event) {
    HearingCommittedReceipt committed = event.committed();
    Object signal = event.signal();
    Object previous = observedReceipts.get(committed.receiptId());
    if (previous != null) {
      if (previous.equals(signal)) {
        duplicateSignalCount++;
      } else {
        reject("HEARING_RECEIPT_ID_PAYLOAD_CONFLICT");
      }
      return;
    }
    if (observedReceipts.size() >= MAX_ACCEPTED_RECEIPTS) {
      reject("HEARING_ACCEPTED_RECEIPT_LIMIT");
      return;
    }
    if (!committed.matches(start)) {
      reject("HEARING_RECEIPT_AUTHORITY_MISMATCH");
      return;
    }
    if (committed.processRevision() <= processRevision) {
      reject("HEARING_RECEIPT_STALE_REVISION");
      return;
    }
    String previousEvent = observedEventSequences.get(committed.committedEventSequence());
    if (previousEvent != null && !previousEvent.equals(committed.receiptId())) {
      reject("HEARING_COMMITTED_EVENT_SEQUENCE_CONFLICT");
      return;
    }
    WorkflowEvent previousRevision = pendingReceipts.get(committed.processRevision());
    if (previousRevision != null && !previousRevision.equals(event)) {
      reject("HEARING_RECEIPT_REVISION_CONFLICT");
      return;
    }
    if (previousRevision == null && pendingReceipts.size() >= MAX_PENDING_RECEIPTS) {
      reject("HEARING_PENDING_RECEIPT_LIMIT");
      return;
    }
    observedReceipts.put(committed.receiptId(), signal);
    observedEventSequences.put(committed.committedEventSequence(), committed.receiptId());
    pendingReceipts.put(committed.processRevision(), event);
  }

  private void processAgentRunFinalizationReceipt(
      TargetRoomAgentRunFinalizationReceipt receipt) {
    TargetRoomAgentRunFinalizationReceipt previous =
        agentRunFinalizationReceipts.get(receipt.commandId());
    if (previous != null) {
      if (previous.equals(receipt)) {
        duplicateSignalCount++;
      } else {
        failProtocol("HEARING_AGENT_RUN_RECEIPT_COMMAND_CONFLICT");
      }
      return;
    }
    if (!receipt.matchesHearingRoom(
        start.tenantSurrogate(), start.caseId(), start.roomEpoch(), start.fencingToken())) {
      failProtocol("HEARING_AGENT_RUN_RECEIPT_AUTHORITY_MISMATCH");
      return;
    }
    if (receipt.processRevision() != processRevision
        || receipt.roomRevision() != roomRevision
        || receipt.stageSequence() != stageSequence) {
      failProtocol("HEARING_AGENT_RUN_RECEIPT_STALE_REVISION");
      return;
    }
    if (!stage.requiresAgentRun()) {
      failProtocol("HEARING_AGENT_RUN_RECEIPT_STAGE_INVALID");
      return;
    }
    if (agentRunFinalizationReceipts.size() >= MAX_AGENT_RUN_FINALIZATION_RECEIPTS) {
      failProtocol("HEARING_AGENT_RUN_RECEIPT_LIMIT");
      return;
    }
    agentRunFinalizationReceipts.put(receipt.commandId(), receipt);
  }

  private void processPartyCommand(HearingPartyCommand partyCommand) {
    HearingPartyCommand previous = observedPartyCommands.get(partyCommand.command().commandId());
    if (previous != null) {
      if (previous.equals(partyCommand)) {
        duplicateSignalCount++;
      } else {
        failProtocol("HEARING_PARTY_COMMAND_ID_PAYLOAD_CONFLICT");
      }
      return;
    }
    if (!stage.isPartyWait()
        || partyCommand.fencingToken() != start.fencingToken()
        || partyCommand.expectedProcessRevision() != processRevision
        || partyCommand.expectedRoomRevision() != roomRevision
        || !matchesPartyCommand(stage, partyCommand.command().commandType())) {
      failProtocol("HEARING_PARTY_COMMAND_STAGE_OR_COORDINATE_MISMATCH");
      return;
    }
    observedPartyCommands.put(partyCommand.command().commandId(), partyCommand);
    String participant = participantFor(partyCommand.command());
    String operationKey =
        HearingOperationKeys.partyTerminal(
            start.tenantSurrogate(),
            start.caseId(),
            start.roomEpoch(),
            stage,
            stageSequence,
            participant,
            partyCommand.command().commandId());
    TargetHearingFormalizationActivities.TransitionRequest transition = transition(operationKey);
    HearingPartyTerminalReceipt receipt =
        formalization
            .formalizeParty(
                new TargetHearingFormalizationActivities.PartyRequest(
                    transition, partyCommand.command()))
            .receipt();
    queueCommittedReceipt(WorkflowEvent.party(receipt));
  }

  private boolean formalizationRequired() {
    if (stage.isPartyWait()) return false;
    if (stage == HearingWorkflowStage.HUMAN_REVIEW_OPEN) {
      return true;
    }
    return stage != HearingWorkflowStage.CLOSED;
  }

  private boolean isTargetChild() {
    return Workflow.getInfo().getParentWorkflowId().filter(id -> !id.isBlank()).isPresent();
  }

  private void executeFormalization() {
    if (stage.sequence() <= HearingWorkflowStage.EVIDENCE_INTRODUCTION.sequence()) {
      stageCompleted(formalization.bootstrapNext(transition(stageOperationKey())).receipt());
      return;
    }
    if (stage.requiresAgentRun()) {
      ExecuteAgentRunRequest request =
          formalization.prepareAgentStage(transition(agentOperationKey())).request();
      ExecuteAgentRunResult result = launchAgentRunChild(request);
      TargetHearingFormalizationActivities.AgentStageResult formalResult =
          formalization.finalizeAgentStage(
              new TargetHearingFormalizationActivities.AgentStageFinalizationRequest(
                  transition(agentOperationKey()), request, result));
      stageCompleted(formalResult.finalizerReceipt());
      return;
    }
    if (stage == HearingWorkflowStage.DOSSIER_FREEZING) {
      stageCompleted(formalization.freezeDossier(transition(stageOperationKey())).receipt());
      return;
    }
    if (stage == HearingWorkflowStage.HUMAN_REVIEW_OPEN) {
      if (handoffReceiptId == null) {
        int version = Workflow.getVersion(
            HANDOFF_OPERATION_KEY_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
        String operationKey = version == Workflow.DEFAULT_VERSION
            ? "hearing.handoff:" + start.caseId()
            : exactHandoffOperationKey(start, handoffParentId, handoffParentHash);
        stageCompleted(formalization.handoff(transition(operationKey)).receipt());
      } else {
        stageCompleted(formalization.close(transition(HearingOperationKeys.close(
            start.tenantSurrogate(), start.caseId(), start.roomEpoch(), handoffReceiptHash))).receipt());
      }
    }
  }

  private TargetHearingFormalizationActivities.TransitionRequest transition(String operationKey) {
    return new TargetHearingFormalizationActivities.TransitionRequest(
        start, stage, stageSequence, processRevision, roomRevision, start.fencingToken(), operationKey);
  }

  private String stageOperationKey() {
    return HearingOperationKeys.stageCompletion(
        start.tenantSurrogate(), start.caseId(), start.roomEpoch(), stage, stageSequence);
  }

  private String agentOperationKey() {
    return "hearing.agent-stage:"
        + start.tenantSurrogate() + ':' + start.caseId() + ':' + start.roomEpoch() + ':' + stageSequence;
  }

  private ExecuteAgentRunResult launchAgentRunChild(ExecuteAgentRunRequest request) {
    long remainingMillis = request.command().deadlineAt().toEpochMilli() - Workflow.currentTimeMillis();
    if (remainingMillis <= 0) {
      throw new IllegalArgumentException("target Hearing AgentRun deadline has elapsed");
    }
    AgentRunWorkflow child =
        Workflow.newChildWorkflowStub(
            AgentRunWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(
                    AgentRunWorkflowIds.forLogicalRun(request.logicalRunId()))
                .setTaskQueue(AGENT_EXECUTION)
                .setWorkflowExecutionTimeout(Duration.ofMillis(remainingMillis))
                .setWorkflowRunTimeout(Duration.ofMillis(remainingMillis))
                .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setParentClosePolicy(PARENT_CLOSE_POLICY_ABANDON)
                .build());
    return child.run(request);
  }

  private static boolean matchesPartyCommand(HearingWorkflowStage stage, CommandType commandType) {
    return (stage == HearingWorkflowStage.PARTY_ANSWERS_OPEN
            && commandType == CommandType.HEARING_STATEMENT)
        || (stage == HearingWorkflowStage.PARTY_EVIDENCE_OPEN
            && commandType == CommandType.HEARING_EVIDENCE_BATCH);
  }

  private String participantFor(com.example.dispute.workflow.contract.v1.CaseCommandRef command) {
    return switch (command.actorRef().actorRole()) {
      case USER -> start.initiatorParticipantId();
      case MERCHANT -> start.respondentParticipantId();
      default -> throw new IllegalArgumentException("Hearing party command requires a USER or MERCHANT actor");
    };
  }

  private void drainCommittedReceipts() {
    while (stage != HearingWorkflowStage.CLOSED && "RUNNING".equals(status)) {
      long expectedRevision = processRevision + 1;
      WorkflowEvent event = pendingReceipts.get(expectedRevision);
      if (event == null) {
        return;
      }
      HearingCommittedReceipt committed = event.committed();
      if (committed.sourceProcessRevision() != processRevision
          || committed.sourceRoomRevision() != roomRevision
          || committed.roomRevision() != roomRevision + 1
          || committed.committedEventSequence() <= lastCommittedEventSequence) {
        failProtocol("HEARING_RECEIPT_CAUSAL_CHAIN_MISMATCH");
        return;
      }
      if (event.partyReceipt() != null
          && event.partyReceipt().terminalStatus()
              == HearingPartyTerminalReceipt.TerminalStatus.AUTO_TIMEOUT
          && !deadlineReached) {
        return;
      }

      boolean applied = event.stageReceipt() != null
          ? processStageReceipt(event.stageReceipt())
          : processPartyReceipt(event.partyReceipt());
      if (!applied) {
        return;
      }
      pendingReceipts.remove(expectedRevision);
      processRevision = committed.processRevision();
      roomRevision = committed.roomRevision();
      lastCommittedEventSequence = committed.committedEventSequence();
      lastReceiptId = committed.receiptId();
      lastReceiptHash = committed.receiptHash();
      notifyParentProgress(committed);
    }
  }

  private boolean processStageReceipt(HearingStageReceipt receipt) {
    HearingCommittedReceipt committed = receipt.committed();
    if (committed.sourceStage() != stage || committed.sourceStageSequence() != stageSequence) {
      failProtocol("HEARING_STAGE_RECEIPT_STAGE_MISMATCH");
      return false;
    }
    if (!validateTargetDeadline(committed)) {
      return false;
    }

    boolean sameStage = committed.stage() == stage;
    if (stage.isPartyWait()) {
      if (sameStage
          || partyTerminals.size() != 2
          || committed.stage() != stage.next()
          || (committed.operationType() != HearingAuthorityCommit.OperationType.STAGE
              && committed.operationType() != HearingAuthorityCommit.OperationType.FINALIZE)) {
        failProtocol("HEARING_PARTY_STAGE_TRANSITION_RECEIPT_INVALID");
        return false;
      }
    } else if (sameStage) {
      if (committed.operationType() == HearingAuthorityCommit.OperationType.AGENT_RESULT) {
        if (!stage.requiresAgentRun()
            || agentResultReceiptId != null
            || !HearingOperationKeys.matchesAgent(
                committed.operationKey(),
                start.tenantSurrogate(),
                start.caseId(),
                start.roomEpoch(),
                stageSequence,
                stage.agentOperation())) {
          failProtocol("HEARING_AGENT_RESULT_RECEIPT_INVALID");
          return false;
        }
      } else if (committed.operationType() == HearingAuthorityCommit.OperationType.HANDOFF) {
        if (stage != HearingWorkflowStage.HUMAN_REVIEW_OPEN || handoffReceiptId != null) {
          failProtocol("HEARING_HANDOFF_RECEIPT_INVALID");
          return false;
        }
      } else {
        failProtocol("HEARING_NON_ADVANCING_STAGE_RECEIPT_INVALID");
        return false;
      }
    } else {
      if (committed.stage() != stage.next()) {
        failProtocol("HEARING_STAGE_RECEIPT_NON_ADJACENT");
        return false;
      }
      boolean closing = stage == HearingWorkflowStage.HUMAN_REVIEW_OPEN
          && committed.stage() == HearingWorkflowStage.CLOSED
          && committed.operationType() == HearingAuthorityCommit.OperationType.CLOSE
          && handoffReceiptHash != null
          && HearingOperationKeys.close(
                  start.tenantSurrogate(),
                  start.caseId(),
                  start.roomEpoch(),
                  handoffReceiptHash)
              .equals(committed.operationKey());
      boolean agentFinalizer = stage.requiresAgentRun()
          && committed.operationType() == HearingAuthorityCommit.OperationType.FINALIZE;
      boolean dossierFinalizer = stage == HearingWorkflowStage.DOSSIER_FREEZING
          && committed.operationType() == HearingAuthorityCommit.OperationType.FINALIZE;
      boolean deterministicStage = stage.sequence() <= 3
          && committed.operationType() == HearingAuthorityCommit.OperationType.STAGE;
      if (!closing && !agentFinalizer && !dossierFinalizer && !deterministicStage) {
        failProtocol("HEARING_STAGE_RECEIPT_OPERATION_INVALID");
        return false;
      }
    }

    if (!sameStage
        && isTargetChild()
        && stage == HearingWorkflowStage.JUDGE_V2_GENERATING
        && committed.stage() == HearingWorkflowStage.HUMAN_REVIEW_OPEN
        && !bindHandoffParent(committed)) {
      return false;
    }

    orderedOperationKeys.add(committed.operationKey());
    if (sameStage
        && committed.operationType() == HearingAuthorityCommit.OperationType.AGENT_RESULT) {
      agentResultReceiptId = committed.receiptId();
    } else if (sameStage
        && committed.operationType() == HearingAuthorityCommit.OperationType.HANDOFF) {
      handoffReceiptId = committed.receiptId();
      handoffReceiptHash = committed.receiptHash();
    } else if (!sameStage) {
      advanceTo(committed.stage(), committed.stageDeadlineAt());
    }
    return true;
  }

  private boolean bindHandoffParent(HearingCommittedReceipt committed) {
    String prefix = "urn:hearing:artifact:";
    if (!committed.resultRef().startsWith(prefix)
        || committed.resultRef().length() == prefix.length()
        || handoffParentId != null
        || handoffParentHash != null) {
      failProtocol("HEARING_HANDOFF_PARENT_ARTIFACT_INVALID");
      return false;
    }
    String parentId = committed.resultRef().substring(prefix.length());
    try {
      exactHandoffOperationKey(start, parentId, committed.resultHash());
    } catch (RuntimeException failure) {
      failProtocol("HEARING_HANDOFF_PARENT_ARTIFACT_INVALID");
      return false;
    }
    handoffParentId = parentId;
    handoffParentHash = committed.resultHash();
    return true;
  }

  public static String exactHandoffOperationKey(
      HearingRoomStart start, String judgeV2Id, String judgeV2Hash) {
    Objects.requireNonNull(start, "start must not be null");
    return HearingOperationKeys.handoff(
        start.tenantSurrogate(), start.caseId(), start.roomEpoch(), judgeV2Id, judgeV2Hash);
  }

  private boolean processPartyReceipt(HearingPartyTerminalReceipt receipt) {
    HearingCommittedReceipt committed = receipt.committed();
    if (!stage.isPartyWait()
        || committed.sourceStage() != stage
        || committed.sourceStageSequence() != stageSequence) {
      failProtocol("HEARING_PARTY_RECEIPT_STAGE_MISMATCH");
      return false;
    }
    if (!isExpectedParticipant(receipt.participantId())) {
      failProtocol("HEARING_PARTY_RECEIPT_PARTICIPANT_MISMATCH");
      return false;
    }
    if (partyTerminals.containsKey(receipt.participantId())) {
      failProtocol("HEARING_PARTY_ALREADY_TERMINAL");
      return false;
    }
    if (!validateTargetDeadline(committed)) {
      return false;
    }
    boolean sameStage = committed.stage() == stage;
    if (!sameStage && committed.stage() != stage.next()) {
      failProtocol("HEARING_PARTY_RECEIPT_TARGET_STAGE_INVALID");
      return false;
    }
    if (!sameStage && partyTerminals.size() != 1) {
      failProtocol("HEARING_PARTY_ADVANCE_BEFORE_BOTH_TERMINAL");
      return false;
    }

    partyTerminals.put(receipt.participantId(), receipt);
    timeoutRequired.remove(receipt.participantId());
    orderedOperationKeys.add(committed.operationKey());
    if (!sameStage) {
      advanceTo(committed.stage(), committed.stageDeadlineAt());
    } else if (deadlineReached
        && receipt.terminalStatus() != HearingPartyTerminalReceipt.TerminalStatus.AUTO_TIMEOUT) {
      inbox.addLast(WorkflowEvent.timer(stage, stageSequence));
    }
    return true;
  }

  private boolean validateTargetDeadline(HearingCommittedReceipt committed) {
    if (committed.stage().isPartyWait()) {
      Instant receiptDeadline = committed.stageDeadlineAt();
      if (receiptDeadline == null
          || !receiptDeadline.isAfter(start.openedAt())
          || receiptDeadline.isAfter(start.hearingDeadlineAt())) {
        failProtocol("HEARING_RECEIPT_DEADLINE_INVALID");
        return false;
      }
      if (committed.stage() == stage
          && stageDeadlineAt != null
          && !stageDeadlineAt.equals(receiptDeadline)) {
        failProtocol("HEARING_RECEIPT_DEADLINE_CONFLICT");
        return false;
      }
    }
    return true;
  }

  private void processDeadline(HearingWorkflowStage timerStage, int timerSequence) {
    clearActiveTimer();
    if (stage != timerStage || stageSequence != timerSequence || !stage.isPartyWait()) {
      return;
    }
    if (!deadlineReached) {
      deadlineReached = true;
      orderedOperationKeys.add(
          HearingOperationKeys.partyDeadline(
              start.tenantSurrogate(), start.caseId(), start.roomEpoch(), stage, stageSequence));
    }
    if (!partyTerminals.containsKey(start.initiatorParticipantId())) {
      timeoutRequired.add(start.initiatorParticipantId());
    }
    if (!partyTerminals.containsKey(start.respondentParticipantId())) {
      timeoutRequired.add(start.respondentParticipantId());
    }
    if (!isTargetChild()) {
      return;
    }
    int version = Workflow.getVersion(
        PARTY_TIMEOUT_FORMALIZATION_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
    if (version == Workflow.DEFAULT_VERSION) {
      return;
    }
    formalizeRequiredTimeouts();
  }

  private void formalizeRequiredTimeouts() {
    List<String> participants = List.of(
        start.initiatorParticipantId(), start.respondentParticipantId());
    for (String participantId : participants) {
      if (!stage.isPartyWait() || partyTerminals.containsKey(participantId)) {
        continue;
      }
      String operationKey = HearingOperationKeys.partyTerminal(
          start.tenantSurrogate(), start.caseId(), start.roomEpoch(), stage, stageSequence,
          participantId, "AUTO_TIMEOUT");
      TargetHearingFormalizationActivities.TimeoutResult result =
          formalization.formalizeTimeout(
              new TargetHearingFormalizationActivities.TimeoutRequest(
                  transition(operationKey), participantId));
      if (result.pendingSubmittedAction()) {
        return;
      }
      queueCommittedReceipt(WorkflowEvent.party(result.receipt()));
      drainCommittedReceipts();
      if (!"RUNNING".equals(status) || !stage.isPartyWait()) {
        return;
      }
    }
  }

  private void schedulePartyDeadlineIfRequired() {
    if (!stage.isPartyWait() || activeTimerScope != null || deadlineReached) {
      return;
    }
    if (stageDeadlineAt == null) {
      throw new IllegalStateException("party-wait stage requires a committed absolute deadline");
    }
    long delayMillis = Math.max(0, stageDeadlineAt.toEpochMilli() - Workflow.currentTimeMillis());
    HearingWorkflowStage scheduledStage = stage;
    int scheduledSequence = stageSequence;
    activeTimerScope =
        Workflow.newCancellationScope(
            () -> {
              activeTimer = Workflow.newTimer(Duration.ofMillis(delayMillis));
              activeTimerCallback =
                  activeTimer.handle(
                      (ignored, failure) -> {
                        if (failure == null) {
                          inbox.addLast(WorkflowEvent.timer(scheduledStage, scheduledSequence));
                        }
                        return null;
                      });
            });
    activeTimerScope.run();
  }

  private void cancelActiveTimer() {
    if (activeTimerScope != null && activeTimer != null && !activeTimer.isCompleted()) {
      activeTimerScope.cancel();
    }
    clearActiveTimer();
  }

  private void notifyParentProgress(HearingCommittedReceipt receipt) {
    if (!isTargetChild()) {
      return;
    }
    CaseProcessWorkflow parent =
        Workflow.newExternalWorkflowStub(
            CaseProcessWorkflow.class,
            com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.caseWorkflowId(
                start.tenantSurrogate(), start.caseId()));
    parent.targetRoomProgressed(
        new TargetRoomProgressReceipt(
            com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.HEARING,
            start.roomEpoch(),
            start.fencingToken(),
            processRevision,
            roomRevision,
            receipt.receiptId(),
            receipt.receiptHash()));
  }

  private void clearActiveTimer() {
    activeTimerScope = null;
    activeTimer = null;
    activeTimerCallback = null;
  }

  private void advanceTo(HearingWorkflowStage target, Instant targetDeadlineAt) {
    cancelActiveTimer();
    if (stage.next() != target) {
      throw new IllegalStateException("Hearing receipt target is not the adjacent stage");
    }
    stage = target;
    stageSequence = target.sequence();
    stageOpenedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
    stageDeadlineAt = targetDeadlineAt;
    deadlineReached = false;
    partyTerminals.clear();
    timeoutRequired.clear();
    agentResultReceiptId = null;
    if (target != HearingWorkflowStage.CLOSED) {
      handoffReceiptId = null;
      handoffReceiptHash = null;
    }
    protocolErrorCode = null;
    if (target == HearingWorkflowStage.CLOSED) {
      status = "CLOSED";
    }
  }

  private boolean isExpectedParticipant(String participantId) {
    return start.initiatorParticipantId().equals(participantId)
        || start.respondentParticipantId().equals(participantId);
  }

  private void reject(String code) {
    rejectedSignalCount++;
    protocolErrorCode = code;
  }

  private void failProtocol(String code) {
    reject(code);
    status = "FAILED";
  }

  private record WorkflowEvent(
      HearingStageReceipt stageReceipt,
      HearingPartyTerminalReceipt partyReceipt,
      HearingPartyCommand partyCommand,
      TargetRoomAgentRunFinalizationReceipt agentRunFinalizationReceipt,
      HearingWorkflowStage timerStage,
      int timerSequence) {

    private WorkflowEvent {
      int populated =
          (stageReceipt == null ? 0 : 1)
              + (partyReceipt == null ? 0 : 1)
              + (partyCommand == null ? 0 : 1)
              + (agentRunFinalizationReceipt == null ? 0 : 1)
              + (timerStage == null ? 0 : 1);
      if (populated != 1) {
        throw new IllegalArgumentException("Workflow event must contain exactly one payload");
      }
    }

    private HearingCommittedReceipt committed() {
      if (stageReceipt != null) {
        return stageReceipt.committed();
      }
      if (partyReceipt != null) {
        return partyReceipt.committed();
      }
      throw new IllegalStateException("timer event has no committed receipt");
    }

    private Object signal() {
      return stageReceipt != null ? stageReceipt : partyReceipt;
    }

    private static WorkflowEvent stage(HearingStageReceipt receipt) {
      return new WorkflowEvent(receipt, null, null, null, null, 0);
    }

    private static WorkflowEvent party(HearingPartyTerminalReceipt receipt) {
      return new WorkflowEvent(null, receipt, null, null, null, 0);
    }

    private static WorkflowEvent partyCommand(HearingPartyCommand command) {
      return new WorkflowEvent(null, null, command, null, null, 0);
    }

    private static WorkflowEvent agentRunFinalization(
        TargetRoomAgentRunFinalizationReceipt receipt) {
      return new WorkflowEvent(null, null, null, receipt, null, 0);
    }

    private static WorkflowEvent timer(HearingWorkflowStage stage, int sequence) {
      return new WorkflowEvent(null, null, null, null, stage, sequence);
    }
  }
}

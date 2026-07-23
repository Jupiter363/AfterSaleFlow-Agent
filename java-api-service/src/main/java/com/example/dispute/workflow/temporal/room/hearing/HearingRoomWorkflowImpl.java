package com.example.dispute.workflow.temporal.room.hearing;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
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

  private final ArrayDeque<WorkflowEvent> inbox = new ArrayDeque<>();
  private final Map<String, Object> observedReceipts = new LinkedHashMap<>();
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
    status = "RUNNING";

    while (stage != HearingWorkflowStage.CLOSED && "RUNNING".equals(status)) {
      schedulePartyDeadlineIfRequired();
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
        handoffReceiptHash);
  }

  private void drainInboxInHistoryOrder() {
    while (stage != HearingWorkflowStage.CLOSED
        && "RUNNING".equals(status)
        && !inbox.isEmpty()) {
      WorkflowEvent event = inbox.removeFirst();
      if (event.timerStage() != null) {
        processDeadline(event.timerStage(), event.timerSequence());
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
    if (deadlineReached) {
      return;
    }
    deadlineReached = true;
    orderedOperationKeys.add(
        HearingOperationKeys.partyDeadline(
            start.tenantSurrogate(), start.caseId(), start.roomEpoch(), stage, stageSequence));
    if (!partyTerminals.containsKey(start.initiatorParticipantId())) {
      timeoutRequired.add(start.initiatorParticipantId());
    }
    if (!partyTerminals.containsKey(start.respondentParticipantId())) {
      timeoutRequired.add(start.respondentParticipantId());
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
      HearingWorkflowStage timerStage,
      int timerSequence) {

    private WorkflowEvent {
      int populated =
          (stageReceipt == null ? 0 : 1)
              + (partyReceipt == null ? 0 : 1)
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
      return new WorkflowEvent(receipt, null, null, 0);
    }

    private static WorkflowEvent party(HearingPartyTerminalReceipt receipt) {
      return new WorkflowEvent(null, receipt, null, 0);
    }

    private static WorkflowEvent timer(HearingWorkflowStage stage, int sequence) {
      return new WorkflowEvent(null, null, stage, sequence);
    }
  }
}

package com.example.dispute.workflow.temporal.room.hearing;

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

/** Deterministic 15-stage Hearing process kernel. It contains no domain or model side effects. */
public final class HearingRoomWorkflowImpl implements HearingRoomWorkflow {

  private final ArrayDeque<WorkflowEvent> inbox = new ArrayDeque<>();
  private final Map<String, Object> observedRequests = new LinkedHashMap<>();
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
    stageOpenedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
    processRevision = start.initialProcessRevision();
    roomRevision = start.initialRoomRevision();
    status = "RUNNING";

    while (stage != HearingWorkflowStage.CLOSED) {
      schedulePartyDeadlineIfRequired();
      Workflow.await(() -> !inbox.isEmpty());
      drainInboxInHistoryOrder();
    }
    Workflow.await(Workflow::isEveryHandlerFinished);
    return state();
  }

  @Override
  public void stageCompleted(HearingStageReceipt receipt) {
    inbox.addLast(
        WorkflowEvent.stage(
            Objects.requireNonNull(receipt, "receipt must not be null")));
  }

  @Override
  public void partyTerminal(HearingPartyTerminalReceipt receipt) {
    inbox.addLast(
        WorkflowEvent.party(
            Objects.requireNonNull(receipt, "receipt must not be null")));
  }

  @Override
  public HearingRoomSnapshot state() {
    Map<String, HearingPartyTerminalReceipt.TerminalStatus> terminalProjection =
        new LinkedHashMap<>();
    partyTerminals.forEach(
        (participantId, receipt) ->
            terminalProjection.put(participantId, receipt.terminalStatus()));
    return new HearingRoomSnapshot(
        "hearing-room-snapshot.v1",
        start == null ? null : start.tenantSurrogate(),
        start == null ? null : start.caseId(),
        start == null ? null : start.roomId(),
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
        protocolErrorCode);
  }

  private void drainInboxInHistoryOrder() {
    while (stage != HearingWorkflowStage.CLOSED && !inbox.isEmpty()) {
      WorkflowEvent event = inbox.removeFirst();
      if (event.stageReceipt() != null) {
        processStageReceipt(event.stageReceipt());
      } else if (event.partyReceipt() != null) {
        processPartyReceipt(event.partyReceipt());
      } else {
        processDeadline(event.timerStage(), event.timerSequence());
      }
    }
  }

  private void processStageReceipt(HearingStageReceipt receipt) {
    if (isDuplicateOrConflict(receipt.receiptId(), receipt)) {
      return;
    }
    if (stage == null
        || stage.isPartyWait()
        || receipt.stage() != stage
        || receipt.stageSequence() != stageSequence) {
      reject("HEARING_STAGE_RECEIPT_STAGE_MISMATCH");
      return;
    }
    String expected =
        HearingOperationKeys.stageCompletion(
            start.caseId(), start.roomEpoch(), stage, stageSequence);
    if (!expected.equals(receipt.operationKey())) {
      reject("HEARING_STAGE_RECEIPT_OPERATION_KEY_MISMATCH");
      return;
    }
    if (!acceptMonotonicReceipt(
        receipt.processRevision(),
        receipt.roomRevision(),
        receipt.committedEventSequence())) {
      return;
    }
    observedRequests.put(receipt.receiptId(), receipt);
    orderedOperationKeys.add(receipt.operationKey());
    advance();
  }

  private void processPartyReceipt(HearingPartyTerminalReceipt receipt) {
    if (isDuplicateOrConflict(receipt.requestId(), receipt)) {
      return;
    }
    if (stage == null
        || !stage.isPartyWait()
        || receipt.stage() != stage
        || receipt.stageSequence() != stageSequence) {
      reject("HEARING_PARTY_RECEIPT_STAGE_MISMATCH");
      return;
    }
    if (!isExpectedParticipant(receipt.participantId())) {
      reject("HEARING_PARTY_RECEIPT_PARTICIPANT_MISMATCH");
      return;
    }
    String expected =
        HearingOperationKeys.partyTerminal(
            start.caseId(),
            start.roomEpoch(),
            stage,
            stageSequence,
            receipt.participantId(),
            receipt.requestId());
    if (!expected.equals(receipt.operationKey())) {
      reject("HEARING_PARTY_RECEIPT_OPERATION_KEY_MISMATCH");
      return;
    }
    if (partyTerminals.containsKey(receipt.participantId())) {
      reject("HEARING_PARTY_ALREADY_TERMINAL");
      return;
    }
    if (!acceptMonotonicReceipt(
        receipt.processRevision(),
        receipt.roomRevision(),
        receipt.committedEventSequence())) {
      return;
    }
    observedRequests.put(receipt.requestId(), receipt);
    partyTerminals.put(receipt.participantId(), receipt);
    timeoutRequired.remove(receipt.participantId());
    orderedOperationKeys.add(receipt.operationKey());
    if (partyTerminals.size() == 2) {
      advance();
    }
  }

  private boolean isDuplicateOrConflict(String id, Object receipt) {
    Object previous = observedRequests.get(id);
    if (previous == null) {
      return false;
    }
    if (previous.equals(receipt)) {
      duplicateSignalCount++;
    } else {
      reject("HEARING_RECEIPT_ID_PAYLOAD_CONFLICT");
    }
    return true;
  }

  private boolean acceptMonotonicReceipt(
      long nextProcessRevision, long nextRoomRevision, long eventSequence) {
    if (nextProcessRevision <= processRevision
        || nextRoomRevision <= roomRevision
        || eventSequence <= lastCommittedEventSequence) {
      reject("HEARING_RECEIPT_MONOTONICITY_VIOLATION");
      return false;
    }
    processRevision = nextProcessRevision;
    roomRevision = nextRoomRevision;
    lastCommittedEventSequence = eventSequence;
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
            start.caseId(), start.roomEpoch(), stage, stageSequence));
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
    Instant windowDeadline =
        stageOpenedAt.plusSeconds(start.partyStageWindowSeconds());
    stageDeadlineAt =
        windowDeadline.isBefore(start.hearingDeadlineAt())
            ? windowDeadline
            : start.hearingDeadlineAt();
    long delayMillis =
        Math.max(0, stageDeadlineAt.toEpochMilli() - Workflow.currentTimeMillis());
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
                          inbox.addLast(
                              WorkflowEvent.timer(scheduledStage, scheduledSequence));
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

  private void advance() {
    cancelActiveTimer();
    HearingWorkflowStage next = stage.next();
    if (next == null) {
      throw new IllegalStateException("CLOSED has no successor");
    }
    stage = next;
    stageSequence = next.sequence();
    stageOpenedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
    stageDeadlineAt = null;
    deadlineReached = false;
    partyTerminals.clear();
    timeoutRequired.clear();
    protocolErrorCode = null;
    if (next == HearingWorkflowStage.CLOSED) {
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

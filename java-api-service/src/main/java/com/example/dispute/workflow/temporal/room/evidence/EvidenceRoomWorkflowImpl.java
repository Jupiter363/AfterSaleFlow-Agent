package com.example.dispute.workflow.temporal.room.evidence;

import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvidenceRoomWorkflowImpl implements EvidenceRoomWorkflow {

  private final ArrayDeque<EvidenceRoomSignal> inbox = new ArrayDeque<>();
  private final Map<String, EvidenceRoomSignal> observedRequests = new LinkedHashMap<>();
  private final List<String> orderedOperationKeys = new ArrayList<>();

  private EvidenceRoomStart start;
  private EvidenceTimerPlan timerPlan;
  private EvidenceRoomPhase roomPhase = EvidenceRoomPhase.OPEN;
  private String terminalReason;
  private boolean warningSent;
  private Instant warningSentAt;
  private boolean deadlineExpired;
  private EvidenceRoomSignal initiatorCompletion;
  private EvidenceRoomSignal respondentCompletion;
  private String pendingOperationKey;
  private long processRevision;
  private long roomRevision;
  private long duplicateSignalCount;
  private long rejectedSignalCount;
  private String protocolErrorCode;

  @Override
  public EvidenceRoomSnapshot run(EvidenceRoomStart start) {
    if (this.start != null) {
      throw new IllegalStateException("Evidence room workflow was initialized more than once");
    }
    this.start = Objects.requireNonNull(start, "start must not be null");
    timerPlan = EvidenceTimerPlan.from(start);
    processRevision = start.initialProcessRevision();
    roomRevision = start.initialRoomRevision();
    roomPhase = EvidenceRoomPhase.WAITING_PARTIES;

    while (roomPhase != EvidenceRoomPhase.COMPLETED) {
      drainInbox();
      if (bothPartiesCompleted()) {
        complete("BOTH_PARTIES_COMPLETED");
        break;
      }

      if (!warningSent) {
        if (awaitInputBefore(timerPlan.warningAt())) {
          continue;
        }
        warningSent = true;
        warningSentAt = timerPlan.warningAt();
        appendOperation(timerPlan.warningOperationKey());
        continue;
      }

      if (awaitInputBefore(timerPlan.deadlineAt())) {
        continue;
      }
      deadlineExpired = true;
      appendOperation(timerPlan.expiryOperationKey());
      complete("DEADLINE_EXPIRED");
    }

    Workflow.await(Workflow::isEveryHandlerFinished);
    return state();
  }

  @Override
  public void partyCompleted(EvidenceRoomSignal signal) {
    inbox.addLast(Objects.requireNonNull(signal, "signal must not be null"));
  }

  @Override
  public EvidenceRoomSnapshot state() {
    return new EvidenceRoomSnapshot(
        "evidence-room-snapshot.v1",
        start == null ? null : start.tenantSurrogate(),
        start == null ? null : start.caseId(),
        start == null ? null : start.roomId(),
        start == null ? 0 : start.roomEpoch(),
        start == null ? 0 : start.fencingToken(),
        roomPhase,
        terminalReason,
        start == null ? null : start.openedAt(),
        start == null ? null : start.originalDeadlineAt(),
        start == null ? 0 : start.deadlineRevision(),
        timerPlan == null ? null : timerPlan.warningAt(),
        warningSent,
        warningSentAt,
        deadlineExpired,
        initiatorCompletion != null,
        respondentCompletion != null,
        initiatorCompletion == null ? null : initiatorCompletion.completionRequestId(),
        respondentCompletion == null ? null : respondentCompletion.completionRequestId(),
        orderedOperationKeys,
        pendingOperationKey,
        processRevision,
        roomRevision,
        duplicateSignalCount,
        rejectedSignalCount,
        protocolErrorCode);
  }

  private void drainInbox() {
    while (!inbox.isEmpty()) {
      processSignal(inbox.removeFirst());
    }
  }

  private void processSignal(EvidenceRoomSignal signal) {
    EvidenceRoomSignal observed = observedRequests.get(signal.completionRequestId());
    if (observed != null) {
      if (observed.equals(signal)) {
        duplicateSignalCount++;
      } else {
        reject("EVIDENCE_COMPLETION_REQUEST_CONFLICT");
      }
      return;
    }
    observedRequests.put(signal.completionRequestId(), signal);

    String expectedKey =
        EvidenceOperationKeys.partyComplete(
            start.caseId(),
            start.roomEpoch(),
            signal.participantId(),
            signal.completionRequestId());
    if (!expectedKey.equals(signal.operationKey())) {
      reject("EVIDENCE_COMPLETION_OPERATION_KEY_MISMATCH");
      return;
    }

    if (start.initiatorParticipantId().equals(signal.participantId())) {
      if (initiatorCompletion != null) {
        duplicateSignalCount++;
        return;
      }
      initiatorCompletion = signal;
    } else if (start.respondentParticipantId().equals(signal.participantId())) {
      if (respondentCompletion != null) {
        duplicateSignalCount++;
        return;
      }
      respondentCompletion = signal;
    } else {
      reject("EVIDENCE_COMPLETION_PARTICIPANT_MISMATCH");
      return;
    }
    appendOperation(signal.operationKey());
  }

  private boolean bothPartiesCompleted() {
    return initiatorCompletion != null && respondentCompletion != null;
  }

  private boolean awaitInputBefore(Instant boundary) {
    long remainingMillis = boundary.toEpochMilli() - Workflow.currentTimeMillis();
    if (remainingMillis <= 0) {
      return false;
    }
    return Workflow.await(Duration.ofMillis(remainingMillis), () -> !inbox.isEmpty());
  }

  private void appendOperation(String operationKey) {
    orderedOperationKeys.add(operationKey);
    pendingOperationKey = operationKey;
  }

  private void reject(String errorCode) {
    rejectedSignalCount++;
    protocolErrorCode = errorCode;
  }

  private void complete(String reason) {
    terminalReason = reason;
    roomPhase = EvidenceRoomPhase.COMPLETED;
    pendingOperationKey = null;
  }
}

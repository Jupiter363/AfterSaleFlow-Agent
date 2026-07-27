package com.example.dispute.workflow.temporal.room.evidence;

import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvidenceRoomWorkflowImpl implements EvidenceRoomWorkflow {

  private static final String TIMER_ARBITRATION_CHANGE_ID =
      "evidence-history-ordered-timer-arbitration";
  private static final int HISTORY_ORDERED_TIMER_ARBITRATION = 1;
  private static final int ACCEPTED_TIME_TIMER_ARBITRATION = 2;

  private final ArrayDeque<WorkflowEvent> inbox = new ArrayDeque<>();
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
  private int timerArbitrationVersion;
  private CancellationScope activeTimerScope;
  private Promise<Void> activeTimer;
  private Promise<Void> activeTimerContinuation;
  private TimerBoundary scheduledBoundary;

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

    timerArbitrationVersion =
        Workflow.getVersion(
            TIMER_ARBITRATION_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            ACCEPTED_TIME_TIMER_ARBITRATION);
    if (timerArbitrationVersion == Workflow.DEFAULT_VERSION) {
      runLegacyTimerKernel();
    } else if (timerArbitrationVersion == HISTORY_ORDERED_TIMER_ARBITRATION) {
      runHistoryOrderedTimerKernel();
    } else {
      runAcceptedTimeTimerKernel();
    }

    Workflow.await(Workflow::isEveryHandlerFinished);
    return state();
  }

  private void runHistoryOrderedTimerKernel() {
    scheduleTimer(TimerBoundary.WARNING, timerPlan.warningAt());
    while (roomPhase != EvidenceRoomPhase.COMPLETED) {
      Workflow.await(() -> !inbox.isEmpty());
      drainHistoryOrderedInbox();
      if (roomPhase != EvidenceRoomPhase.COMPLETED && scheduledBoundary == null) {
        scheduleTimer(TimerBoundary.DEADLINE, timerPlan.deadlineAt());
      }
    }
  }

  private void runAcceptedTimeTimerKernel() {
    scheduleTimer(TimerBoundary.WARNING, timerPlan.warningAt());
    while (roomPhase != EvidenceRoomPhase.COMPLETED) {
      Workflow.await(this::hasProcessableEvent);
      drainAcceptedTimeInbox();
      if (roomPhase != EvidenceRoomPhase.COMPLETED && scheduledBoundary == null) {
        scheduleTimer(TimerBoundary.DEADLINE, timerPlan.deadlineAt());
      }
    }
  }

  private void runLegacyTimerKernel() {
    while (roomPhase != EvidenceRoomPhase.COMPLETED) {
      drainLegacyInbox();
      if (bothPartiesCompleted()) {
        complete("BOTH_PARTIES_COMPLETED");
        break;
      }

      if (!warningSent) {
        if (awaitLegacyInputBefore(timerPlan.warningAt())) {
          continue;
        }
        warningSent = true;
        warningSentAt = timerPlan.warningAt();
        appendOperation(timerPlan.warningOperationKey());
        continue;
      }

      if (awaitLegacyInputBefore(timerPlan.deadlineAt())) {
        continue;
      }
      deadlineExpired = true;
      appendOperation(timerPlan.expiryOperationKey());
      complete("DEADLINE_EXPIRED");
    }
  }

  @Override
  public void partyCompleted(EvidenceRoomSignal signal) {
    inbox.addLast(
        WorkflowEvent.completion(Objects.requireNonNull(signal, "signal must not be null")));
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

  private void drainHistoryOrderedInbox() {
    while (roomPhase != EvidenceRoomPhase.COMPLETED && !inbox.isEmpty()) {
      processWorkflowEvent(inbox.removeFirst());
    }
  }

  private void drainAcceptedTimeInbox() {
    while (roomPhase != EvidenceRoomPhase.COMPLETED && hasProcessableEvent()) {
      WorkflowEvent event = removeNextProcessableEvent();
      processWorkflowEvent(event);
      if (event.timerBoundary() != null && roomPhase != EvidenceRoomPhase.COMPLETED) {
        return;
      }
    }
  }

  private void processWorkflowEvent(WorkflowEvent event) {
    if (event.completion() != null) {
      processSignal(event.completion());
      if (bothPartiesCompleted()) {
        complete("BOTH_PARTIES_COMPLETED");
      }
    } else {
      processTimer(event.timerBoundary());
    }
  }

  private boolean hasProcessableEvent() {
    if (inbox.isEmpty()) {
      return false;
    }
    if (scheduledBoundary == null) {
      return true;
    }
    Instant boundaryAt = boundaryAt(scheduledBoundary);
    return inbox.stream()
        .anyMatch(
            event ->
                event.timerBoundary() != null
                    || (event.completion() != null
                        && acceptedBefore(event.completion(), boundaryAt)));
  }

  private WorkflowEvent removeNextProcessableEvent() {
    if (scheduledBoundary == null) {
      return inbox.removeFirst();
    }
    Instant boundaryAt = boundaryAt(scheduledBoundary);
    WorkflowEvent timer = null;
    for (Iterator<WorkflowEvent> iterator = inbox.iterator(); iterator.hasNext(); ) {
      WorkflowEvent event = iterator.next();
      if (event.completion() != null && acceptedBefore(event.completion(), boundaryAt)) {
        iterator.remove();
        return event;
      }
      if (timer == null && event.timerBoundary() != null) {
        timer = event;
      }
    }
    if (timer != null) {
      inbox.removeFirstOccurrence(timer);
      return timer;
    }
    throw new IllegalStateException("Evidence inbox did not contain a processable event");
  }

  private boolean acceptedBefore(EvidenceRoomSignal signal, Instant boundaryAt) {
    return signal.acceptedAt() == null || signal.acceptedAt().isBefore(boundaryAt);
  }

  private Instant boundaryAt(TimerBoundary boundary) {
    return boundary == TimerBoundary.WARNING ? timerPlan.warningAt() : timerPlan.deadlineAt();
  }

  private void drainLegacyInbox() {
    while (!inbox.isEmpty()) {
      WorkflowEvent event = inbox.removeFirst();
      if (event.completion() == null) {
        throw new IllegalStateException("Legacy Evidence history contained a timer inbox event");
      }
      processSignal(event.completion());
    }
  }

  private void processTimer(TimerBoundary boundary) {
    if (scheduledBoundary != boundary) {
      throw new IllegalStateException("Unexpected Evidence timer boundary: " + boundary);
    }
    clearActiveTimer();
    if (boundary == TimerBoundary.WARNING) {
      warningSent = true;
      warningSentAt = timerPlan.warningAt();
      appendOperation(timerPlan.warningOperationKey());
      return;
    }
    deadlineExpired = true;
    appendOperation(timerPlan.expiryOperationKey());
    complete("DEADLINE_EXPIRED");
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
    if (timerArbitrationVersion == ACCEPTED_TIME_TIMER_ARBITRATION
        && signal.acceptedAt() == null) {
      reject("EVIDENCE_COMPLETION_ACCEPTED_AT_REQUIRED");
      return;
    }

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

  private boolean awaitLegacyInputBefore(Instant boundary) {
    long remainingMillis = boundary.toEpochMilli() - Workflow.currentTimeMillis();
    if (remainingMillis <= 0) {
      return false;
    }
    return Workflow.await(Duration.ofMillis(remainingMillis), () -> !inbox.isEmpty());
  }

  private void scheduleTimer(TimerBoundary boundary, Instant fireAt) {
    if (scheduledBoundary != null) {
      throw new IllegalStateException("Evidence timer already scheduled");
    }
    scheduledBoundary = boundary;
    long delayMillis = Math.max(0, fireAt.toEpochMilli() - Workflow.currentTimeMillis());
    activeTimerScope =
        Workflow.newCancellationScope(
            () -> {
              activeTimer = Workflow.newTimer(Duration.ofMillis(delayMillis));
              if (timerArbitrationVersion == HISTORY_ORDERED_TIMER_ARBITRATION) {
                activeTimerContinuation =
                    activeTimer.handle(
                        (ignored, failure) -> {
                          if (failure == null) {
                            inbox.addLast(WorkflowEvent.timer(boundary));
                          }
                          return null;
                        });
              } else {
                // Collect signal callbacks from the same activation before accepted-time arbitration.
                activeTimerContinuation =
                    Async.procedure(
                        () -> {
                          try {
                            activeTimer.get();
                            inbox.addLast(WorkflowEvent.timer(boundary));
                          } catch (CanceledFailure ignored) {
                            // Completing the room cancels its outstanding timer.
                          }
                        });
              }
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
    activeTimerContinuation = null;
    scheduledBoundary = null;
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
    cancelActiveTimer();
    terminalReason = reason;
    roomPhase = EvidenceRoomPhase.COMPLETED;
    pendingOperationKey = null;
  }

  private enum TimerBoundary {
    WARNING,
    DEADLINE
  }

  private record WorkflowEvent(EvidenceRoomSignal completion, TimerBoundary timerBoundary) {

    private WorkflowEvent {
      if ((completion == null) == (timerBoundary == null)) {
        throw new IllegalArgumentException(
            "Workflow event must contain exactly one completion or timer boundary");
      }
    }

    private static WorkflowEvent completion(EvidenceRoomSignal signal) {
      return new WorkflowEvent(signal, null);
    }

    private static WorkflowEvent timer(TimerBoundary boundary) {
      return new WorkflowEvent(null, boundary);
    }
  }
}

package com.example.dispute.workflow.temporal.room.outcome;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeAttemptReconciliationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeCompensationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeExecutionAttemptObservation;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeProjection;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeSlaEscalationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Deterministic, receipt-driven Outcome kernel with no Activities or external reads. */
public final class OutcomeRoomWorkflowImpl implements OutcomeRoomWorkflow {

  static final int MAX_INBOX_EVENTS = OutcomeWorkflowKernel.MAX_RECEIPTS;

  private final ArrayDeque<WorkflowEvent> inbox = new ArrayDeque<>();
  private OutcomeWorkflowStart start;
  private OutcomeWorkflowKernel kernel;
  private CancellationScope reviewTimerScope;
  private Promise<Void> reviewTimer;
  private Promise<Void> reviewTimerCallback;
  private boolean inboxCapacityExceeded;

  @Override
  public OutcomeProjection run(OutcomeWorkflowStart start) {
    if (this.start != null) {
      throw new IllegalStateException("Outcome workflow was initialized more than once");
    }
    this.start = Objects.requireNonNull(start, "start must not be null");
    boundedOperationCount(start.requiredOperationCount());
    if (start.runtimeMode() == OutcomeWireTypes.RuntimeMode.DISABLED) {
      throw new IllegalArgumentException("DISABLED mode cannot start an Outcome workflow");
    }
    kernel = new OutcomeWorkflowKernel(new OutcomeWorkflowKernel.Start(
        start.workflowId(),
        start.caseId(),
        start.epoch(),
        start.fence(),
        start.revision(),
        start.reviewOpenedAt(),
        start.reviewDeadlineAt(),
        start.runtimeMode(),
        start.syntheticOnly()));

    if (inboxCapacityExceeded) {
      kernel.failCapacity("OUTCOME_SIGNAL_INBOX_LIMIT");
    }
    while (!kernel.snapshot().phase().terminal()) {
      synchronizeReviewTimer();
      Workflow.await(() -> kernel.snapshot().phase().terminal() || !inbox.isEmpty());
      if (kernel.snapshot().phase().terminal()) {
        break;
      }
      WorkflowEvent event = inbox.removeFirst();
      if (event.type() == EventType.REVIEW_DEADLINE) {
        clearReviewTimer();
        kernel.deadlineReached();
      } else {
        adaptAndSubmit(event);
      }
    }
    cancelReviewTimer();
    Workflow.await(Workflow::isEveryHandlerFinished);
    return projection();
  }

  @Override
  public void reviewDecisionCommitted(OutcomeReviewDecisionReceipt receipt) {
    enqueue(EventType.REVIEW_DECISION, Objects.requireNonNull(receipt));
  }

  @Override
  public void slaEscalationCommitted(OutcomeSlaEscalationReceipt receipt) {
    enqueue(EventType.SLA_ESCALATION, Objects.requireNonNull(receipt));
  }

  @Override
  public void operationCommandCommitted(OutcomeOperationCommand command) {
    enqueue(EventType.OPERATION_COMMAND, Objects.requireNonNull(command));
  }

  @Override
  public void operationReceiptCommitted(OutcomeOperationReceipt receipt) {
    enqueue(EventType.OPERATION_RECEIPT, Objects.requireNonNull(receipt));
  }

  @Override
  public void attemptObservationCommitted(OutcomeExecutionAttemptObservation observation) {
    enqueue(EventType.ATTEMPT_OBSERVATION, Objects.requireNonNull(observation));
  }

  @Override
  public void attemptReconciliationCommitted(OutcomeAttemptReconciliationReceipt receipt) {
    enqueue(EventType.RECONCILIATION, Objects.requireNonNull(receipt));
  }

  @Override
  public void compensationReceiptCommitted(OutcomeCompensationReceipt receipt) {
    enqueue(EventType.COMPENSATION, Objects.requireNonNull(receipt));
  }

  @Override
  public void closureReceiptCommitted(OutcomeClosureReceipt receipt) {
    enqueue(EventType.CLOSURE, Objects.requireNonNull(receipt));
  }

  @Override
  public void evaluationReceiptCommitted(OutcomeEvaluationReceipt receipt) {
    enqueue(EventType.EVALUATION, Objects.requireNonNull(receipt));
  }

  @Override
  public OutcomeProjection projection() {
    if (kernel == null || start == null) {
      return null;
    }
    OutcomeWorkflowKernel.Snapshot state = kernel.snapshot();
    long succeeded = state.operations().stream()
        .filter(operation -> operation.terminalStatus()
            == OutcomeWorkflowKernel.TerminalStatus.SUCCEEDED)
        .count();
    long failed = state.operations().stream()
        .filter(operation -> operation.terminalStatus()
            == OutcomeWorkflowKernel.TerminalStatus.FAILED)
        .count();
    long inFlight = state.decision() != null && state.decision().authorizesExecution()
        ? Math.max(0, start.requiredOperationCount() - succeeded - failed)
        : 0;
    long compensationInFlight = state.phase() == OutcomeWorkflowKernel.Phase.COMPENSATING
        ? state.compensationOrder().size() - state.compensationCursor() : 0;
    boolean shadow = start.runtimeMode()
        == OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW;
    return new OutcomeProjection(
        OutcomeProjection.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        projectionPhase(state.phase()),
        state.terminalReviewReceiptId(),
        state.terminalReviewReceiptHash(),
        start.requiredOperationSetRef(),
        start.requiredOperationSetHash(),
        start.requiredOperationCount(),
        succeeded,
        state.ambiguousOperationId() == null ? 0 : 1,
        failed,
        inFlight,
        compensationInFlight,
        state.phase() == OutcomeWorkflowKernel.Phase.MANUAL_RECOVERY_REQUIRED
            || state.phase() == OutcomeWorkflowKernel.Phase.FAILED ? 1 : 0,
        state.closureReceiptId(),
        state.closureReceiptHash(),
        state.evaluationReceiptId(),
        state.evaluationReceiptHash(),
        start.epoch(),
        state.revision(),
        start.fence(),
        writerMode(start.runtimeMode()),
        start.runtimeMode(),
        shadow ? OutcomeWireTypes.SyntheticNoopMarker.JAVA_SIGNED_SYNTHETIC_NOOP_V1 : null,
        shadow);
  }

  @Override
  public OutcomeWorkflowDiagnostics diagnostics() {
    if (kernel == null || start == null) {
      return null;
    }
    OutcomeWorkflowKernel.Snapshot state = kernel.snapshot();
    List<String> operationIds = state.operations().stream()
        .map(OutcomeWorkflowKernel.OperationSnapshot::operationId)
        .toList();
    return new OutcomeWorkflowDiagnostics(
        OutcomeWorkflowDiagnostics.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        state.phase().name(),
        state.reviewDeadlineReached(),
        state.revision(),
        state.lastCommittedEventSequence(),
        state.duplicateSignalCount(),
        state.rejectedSignalCount(),
        state.protocolErrorCode(),
        state.decision() == null ? null : state.decision().name(),
        state.terminalReviewReceiptId(),
        state.ambiguousOperationId(),
        state.ambiguousObservationReceiptId(),
        state.pendingRevisions(),
        state.orderedReceiptIds(),
        operationIds,
        state.compensationOrder(),
        state.compensationCursor(),
        state.closureReceiptId(),
        state.evaluationReceiptId(),
        state.evaluationFailureCount());
  }

  private void adaptAndSubmit(WorkflowEvent event) {
    try {
      switch (event.type()) {
        case REVIEW_DECISION -> submitDecision((OutcomeReviewDecisionReceipt) event.payload());
        case SLA_ESCALATION -> submitSla((OutcomeSlaEscalationReceipt) event.payload());
        case OPERATION_COMMAND -> submitOperationCommand((OutcomeOperationCommand) event.payload());
        case OPERATION_RECEIPT -> submitOperationReceipt((OutcomeOperationReceipt) event.payload());
        case ATTEMPT_OBSERVATION -> submitObservation(
            (OutcomeExecutionAttemptObservation) event.payload());
        case RECONCILIATION -> submitReconciliation(
            (OutcomeAttemptReconciliationReceipt) event.payload());
        case COMPENSATION -> submitCompensation((OutcomeCompensationReceipt) event.payload());
        case CLOSURE -> submitClosure((OutcomeClosureReceipt) event.payload());
        case EVALUATION -> submitEvaluation((OutcomeEvaluationReceipt) event.payload());
        case REVIEW_DEADLINE -> throw new IllegalStateException("deadline must be handled directly");
      }
    } catch (IllegalArgumentException | ClassCastException exception) {
      kernel.rejectMalformed("OUTCOME_SIGNAL_PROTOCOL_INVALID");
    }
  }

  private void submitDecision(OutcomeReviewDecisionReceipt value) {
    requireReviewBinding(
        value.workflowId(), value.caseId(), value.reviewTaskId(), value.frozenReviewPacketRef(),
        value.frozenReviewPacketHash(), value.epoch(), value.fence());
    if (!start.actionSnapshotRef().equals(value.actionSnapshotRef())
        || !start.actionSnapshotHash().equals(value.actionSnapshotHash())
        || !start.requiredOperationSetRef().equals(value.requiredOperationSetRef())
        || !start.requiredOperationSetHash().equals(value.requiredOperationSetHash())
        || start.requiredOperationCount() != value.requiredOperationCount()
        || !start.policyVersion().equals(value.policyVersion())
        || start.syntheticOnly() != value.syntheticOnly()) {
      throw new IllegalArgumentException("decision does not bind the frozen start");
    }
    if (value.decision() == OutcomeWireTypes.ReviewDecision.APPROVE
        && (!start.actionSnapshotRef().equals(value.approvedActionSnapshotRef())
            || !start.actionSnapshotHash().equals(value.approvedActionSnapshotHash()))) {
      throw new IllegalArgumentException("approve must preserve the frozen action snapshot");
    }
    OutcomeWorkflowKernel.Decision decision = OutcomeWorkflowKernel.Decision.valueOf(
        value.decision().name());
    kernel.submit(new OutcomeWorkflowKernel.DecisionReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.receiptId(),
        value.receiptHash(),
        value.sourceRevision(),
        value.revision(),
        value.committedEventSequence(),
        decision,
        decision.authorizesExecution() ? value.operationKeyHash() : null,
        value.requestHash(),
        decision.authorizesExecution() ? value.approvedActionSnapshotRef() : null,
        decision.authorizesExecution() ? value.approvedActionSnapshotHash() : null,
        decision.authorizesExecution() ? value.requiredOperationSetRef() : null,
        decision.authorizesExecution() ? value.requiredOperationSetHash() : null,
        decision.authorizesExecution() ? boundedOperationCount(value.requiredOperationCount()) : 0,
        value.committedAt(),
        value.syntheticOnly()));
  }

  private void submitSla(OutcomeSlaEscalationReceipt value) {
    requireReviewBinding(
        value.workflowId(), value.caseId(), value.reviewTaskId(), value.frozenReviewPacketRef(),
        value.frozenReviewPacketHash(), value.epoch(), value.fence());
    if (!start.reviewDeadlineAt().equals(value.deadlineAt())
        || value.factType() != OutcomeWireTypes.SlaFactType.SYSTEM_SLA_ESCALATION
        || value.actor() != OutcomeWireTypes.ActorType.SYSTEM
        || start.syntheticOnly() != value.syntheticOnly()) {
      throw new IllegalArgumentException("SLA receipt does not bind the immutable deadline");
    }
    kernel.submit(new OutcomeWorkflowKernel.SlaReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.receiptId(), value.receiptHash(), value.sourceRevision(), value.revision(),
        value.committedEventSequence(), value.deadlineAt(), value.committedAt(),
        value.syntheticOnly()));
  }

  private void submitOperationCommand(OutcomeOperationCommand value) {
    requireAuthority(value.workflowId(), value.caseId(), value.epoch(), value.fence());
    OutcomeWorkflowKernel.Snapshot state = kernel.snapshot();
    if (!Objects.equals(state.decisionReceiptId(), value.approvalReceiptRef())
        || !Objects.equals(state.decisionReceiptHash(), value.approvalReceiptHash())
        || !Objects.equals(state.approvedActionSnapshotRef(), value.approvedActionSnapshotRef())
        || !Objects.equals(state.approvedActionSnapshotHash(), value.approvedActionSnapshotHash())
        || start.runtimeMode() != value.runtimeMode()
        || start.syntheticOnly() != value.syntheticOnly()) {
      throw new IllegalArgumentException("operation command does not bind its approval");
    }
    kernel.submit(new OutcomeWorkflowKernel.OperationCommandReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.commandId(), value.requestHash(), value.sourceRevision(), value.revision(),
        value.committedEventSequence(), value.operationId(), value.operationKeyHash(),
        value.requestHash(), value.externalIdempotencyKeyHash(),
        boundedOperationSequence(value.operationSequence()), value.requiredForClosure(),
        value.compensable(),
        value.attemptNo(), value.runtimeMode(), value.syntheticOnly(),
        value.syntheticNoopMarker() != null));
  }

  private void submitOperationReceipt(OutcomeOperationReceipt value) {
    requireAuthority(value.workflowId(), value.caseId(), value.epoch(), value.fence());
    kernel.submit(new OutcomeWorkflowKernel.OperationReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.receiptId(), value.receiptHash(), value.sourceRevision(), value.revision(),
        value.committedEventSequence(), value.operationId(), value.operationKeyHash(),
        value.requestHash(), value.externalIdempotencyKeyHash(),
        boundedOperationSequence(value.operationSequence()), value.requiredForClosure(),
        value.compensable(),
        OutcomeWorkflowKernel.TerminalStatus.valueOf(value.terminalStatus().name()),
        value.resultRef(), value.resultHash(), value.runtimeMode(), value.syntheticNoop()));
  }

  private void submitObservation(OutcomeExecutionAttemptObservation value) {
    requireAuthority(value.workflowId(), value.caseId(), value.epoch(), value.fence());
    kernel.submit(new OutcomeWorkflowKernel.AttemptObservationReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.observationId(), value.observationHash(), value.sourceRevision(), value.revision(),
        value.committedEventSequence(), value.operationId(), value.operationKeyHash(),
        value.requestHash(), value.externalIdempotencyKeyHash(),
        boundedOperationSequence(value.operationSequence()), value.requiredForClosure(),
        value.compensable(),
        value.observationId(), value.observationHash()));
  }

  private void submitReconciliation(OutcomeAttemptReconciliationReceipt value) {
    requireAuthority(value.workflowId(), value.caseId(), value.epoch(), value.fence());
    kernel.submit(new OutcomeWorkflowKernel.ReconciliationReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.receiptId(), value.receiptHash(), value.sourceRevision(), value.revision(),
        value.committedEventSequence(), value.operationId(), value.operationKeyHash(),
        value.requestHash(), value.externalIdempotencyKeyHash(),
        boundedOperationSequence(value.operationSequence()), value.requiredForClosure(),
        value.compensable(),
        value.observationId(),
        OutcomeWorkflowKernel.ReconciliationResolution.valueOf(value.resolution().name()),
        value.authoritativeReceiptRef(), value.authoritativeReceiptHash()));
  }

  private void submitCompensation(OutcomeCompensationReceipt value) {
    requireAuthority(value.workflowId(), value.caseId(), value.epoch(), value.fence());
    kernel.submit(new OutcomeWorkflowKernel.CompensationReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.receiptId(), value.receiptHash(), value.sourceRevision(), value.revision(),
        value.committedEventSequence(), value.compensationOperationId(),
        value.compensationRequestHash(), value.originalOperationId(),
        value.originalSuccessReceiptId(), value.originalSuccessReceiptHash(), value.reverseOrder(),
        OutcomeWorkflowKernel.TerminalStatus.valueOf(value.status().name()),
        value.compensationReceiptId(), value.compensationReceiptHash()));
  }

  private void submitClosure(OutcomeClosureReceipt value) {
    requireAuthority(value.workflowId(), value.caseId(), value.epoch(), value.fence());
    OutcomeWorkflowKernel.Snapshot state = kernel.snapshot();
    if (!Objects.equals(state.decisionReceiptId(), value.approvalReceiptRef())
        || !Objects.equals(state.decisionReceiptHash(), value.approvalReceiptHash())
        || !Objects.equals(state.approvedActionSnapshotRef(), value.approvedActionSnapshotRef())
        || !Objects.equals(state.approvedActionSnapshotHash(), value.approvedActionSnapshotHash())
        || !start.requiredOperationSetRef().equals(value.requiredOperationSetRef())
        || !start.requiredOperationSetHash().equals(value.requiredOperationSetHash())
        || start.requiredOperationCount() != value.requiredOperationCount()) {
      throw new IllegalArgumentException("closure does not bind its immutable parents");
    }
    kernel.submit(new OutcomeWorkflowKernel.ClosureReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.receiptId(), value.receiptHash(), value.sourceRevision(), value.revision(),
        value.committedEventSequence(), value.closedSnapshotRef(), value.closedSnapshotHash()));
  }

  private void submitEvaluation(OutcomeEvaluationReceipt value) {
    requireAuthority(value.workflowId(), value.caseId(), value.epoch(), value.fence());
    OutcomeWorkflowKernel.EvaluationStatus status =
        value.status() == OutcomeWireTypes.EvaluationStatus.SUCCEEDED
            ? OutcomeWorkflowKernel.EvaluationStatus.SUCCEEDED
            : OutcomeWorkflowKernel.EvaluationStatus.FAILED;
    kernel.submit(new OutcomeWorkflowKernel.EvaluationReceipt(
        authority(value.workflowId(), value.caseId(), value.epoch(), value.fence()),
        value.receiptId(), value.receiptHash(), value.sourceRevision(), value.revision(),
        value.committedEventSequence(), value.closedSnapshotRef(), value.closedSnapshotHash(),
        status, value.evaluationLedgerRef(), value.evaluationLedgerHash()));
  }

  private void requireReviewBinding(
      String workflowId,
      String caseId,
      String reviewTaskId,
      String packetRef,
      String packetHash,
      long epoch,
      long fence) {
    requireAuthority(workflowId, caseId, epoch, fence);
    if (!start.reviewTaskId().equals(reviewTaskId)
        || !start.frozenReviewPacketRef().equals(packetRef)
        || !start.frozenReviewPacketHash().equals(packetHash)) {
      throw new IllegalArgumentException("review receipt does not bind the frozen packet");
    }
  }

  private void requireAuthority(
      String workflowId, String caseId, long epoch, long fence) {
    if (!start.workflowId().equals(workflowId)
        || !start.caseId().equals(caseId)
        || start.epoch() != epoch
        || start.fence() != fence) {
      throw new IllegalArgumentException("receipt authority does not match the Outcome epoch");
    }
  }

  private OutcomeWorkflowKernel.Authority authority(
      String workflowId, String caseId, long epoch, long fence) {
    return new OutcomeWorkflowKernel.Authority(workflowId, caseId, epoch, fence);
  }

  private void enqueue(EventType type, Object payload) {
    if (type == EventType.REVIEW_DEADLINE) {
      inbox.addLast(new WorkflowEvent(type, payload));
      return;
    }
    if (inbox.size() >= MAX_INBOX_EVENTS) {
      if (kernel != null) {
        kernel.failCapacity("OUTCOME_SIGNAL_INBOX_LIMIT");
      } else {
        inboxCapacityExceeded = true;
      }
      return;
    }
    inbox.addLast(new WorkflowEvent(type, payload));
  }

  private void synchronizeReviewTimer() {
    OutcomeWorkflowKernel.Phase phase = kernel.snapshot().phase();
    if (phase != OutcomeWorkflowKernel.Phase.WAITING_REVIEW) {
      cancelReviewTimer();
      return;
    }
    if (reviewTimerScope != null) {
      return;
    }
    long delayMillis = Math.max(0,
        start.reviewDeadlineAt().toEpochMilli() - Workflow.currentTimeMillis());
    reviewTimerScope = Workflow.newCancellationScope(() -> {
      reviewTimer = Workflow.newTimer(Duration.ofMillis(delayMillis));
      reviewTimerCallback = reviewTimer.handle((ignored, failure) -> {
        if (failure == null) {
          enqueue(EventType.REVIEW_DEADLINE, null);
        }
        return null;
      });
    });
    reviewTimerScope.run();
  }

  private void cancelReviewTimer() {
    if (reviewTimerScope != null && reviewTimer != null && !reviewTimer.isCompleted()) {
      reviewTimerScope.cancel();
    }
    clearReviewTimer();
  }

  private void clearReviewTimer() {
    reviewTimerScope = null;
    reviewTimer = null;
    reviewTimerCallback = null;
  }

  private static OutcomeWireTypes.ProjectionPhase projectionPhase(
      OutcomeWorkflowKernel.Phase phase) {
    return switch (phase) {
      case WAITING_REVIEW, WAITING_SLA_ESCALATION ->
          OutcomeWireTypes.ProjectionPhase.WAITING_REVIEW;
      case EXECUTION_INTENT, REJECTED, MORE_EVIDENCE_REQUESTED, MANUAL_ESCALATED ->
          OutcomeWireTypes.ProjectionPhase.DECISION_COMMITTED;
      case SLA_ESCALATED -> OutcomeWireTypes.ProjectionPhase.SLA_ESCALATED;
      case EXECUTING -> OutcomeWireTypes.ProjectionPhase.EXECUTING;
      case RECONCILING -> OutcomeWireTypes.ProjectionPhase.RECONCILING;
      case COMPENSATING -> OutcomeWireTypes.ProjectionPhase.COMPENSATING;
      case CLOSURE_PENDING -> OutcomeWireTypes.ProjectionPhase.CLOSURE_PENDING;
      case CLOSED -> OutcomeWireTypes.ProjectionPhase.CLOSED;
      case EVALUATED -> OutcomeWireTypes.ProjectionPhase.EVALUATED;
      case MANUAL_RECOVERY_REQUIRED, FAILED -> OutcomeWireTypes.ProjectionPhase.MANUAL_RECOVERY;
    };
  }

  private static OutcomeWireTypes.WriterMode writerMode(OutcomeWireTypes.RuntimeMode runtimeMode) {
    return switch (runtimeMode) {
      case DISABLED -> OutcomeWireTypes.WriterMode.LEGACY;
      case JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW -> OutcomeWireTypes.WriterMode.SHADOW;
      case TEMPORAL -> OutcomeWireTypes.WriterMode.TEMPORAL;
    };
  }

  static int boundedOperationCount(long value) {
    if (value < 0 || value > OutcomeWorkflowKernel.MAX_OPERATIONS) {
      throw new IllegalArgumentException("requiredOperationCount must be between 0 and 64");
    }
    return (int) value;
  }

  static int boundedOperationSequence(long value) {
    if (value < 1 || value > OutcomeWorkflowKernel.MAX_OPERATIONS) {
      throw new IllegalArgumentException("operationSequence must be between 1 and 64");
    }
    return (int) value;
  }

  private enum EventType {
    REVIEW_DECISION,
    SLA_ESCALATION,
    OPERATION_COMMAND,
    OPERATION_RECEIPT,
    ATTEMPT_OBSERVATION,
    RECONCILIATION,
    COMPENSATION,
    CLOSURE,
    EVALUATION,
    REVIEW_DEADLINE
  }

  private record WorkflowEvent(EventType type, Object payload) {
    private WorkflowEvent {
      Objects.requireNonNull(type, "type must not be null");
      if ((type == EventType.REVIEW_DEADLINE) != (payload == null)) {
        throw new IllegalArgumentException("only the deadline event has no payload");
      }
    }
  }
}

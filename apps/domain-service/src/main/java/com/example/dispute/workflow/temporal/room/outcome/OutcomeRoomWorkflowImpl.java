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
import com.example.dispute.workflow.runtime.rooms.outcome.TargetOutcomeCompletionActivities;
import com.example.dispute.workflow.runtime.rooms.outcome.TargetOutcomeCompletionActivities.CompletionRequest;
import com.example.dispute.workflow.runtime.rooms.outcome.TargetOutcomeCompletionActivities.CompletionResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic, receipt-driven Outcome kernel; formal target completion is activity-relayed. */
public final class OutcomeRoomWorkflowImpl implements OutcomeRoomWorkflow {

  static final int MAX_INBOX_EVENTS = OutcomeWorkflowKernel.MAX_UNIQUE_RECEIPTS;
  // Protobuf Duration's inclusive maximum whole-second value, expressed in milliseconds.
  static final long MAX_TEMPORAL_TIMER_DELAY_MILLIS = 315_576_000_000_000L;

  private final ArrayDeque<QueuedWorkflowEvent> inbox = new ArrayDeque<>();
  private final Map<WorkflowEvent, QueuedWorkflowEvent> coalescedSignals = new LinkedHashMap<>();
  private OutcomeWorkflowStart start;
  private OutcomeWorkflowKernel kernel;
  private CancellationScope reviewTimerScope;
  private Promise<Void> reviewTimer;
  private Promise<Void> reviewTimerCallback;
  private boolean inboxCapacityExceeded;
  private OutcomeReviewDecisionReceipt acceptedDecision;
  private TargetOutcomeCompletionActivities targetCompletionActivities;
  private OutcomeCompletionRequest completionRequest;
  private OutcomeCompletionResult completionResult;

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
    if (start.runtimeMode() == OutcomeWireTypes.RuntimeMode.TEMPORAL) {
      targetCompletionActivities = Workflow.newActivityStub(
          TargetOutcomeCompletionActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build());
    }

    if (inboxCapacityExceeded) {
      kernel.failCapacity("OUTCOME_SIGNAL_INBOX_LIMIT");
    }
    while (!kernel.snapshot().phase().terminal()) {
      synchronizeReviewTimer();
      Workflow.await(() -> kernel.snapshot().phase().terminal() || !inbox.isEmpty());
      if (kernel.snapshot().phase().terminal()) {
        break;
      }
      QueuedWorkflowEvent queued = inbox.removeFirst();
      WorkflowEvent event = queued.event();
      if (event.type() == EventType.REVIEW_DEADLINE) {
        clearReviewTimer();
        kernel.deadlineReached();
      } else {
        coalescedSignals.remove(event);
        adaptAndSubmit(event);
        kernel.recordCoalescedDuplicates(queued.coalescedDuplicateCount());
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
  public OutcomeReviewDecisionAcceptance reviewDecisionAccepted(OutcomeReviewDecisionReceipt receipt) {
    OutcomeReviewDecisionReceipt value = Objects.requireNonNull(receipt, "receipt");
    if (kernel == null || start == null) {
      return OutcomeReviewDecisionAcceptance.rejected(
          value.receiptId(), value.receiptHash(), value.sourceRevision());
    }
    try {
      submitDecision(value);
    } catch (IllegalArgumentException | ClassCastException exception) {
      return OutcomeReviewDecisionAcceptance.rejected(
          value.receiptId(), value.receiptHash(), value.sourceRevision());
    }
    OutcomeWorkflowKernel.Snapshot state = kernel.snapshot();
    boolean accepted =
        value.receiptId().equals(state.decisionReceiptId())
            && value.receiptHash().equals(state.decisionReceiptHash())
            && state.revision() >= value.revision();
    return new OutcomeReviewDecisionAcceptance(
        value.receiptId(),
        value.receiptHash(),
        value.sourceRevision(),
        accepted ? value.revision() : value.sourceRevision(),
        accepted);
  }

  @Override
  public OutcomeCompletionResult completeTargetOutcomeAfterRouting(OutcomeCompletionRequest request) {
    OutcomeCompletionRequest value = Objects.requireNonNull(request, "request");
    if (completionResult != null) {
      if (!completionRequest.equals(value)) {
        throw new IllegalArgumentException("target Outcome completion request conflicts with durable terminal receipt");
      }
      return completionResult;
    }
    if (kernel == null
        || start == null
        || targetCompletionActivities == null
        || acceptedDecision == null
        || !acceptedDecision.executionAuthorized()) {
      throw new IllegalStateException("target Outcome completion requires an accepted executable Review receipt");
    }
    requireCompletionAuthority(value);
    OutcomeWorkflowKernel.Snapshot before = kernel.snapshot();
    if (before.phase() != OutcomeWorkflowKernel.Phase.EXECUTION_INTENT
        && before.phase() != OutcomeWorkflowKernel.Phase.EXECUTING
        && before.phase() != OutcomeWorkflowKernel.Phase.CLOSURE_PENDING
        && before.phase() != OutcomeWorkflowKernel.Phase.CLOSED) {
      throw new IllegalStateException("target Outcome completion is not in an executable phase");
    }
    CompletionResult durable = targetCompletionActivities.complete(
        new CompletionRequest(
            start,
            acceptedDecision,
            before.revision(),
            before.lastCommittedEventSequence(),
            value));
    applyTargetCompletionFacts(durable);
    OutcomeWorkflowKernel.Snapshot terminal = kernel.snapshot();
    if (terminal.phase() != OutcomeWorkflowKernel.Phase.EVALUATED) {
      throw new IllegalStateException("target Outcome completion did not reach EVALUATED");
    }
    requireTerminalProgress(value, durable.terminalProgressReceipt());
    completionRequest = value;
    completionResult = new OutcomeCompletionResult(projection(), durable.terminalProgressReceipt());
    return completionResult;
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
    OutcomeWorkflowKernel.Snapshot state = kernel.snapshot();
    if (value.receiptId().equals(state.decisionReceiptId())
        && value.receiptHash().equals(state.decisionReceiptHash())) {
      acceptedDecision = value;
    }
  }

  /** Applies the activity's already durable facts in the only legal causal order. */
  private void applyTargetCompletionFacts(CompletionResult result) {
    result = Objects.requireNonNull(result, "target Outcome completion result");
    for (OutcomeOperationCommand command : result.operationCommands()) {
      submitOperationCommand(command);
    }
    for (OutcomeOperationReceipt receipt : result.operationReceipts()) {
      submitOperationReceipt(receipt);
    }
    if (result.closureReceipt() == null || result.evaluationReceipt() == null) {
      throw new IllegalStateException("target Outcome completion is missing terminal facts");
    }
    submitClosure(result.closureReceipt());
    submitEvaluation(result.evaluationReceipt());
  }

  private void requireCompletionAuthority(OutcomeCompletionRequest request) {
    if (request.roomEpoch() != start.epoch() || request.fencingToken() != start.fence()) {
      throw new IllegalArgumentException("target Outcome completion crossed fenced Review authority");
    }
    if (!request.reviewReceiptId().equals(acceptedDecision.receiptId())
        || !request.reviewReceiptHash().equals(acceptedDecision.receiptHash())
        || request.reviewReceiptRevision() != acceptedDecision.revision()) {
      throw new IllegalArgumentException("target Outcome completion is not bound to the accepted Review receipt");
    }
  }

  private static void requireTerminalProgress(
      OutcomeCompletionRequest request,
      com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt receipt) {
    if (receipt == null
        || receipt.roomType()
            != com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.REVIEW
        || receipt.roomEpoch() != request.roomEpoch()
        || receipt.fencingToken() != request.fencingToken()
        || receipt.processRevision() <= request.expectedProcessRevision()
        || receipt.roomRevision() <= request.expectedRoomRevision()) {
      throw new IllegalStateException("target Outcome terminal progress receipt is not a forward exact coordinate");
    }
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
    WorkflowEvent event = new WorkflowEvent(type, payload);
    if (type == EventType.REVIEW_DEADLINE) {
      inbox.addLast(new QueuedWorkflowEvent(event));
      return;
    }
    QueuedWorkflowEvent existing = coalescedSignals.get(event);
    if (existing != null) {
      existing.recordDuplicate();
      return;
    }
    if (coalescedSignals.size() >= MAX_INBOX_EVENTS) {
      if (kernel != null) {
        kernel.failCapacity("OUTCOME_SIGNAL_INBOX_LIMIT");
      } else {
        inboxCapacityExceeded = true;
      }
      return;
    }
    QueuedWorkflowEvent queued = new QueuedWorkflowEvent(event);
    coalescedSignals.put(event, queued);
    inbox.addLast(queued);
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
    long delayMillis = boundedReviewDelayMillis(
        start.reviewDeadlineAt(), Workflow.currentTimeMillis());
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

  static long boundedReviewDelayMillis(Instant deadline, long currentTimeMillis) {
    Objects.requireNonNull(deadline, "deadline must not be null");
    long deadlineMillis;
    try {
      deadlineMillis = deadline.toEpochMilli();
    } catch (ArithmeticException overflow) {
      deadlineMillis = deadline.isAfter(Instant.EPOCH) ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
    if (deadlineMillis <= currentTimeMillis) {
      return 0;
    }
    long delayMillis;
    try {
      delayMillis = Math.subtractExact(deadlineMillis, currentTimeMillis);
    } catch (ArithmeticException overflow) {
      delayMillis = Long.MAX_VALUE;
    }
    return Math.min(delayMillis, MAX_TEMPORAL_TIMER_DELAY_MILLIS);
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

  private static final class QueuedWorkflowEvent {
    private final WorkflowEvent event;
    private long coalescedDuplicateCount;

    private QueuedWorkflowEvent(WorkflowEvent event) {
      this.event = Objects.requireNonNull(event, "event must not be null");
    }

    private WorkflowEvent event() {
      return event;
    }

    private long coalescedDuplicateCount() {
      return coalescedDuplicateCount;
    }

    private void recordDuplicate() {
      if (coalescedDuplicateCount < Long.MAX_VALUE) {
        coalescedDuplicateCount++;
      }
    }
  }
}

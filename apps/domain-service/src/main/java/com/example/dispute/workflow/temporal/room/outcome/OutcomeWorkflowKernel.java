package com.example.dispute.workflow.temporal.room.outcome;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure deterministic state machine behind the unregistered Outcome Workflow. */
final class OutcomeWorkflowKernel {

  static final int MAX_OPERATIONS = 64;
  static final int MAX_PENDING_RECEIPTS = 128;
  // Initial command, two observations, two reconciliations, and one authorized retry command.
  static final int RECEIPTS_PER_ONE_RETRY_OPERATION = 6;
  static final int MAX_ONE_RETRY_SUCCESS_RECEIPTS =
      1 + MAX_OPERATIONS * RECEIPTS_PER_ONE_RETRY_OPERATION + 2;
  static final int MAX_ONE_RETRY_MANUAL_RECEIPTS =
      1 + MAX_OPERATIONS * RECEIPTS_PER_ONE_RETRY_OPERATION + MAX_OPERATIONS - 1;
  // Retain a full future buffer plus deterministic headroom beyond the 448-receipt manual path.
  static final int CAUSAL_RECOVERY_RESERVE = 192;
  static final int MAX_UNIQUE_RECEIPTS =
      MAX_ONE_RETRY_MANUAL_RECEIPTS + MAX_PENDING_RECEIPTS + CAUSAL_RECOVERY_RESERVE;
  static final int MAX_CAUSAL_RECEIPTS = 1024;

  enum Phase {
    WAITING_REVIEW,
    WAITING_SLA_ESCALATION,
    EXECUTION_INTENT,
    EXECUTING,
    RECONCILING,
    COMPENSATING,
    CLOSURE_PENDING,
    MANUAL_RECOVERY_REQUIRED,
    CLOSED,
    EVALUATED,
    REJECTED,
    MORE_EVIDENCE_REQUESTED,
    MANUAL_ESCALATED,
    SLA_ESCALATED,
    FAILED;

    boolean terminal() {
      return switch (this) {
        case EVALUATED,
            REJECTED,
            MORE_EVIDENCE_REQUESTED,
            MANUAL_ESCALATED,
            SLA_ESCALATED,
            MANUAL_RECOVERY_REQUIRED,
            FAILED -> true;
        default -> false;
      };
    }
  }

  enum Decision {
    APPROVE,
    MODIFY_AND_APPROVE,
    REQUEST_MORE_EVIDENCE,
    REJECT,
    ESCALATE_MANUAL;

    boolean authorizesExecution() {
      return this == APPROVE || this == MODIFY_AND_APPROVE;
    }
  }

  enum TerminalStatus {
    SUCCEEDED,
    FAILED
  }

  enum ReconciliationResolution {
    CONFIRMED_SUCCESS,
    CONFIRMED_FAILURE,
    NOT_FOUND_SAFE_TO_RETRY,
    UNRESOLVED
  }

  enum EvaluationStatus {
    SUCCEEDED,
    FAILED
  }

  record Start(
      String workflowId,
      String caseId,
      long outcomeEpoch,
      long fence,
      long initialRevision,
      Instant openedAt,
      Instant reviewDeadlineAt,
      OutcomeWireTypes.RuntimeMode runtimeMode,
      boolean syntheticOnly) {

    Start {
      requireComponent(workflowId, "workflowId");
      requireComponent(caseId, "caseId");
      requireNonNegative(outcomeEpoch, "outcomeEpoch");
      requirePositive(fence, "fence");
      requireNonNegative(initialRevision, "initialRevision");
      Objects.requireNonNull(openedAt, "openedAt must not be null");
      Objects.requireNonNull(reviewDeadlineAt, "reviewDeadlineAt must not be null");
      if (!reviewDeadlineAt.isAfter(openedAt)) {
        throw new IllegalArgumentException("reviewDeadlineAt must be after openedAt");
      }
      Objects.requireNonNull(runtimeMode, "runtimeMode must not be null");
      if (runtimeMode == OutcomeWireTypes.RuntimeMode.DISABLED) {
        throw new IllegalArgumentException("DISABLED mode cannot run an Outcome kernel");
      }
      boolean shadow = runtimeMode
          == OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW;
      if (shadow != syntheticOnly) {
        throw new IllegalArgumentException("runtimeMode and syntheticOnly must be consistent");
      }
    }
  }

  sealed interface Receipt permits DecisionReceipt, SlaReceipt, OperationCommandReceipt,
      OperationReceipt, AttemptObservationReceipt, ReconciliationReceipt, CompensationReceipt,
      ClosureReceipt, EvaluationReceipt {
    Authority authority();

    String receiptId();

    String receiptHash();

    long sourceRevision();

    long revision();

    long committedEventSequence();
  }

  record Authority(
      String workflowId,
      String caseId,
      long outcomeEpoch,
      long fence) {

    Authority {
      requireComponent(workflowId, "workflowId");
      requireComponent(caseId, "caseId");
      requireNonNegative(outcomeEpoch, "outcomeEpoch");
      requirePositive(fence, "fence");
    }

    boolean matches(Start start) {
      return workflowId.equals(start.workflowId())
          && caseId.equals(start.caseId())
          && outcomeEpoch == start.outcomeEpoch()
          && fence == start.fence();
    }
  }

  record DecisionReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      Decision decision,
      String operationKey,
      String requestHash,
      String approvedActionSnapshotRef,
      String approvedActionSnapshotHash,
      String requiredOperationSetRef,
      String requiredOperationSetHash,
      int requiredOperationCount,
      Instant committedAt,
      boolean syntheticOnly) implements Receipt {

    DecisionReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      Objects.requireNonNull(decision, "decision must not be null");
      Objects.requireNonNull(committedAt, "committedAt must not be null");
      requireHash(requestHash, "requestHash");
      if (decision.authorizesExecution()) {
        requireOperationKey(operationKey, "operationKey");
        requireRef(approvedActionSnapshotRef, "approvedActionSnapshotRef");
        requireHash(approvedActionSnapshotHash, "approvedActionSnapshotHash");
        requireRef(requiredOperationSetRef, "requiredOperationSetRef");
        requireHash(requiredOperationSetHash, "requiredOperationSetHash");
        if (requiredOperationCount < 0 || requiredOperationCount > MAX_OPERATIONS) {
          throw new IllegalArgumentException("requiredOperationCount must be between 0 and 64");
        }
      } else if (approvedActionSnapshotRef != null
          || approvedActionSnapshotHash != null
          || requiredOperationSetRef != null
          || requiredOperationSetHash != null
          || requiredOperationCount != 0
          || operationKey != null) {
        throw new IllegalArgumentException("non-execution decisions cannot carry operation intent");
      }
    }
  }

  record SlaReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      Instant deadlineAt,
      Instant committedAt,
      boolean syntheticOnly) implements Receipt {

    SlaReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
      Objects.requireNonNull(committedAt, "committedAt must not be null");
    }
  }

  record OperationCommandReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      String operationId,
      String operationKey,
      String requestHash,
      String externalIdempotencyKeyHash,
      int operationSequence,
      boolean requiredForClosure,
      boolean compensable,
      long attemptNo,
      OutcomeWireTypes.RuntimeMode runtimeMode,
      boolean syntheticOnly,
      boolean syntheticNoop) implements Receipt {

    OperationCommandReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      requireComponent(operationId, "operationId");
      requireOperationKey(operationKey, "operationKey");
      requireHash(requestHash, "requestHash");
      requireHash(externalIdempotencyKeyHash, "externalIdempotencyKeyHash");
      if (operationSequence < 1 || operationSequence > MAX_OPERATIONS) {
        throw new IllegalArgumentException("operationSequence must be between 1 and 64");
      }
      requirePositive(attemptNo, "attemptNo");
      Objects.requireNonNull(runtimeMode, "runtimeMode must not be null");
    }
  }

  record OperationReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      String operationId,
      String operationKey,
      String requestHash,
      String externalIdempotencyKeyHash,
      int operationSequence,
      boolean requiredForClosure,
      boolean compensable,
      TerminalStatus terminalStatus,
      String resultRef,
      String resultHash,
      OutcomeWireTypes.RuntimeMode runtimeMode,
      boolean syntheticNoop) implements Receipt {

    OperationReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      validateOperationResult(operationId, operationKey, requestHash, externalIdempotencyKeyHash,
          operationSequence, terminalStatus, resultRef, resultHash);
      Objects.requireNonNull(runtimeMode, "runtimeMode must not be null");
    }
  }

  record AttemptObservationReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      String operationId,
      String operationKey,
      String requestHash,
      String externalIdempotencyKeyHash,
      int operationSequence,
      boolean requiredForClosure,
      boolean compensable,
      String observationRef,
      String observationHash) implements Receipt {

    AttemptObservationReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      requireComponent(operationId, "operationId");
      requireOperationKey(operationKey, "operationKey");
      requireHash(requestHash, "requestHash");
      requireHash(externalIdempotencyKeyHash, "externalIdempotencyKeyHash");
      if (operationSequence < 1 || operationSequence > MAX_OPERATIONS) {
        throw new IllegalArgumentException("operationSequence must be between 1 and 64");
      }
      requireRef(observationRef, "observationRef");
      requireHash(observationHash, "observationHash");
    }
  }

  record ReconciliationReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      String operationId,
      String operationKey,
      String requestHash,
      String externalIdempotencyKeyHash,
      int operationSequence,
      boolean requiredForClosure,
      boolean compensable,
      String observationReceiptId,
      ReconciliationResolution resolution,
      String resultRef,
      String resultHash) implements Receipt {

    ReconciliationReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      requireComponent(operationId, "operationId");
      requireOperationKey(operationKey, "operationKey");
      requireHash(requestHash, "requestHash");
      requireHash(externalIdempotencyKeyHash, "externalIdempotencyKeyHash");
      if (operationSequence < 1 || operationSequence > MAX_OPERATIONS) {
        throw new IllegalArgumentException("operationSequence must be between 1 and 64");
      }
      requireComponent(observationReceiptId, "observationReceiptId");
      Objects.requireNonNull(resolution, "resolution must not be null");
      if (resolution == ReconciliationResolution.CONFIRMED_SUCCESS
          || resolution == ReconciliationResolution.CONFIRMED_FAILURE) {
        requireRef(resultRef, "resultRef");
        requireHash(resultHash, "resultHash");
      } else if (resultRef != null || resultHash != null) {
        throw new IllegalArgumentException("non-confirming reconciliation cannot carry a result");
      }
    }
  }

  record CompensationReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      String compensationOperationId,
      String compensationRequestHash,
      String parentOperationId,
      String parentSuccessReceiptId,
      String parentSuccessReceiptHash,
      long reverseOrder,
      TerminalStatus terminalStatus,
      String compensationReceiptId,
      String compensationReceiptHash) implements Receipt {

    CompensationReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      requireComponent(compensationOperationId, "compensationOperationId");
      requireHash(compensationRequestHash, "compensationRequestHash");
      requireComponent(parentOperationId, "parentOperationId");
      requireComponent(parentSuccessReceiptId, "parentSuccessReceiptId");
      requireHash(parentSuccessReceiptHash, "parentSuccessReceiptHash");
      requireNonNegative(reverseOrder, "reverseOrder");
      Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
      requireComponent(compensationReceiptId, "compensationReceiptId");
      requireHash(compensationReceiptHash, "compensationReceiptHash");
      if (!receiptId.equals(compensationReceiptId)
          || !receiptHash.equals(compensationReceiptHash)) {
        throw new IllegalArgumentException("compensation receipt identity must be self-consistent");
      }
    }
  }

  record ClosureReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      String closedSnapshotRef,
      String closedSnapshotHash) implements Receipt {

    ClosureReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      requireRef(closedSnapshotRef, "closedSnapshotRef");
      requireHash(closedSnapshotHash, "closedSnapshotHash");
    }
  }

  record EvaluationReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence,
      String closedSnapshotRef,
      String closedSnapshotHash,
      EvaluationStatus evaluationStatus,
      String evaluationRef,
      String evaluationHash) implements Receipt {

    EvaluationReceipt {
      validateReceipt(authority, receiptId, receiptHash, sourceRevision, revision,
          committedEventSequence);
      requireRef(closedSnapshotRef, "closedSnapshotRef");
      requireHash(closedSnapshotHash, "closedSnapshotHash");
      Objects.requireNonNull(evaluationStatus, "evaluationStatus must not be null");
      requireRef(evaluationRef, "evaluationRef");
      requireHash(evaluationHash, "evaluationHash");
    }
  }

  private final Start start;
  private final Map<String, Receipt> observedReceipts = new LinkedHashMap<>();
  private final Map<Long, String> observedEventSequences = new LinkedHashMap<>();
  private final TreeMap<Long, Receipt> pendingReceipts = new TreeMap<>();
  private final Map<String, Operation> operations = new LinkedHashMap<>();
  private final Map<String, String> operationKeys = new LinkedHashMap<>();
  private final List<String> compensationOrder = new ArrayList<>();
  private final List<String> orderedReceiptIds = new ArrayList<>();

  private Phase phase = Phase.WAITING_REVIEW;
  private long revision;
  private long lastCommittedEventSequence;
  private long duplicateSignalCount;
  private long rejectedSignalCount;
  private boolean reviewDeadlineReached;
  private String protocolErrorCode;
  private Decision decision;
  private String decisionReceiptId;
  private String decisionReceiptHash;
  private String terminalReviewReceiptId;
  private String terminalReviewReceiptHash;
  private String approvedActionSnapshotRef;
  private String approvedActionSnapshotHash;
  private String requiredOperationSetRef;
  private String requiredOperationSetHash;
  private int requiredOperationCount;
  private String ambiguousOperationId;
  private String ambiguousObservationReceiptId;
  private int compensationCursor;
  private String closedSnapshotRef;
  private String closedSnapshotHash;
  private String closureReceiptId;
  private String closureReceiptHash;
  private String evaluationReceiptId;
  private String evaluationReceiptHash;
  private int evaluationFailureCount;

  OutcomeWorkflowKernel(Start start) {
    this.start = Objects.requireNonNull(start, "start must not be null");
    revision = start.initialRevision();
  }

  void deadlineReached() {
    if (phase != Phase.WAITING_REVIEW || reviewDeadlineReached) {
      return;
    }
    reviewDeadlineReached = true;
    phase = Phase.WAITING_SLA_ESCALATION;
    drain();
  }

  void submit(Receipt receipt) {
    Objects.requireNonNull(receipt, "receipt must not be null");
    Receipt previous = observedReceipts.get(receipt.receiptId());
    if (previous != null) {
      if (previous.equals(receipt)) {
        recordCoalescedDuplicates(1);
      } else {
        reject("OUTCOME_RECEIPT_ID_PAYLOAD_CONFLICT");
      }
      return;
    }
    if (!receipt.authority().matches(start)) {
      reject("OUTCOME_RECEIPT_AUTHORITY_MISMATCH");
      return;
    }
    String admissionViolation = admissionViolation(receipt);
    if (admissionViolation != null) {
      reject(admissionViolation);
      return;
    }
    if (receipt.revision() <= revision) {
      reject("OUTCOME_RECEIPT_STALE_REVISION");
      return;
    }
    String priorEvent = observedEventSequences.get(receipt.committedEventSequence());
    if (priorEvent != null && !priorEvent.equals(receipt.receiptId())) {
      reject("OUTCOME_COMMITTED_EVENT_SEQUENCE_CONFLICT");
      return;
    }
    Receipt priorRevision = pendingReceipts.get(receipt.revision());
    if (priorRevision != null && !priorRevision.equals(receipt)) {
      reject("OUTCOME_RECEIPT_REVISION_CONFLICT");
      return;
    }
    boolean fillsNextGap = receipt.sourceRevision() == revision
        && receipt.revision() == revision + 1;
    if (priorRevision == null
        && pendingReceipts.size() >= MAX_PENDING_RECEIPTS
        && !fillsNextGap) {
      reject("OUTCOME_PENDING_RECEIPT_LIMIT");
      return;
    }
    if (observedReceipts.size() >= MAX_CAUSAL_RECEIPTS) {
      requireManualRecovery("OUTCOME_CAUSAL_RECEIPT_LIMIT");
      return;
    }
    if (observedReceipts.size() >= MAX_UNIQUE_RECEIPTS && !fillsNextGap) {
      requireManualRecovery("OUTCOME_ACCEPTED_RECEIPT_LIMIT");
      return;
    }
    observedReceipts.put(receipt.receiptId(), receipt);
    observedEventSequences.put(receipt.committedEventSequence(), receipt.receiptId());
    pendingReceipts.put(receipt.revision(), receipt);
    drain();
  }

  void rejectMalformed(String code) {
    reject(code);
  }

  void failCapacity(String code) {
    requireManualRecovery(code);
  }

  void recordCoalescedDuplicates(long count) {
    if (count < 1) {
      return;
    }
    try {
      duplicateSignalCount = Math.addExact(duplicateSignalCount, count);
    } catch (ArithmeticException overflow) {
      duplicateSignalCount = Long.MAX_VALUE;
    }
  }

  Snapshot snapshot() {
    List<OperationSnapshot> operationSnapshots = operations.values().stream()
        .map(Operation::snapshot)
        .toList();
    return new Snapshot(
        phase,
        revision,
        lastCommittedEventSequence,
        reviewDeadlineReached,
        duplicateSignalCount,
        rejectedSignalCount,
        protocolErrorCode,
        decision,
        decisionReceiptId,
        decisionReceiptHash,
        terminalReviewReceiptId,
        terminalReviewReceiptHash,
        approvedActionSnapshotRef,
        approvedActionSnapshotHash,
        requiredOperationSetRef,
        requiredOperationSetHash,
        requiredOperationCount,
        operationSnapshots,
        ambiguousOperationId,
        ambiguousObservationReceiptId,
        List.copyOf(compensationOrder),
        compensationCursor,
        closedSnapshotRef,
        closedSnapshotHash,
        closureReceiptId,
        closureReceiptHash,
        evaluationReceiptId,
        evaluationReceiptHash,
        evaluationFailureCount,
        List.copyOf(pendingReceipts.keySet()),
        List.copyOf(orderedReceiptIds));
  }

  private void drain() {
    while (!phase.terminal()) {
      Receipt receipt = pendingReceipts.get(revision + 1);
      if (receipt == null) {
        return;
      }
      if (receipt.sourceRevision() != revision
          || receipt.revision() != revision + 1
          || receipt.committedEventSequence() <= lastCommittedEventSequence) {
        fail("OUTCOME_RECEIPT_CAUSAL_CHAIN_MISMATCH");
        return;
      }
      if (receipt instanceof SlaReceipt && !reviewDeadlineReached) {
        return;
      }
      if (!apply(receipt)) {
        return;
      }
      pendingReceipts.remove(receipt.revision());
      revision = receipt.revision();
      lastCommittedEventSequence = receipt.committedEventSequence();
      orderedReceiptIds.add(receipt.receiptId());
    }
  }

  private boolean apply(Receipt receipt) {
    if (receipt instanceof DecisionReceipt value) {
      return applyDecision(value);
    }
    if (receipt instanceof SlaReceipt value) {
      return applySla(value);
    }
    if (receipt instanceof OperationCommandReceipt value) {
      return applyOperationCommand(value);
    }
    if (receipt instanceof OperationReceipt value) {
      return applyOperationReceipt(value);
    }
    if (receipt instanceof AttemptObservationReceipt value) {
      return applyObservation(value);
    }
    if (receipt instanceof ReconciliationReceipt value) {
      return applyReconciliation(value);
    }
    if (receipt instanceof CompensationReceipt value) {
      return applyCompensation(value);
    }
    if (receipt instanceof ClosureReceipt value) {
      return applyClosure(value);
    }
    if (receipt instanceof EvaluationReceipt value) {
      return applyEvaluation(value);
    }
    fail("OUTCOME_RECEIPT_TYPE_UNKNOWN");
    return false;
  }

  private boolean applyDecision(DecisionReceipt receipt) {
    if ((phase != Phase.WAITING_REVIEW && phase != Phase.WAITING_SLA_ESCALATION)
        || decisionReceiptId != null) {
      fail("OUTCOME_HUMAN_DECISION_OUTSIDE_REVIEW_WAIT");
      return false;
    }
    decision = receipt.decision();
    decisionReceiptId = receipt.receiptId();
    decisionReceiptHash = receipt.receiptHash();
    terminalReviewReceiptId = receipt.receiptId();
    terminalReviewReceiptHash = receipt.receiptHash();
    if (!decision.authorizesExecution()) {
      phase = switch (decision) {
        case REQUEST_MORE_EVIDENCE -> Phase.MORE_EVIDENCE_REQUESTED;
        case REJECT -> Phase.REJECTED;
        case ESCALATE_MANUAL -> Phase.MANUAL_ESCALATED;
        default -> throw new IllegalStateException("unreachable decision branch");
      };
      return true;
    }
    approvedActionSnapshotRef = receipt.approvedActionSnapshotRef();
    approvedActionSnapshotHash = receipt.approvedActionSnapshotHash();
    requiredOperationSetRef = receipt.requiredOperationSetRef();
    requiredOperationSetHash = receipt.requiredOperationSetHash();
    requiredOperationCount = receipt.requiredOperationCount();
    phase = requiredOperationCount == 0 ? Phase.CLOSURE_PENDING : Phase.EXECUTION_INTENT;
    return true;
  }

  private boolean applySla(SlaReceipt receipt) {
    if (phase != Phase.WAITING_SLA_ESCALATION || !reviewDeadlineReached
        || decisionReceiptId != null) {
      fail("OUTCOME_SLA_ESCALATION_OUTSIDE_EXPIRED_REVIEW_WAIT");
      return false;
    }
    phase = Phase.SLA_ESCALATED;
    terminalReviewReceiptId = receipt.receiptId();
    terminalReviewReceiptHash = receipt.receiptHash();
    return true;
  }

  private boolean applyOperationCommand(OperationCommandReceipt receipt) {
    if (!isApproval() || (phase != Phase.EXECUTION_INTENT && phase != Phase.EXECUTING)) {
      fail("OUTCOME_OPERATION_COMMAND_WITHOUT_APPROVAL");
      return false;
    }
    if (!receipt.requiredForClosure()) {
      fail("OUTCOME_OPERATION_NOT_IN_REQUIRED_CLOSURE_SET");
      return false;
    }
    Operation existing = operations.get(receipt.operationId());
    if (existing != null) {
      boolean sameIdentity = existing.key.equals(receipt.operationKey())
          && existing.requestHash.equals(receipt.requestHash())
          && existing.externalIdempotencyKeyHash.equals(receipt.externalIdempotencyKeyHash())
          && existing.sequence == receipt.operationSequence()
          && existing.compensable == receipt.compensable();
      if (sameIdentity
          && !existing.retryAuthorized
          && receipt.attemptNo() == existing.attemptNo) {
        return true;
      }
      if (!sameIdentity
          || !existing.retryAuthorized
          || receipt.attemptNo() != existing.attemptNo + 1) {
        fail("OUTCOME_OPERATION_RETRY_COMMAND_CONFLICT");
        return false;
      }
      existing.attemptNo = receipt.attemptNo();
      existing.retryAuthorized = false;
      return true;
    }
    String existingId = operationKeys.get(receipt.operationKey());
    if (existingId != null) {
      fail("OUTCOME_OPERATION_KEY_CONFLICT");
      return false;
    }
    if (operations.size() >= requiredOperationCount
        || receipt.operationSequence() != operations.size() + 1) {
      fail("OUTCOME_OPERATION_SEQUENCE_OR_COUNT_INVALID");
      return false;
    }
    Operation operation = new Operation(
        receipt.operationId(), receipt.operationKey(), receipt.requestHash(),
        receipt.externalIdempotencyKeyHash(), receipt.operationSequence(), receipt.compensable(),
        receipt.attemptNo());
    operations.put(operation.id, operation);
    operationKeys.put(operation.key, operation.id);
    if (operations.size() == requiredOperationCount) {
      phase = Phase.EXECUTING;
    }
    return true;
  }

  private boolean applyOperationReceipt(OperationReceipt receipt) {
    if (phase != Phase.EXECUTING || ambiguousOperationId != null) {
      fail("OUTCOME_OPERATION_RECEIPT_OUTSIDE_EXECUTION");
      return false;
    }
    Operation operation = matchingOperation(receipt.operationId(), receipt.operationKey(),
        receipt.requestHash(), receipt.externalIdempotencyKeyHash(), receipt.operationSequence(),
        receipt.requiredForClosure(), receipt.compensable());
    if (operation == null || operation.terminalStatus != null || operation.retryAuthorized) {
      fail("OUTCOME_OPERATION_RECEIPT_PARENT_INVALID");
      return false;
    }
    if (!allPreviousOperationsSucceeded(operation.sequence)) {
      fail("OUTCOME_OPERATION_RECEIPT_OUT_OF_ORDER");
      return false;
    }
    operation.terminalStatus = receipt.terminalStatus();
    operation.successReceiptId = receipt.terminalStatus() == TerminalStatus.SUCCEEDED
        ? receipt.receiptId() : null;
    operation.successReceiptHash = receipt.terminalStatus() == TerminalStatus.SUCCEEDED
        ? receipt.receiptHash() : null;
    if (receipt.terminalStatus() == TerminalStatus.FAILED) {
      beginCompensationOrManualRecovery();
    } else if (allOperationsSucceeded()) {
      phase = Phase.CLOSURE_PENDING;
    }
    return true;
  }

  private boolean applyObservation(AttemptObservationReceipt receipt) {
    if (phase != Phase.EXECUTING || ambiguousOperationId != null) {
      fail("OUTCOME_AMBIGUOUS_OBSERVATION_OUTSIDE_EXECUTION");
      return false;
    }
    Operation operation = matchingOperation(receipt.operationId(), receipt.operationKey(),
        receipt.requestHash(), receipt.externalIdempotencyKeyHash(), receipt.operationSequence(),
        receipt.requiredForClosure(), receipt.compensable());
    if (operation == null || operation.terminalStatus != null
        || operation.retryAuthorized || !allPreviousOperationsSucceeded(operation.sequence)) {
      fail("OUTCOME_AMBIGUOUS_OBSERVATION_PARENT_INVALID");
      return false;
    }
    ambiguousOperationId = operation.id;
    ambiguousObservationReceiptId = receipt.receiptId();
    phase = Phase.RECONCILING;
    return true;
  }

  private boolean applyReconciliation(ReconciliationReceipt receipt) {
    if (phase != Phase.RECONCILING
        || !Objects.equals(ambiguousOperationId, receipt.operationId())
        || !Objects.equals(ambiguousObservationReceiptId, receipt.observationReceiptId())) {
      fail("OUTCOME_RECONCILIATION_OBSERVATION_INVALID");
      return false;
    }
    Operation operation = matchingOperation(receipt.operationId(), receipt.operationKey(),
        receipt.requestHash(), receipt.externalIdempotencyKeyHash(), receipt.operationSequence(),
        receipt.requiredForClosure(), receipt.compensable());
    if (operation == null || operation.terminalStatus != null) {
      fail("OUTCOME_RECONCILIATION_PARENT_INVALID");
      return false;
    }
    ambiguousOperationId = null;
    ambiguousObservationReceiptId = null;
    switch (receipt.resolution()) {
      case CONFIRMED_SUCCESS -> {
        operation.terminalStatus = TerminalStatus.SUCCEEDED;
        operation.successReceiptId = receipt.resultRef();
        operation.successReceiptHash = receipt.resultHash();
        phase = allOperationsSucceeded() ? Phase.CLOSURE_PENDING : Phase.EXECUTING;
      }
      case CONFIRMED_FAILURE -> {
        operation.terminalStatus = TerminalStatus.FAILED;
        beginCompensationOrManualRecovery();
      }
      case NOT_FOUND_SAFE_TO_RETRY -> {
        operation.retryAuthorized = true;
        phase = Phase.EXECUTING;
      }
      case UNRESOLVED -> phase = Phase.MANUAL_RECOVERY_REQUIRED;
    }
    return true;
  }

  private boolean applyCompensation(CompensationReceipt receipt) {
    if (phase != Phase.COMPENSATING || compensationCursor >= compensationOrder.size()) {
      fail("OUTCOME_COMPENSATION_OUTSIDE_REQUIRED_ORDER");
      return false;
    }
    String expectedParentId = compensationOrder.get(compensationCursor);
    Operation parent = operations.get(expectedParentId);
    if (parent == null
        || !parent.compensable
        || parent.terminalStatus != TerminalStatus.SUCCEEDED
        || !parent.id.equals(receipt.parentOperationId())
        || !Objects.equals(parent.successReceiptId, receipt.parentSuccessReceiptId())
        || !Objects.equals(parent.successReceiptHash, receipt.parentSuccessReceiptHash())
        || receipt.reverseOrder() != compensationCursor + 1L) {
      fail("OUTCOME_COMPENSATION_PARENT_OR_ORDER_INVALID");
      return false;
    }
    parent.compensationReceiptId = receipt.receiptId();
    parent.compensationStatus = receipt.terminalStatus();
    if (receipt.terminalStatus() == TerminalStatus.FAILED) {
      phase = Phase.MANUAL_RECOVERY_REQUIRED;
      return true;
    }
    compensationCursor++;
    if (compensationCursor == compensationOrder.size()) {
      phase = Phase.MANUAL_RECOVERY_REQUIRED;
    }
    return true;
  }

  private boolean applyClosure(ClosureReceipt receipt) {
    if (phase != Phase.CLOSURE_PENDING
        || !isApproval()
        || operations.size() != requiredOperationCount
        || !allOperationsSucceeded()
        || ambiguousOperationId != null
        || !compensationOrder.isEmpty()) {
      fail("OUTCOME_CLOSURE_PREREQUISITES_NOT_SATISFIED");
      return false;
    }
    closedSnapshotRef = receipt.closedSnapshotRef();
    closedSnapshotHash = receipt.closedSnapshotHash();
    closureReceiptId = receipt.receiptId();
    closureReceiptHash = receipt.receiptHash();
    phase = Phase.CLOSED;
    return true;
  }

  private boolean applyEvaluation(EvaluationReceipt receipt) {
    if (phase != Phase.CLOSED
        || !Objects.equals(closedSnapshotRef, receipt.closedSnapshotRef())
        || !Objects.equals(closedSnapshotHash, receipt.closedSnapshotHash())) {
      fail("OUTCOME_EVALUATION_CLOSED_SNAPSHOT_MISMATCH");
      return false;
    }
    evaluationReceiptId = receipt.receiptId();
    evaluationReceiptHash = receipt.receiptHash();
    if (receipt.evaluationStatus() == EvaluationStatus.SUCCEEDED) {
      phase = Phase.EVALUATED;
    } else {
      evaluationFailureCount++;
    }
    return true;
  }

  private Operation matchingOperation(
      String id,
      String key,
      String requestHash,
      String externalIdempotencyKeyHash,
      int operationSequence,
      boolean requiredForClosure,
      boolean compensable) {
    Operation operation = operations.get(id);
    if (operation == null
        || !operation.key.equals(key)
        || !operation.requestHash.equals(requestHash)
        || !operation.externalIdempotencyKeyHash.equals(externalIdempotencyKeyHash)
        || operation.sequence != operationSequence
        || !requiredForClosure
        || operation.compensable != compensable) {
      return null;
    }
    return operation;
  }

  private boolean allPreviousOperationsSucceeded(int sequence) {
    for (Operation operation : operations.values()) {
      if (operation.sequence >= sequence) {
        break;
      }
      if (operation.terminalStatus != TerminalStatus.SUCCEEDED) {
        return false;
      }
    }
    return true;
  }

  private boolean allOperationsSucceeded() {
    return operations.size() == requiredOperationCount
        && operations.values().stream()
            .allMatch(operation -> operation.terminalStatus == TerminalStatus.SUCCEEDED);
  }

  private void beginCompensationOrManualRecovery() {
    compensationOrder.clear();
    List<Operation> reversed = new ArrayList<>(operations.values());
    Collections.reverse(reversed);
    for (Operation operation : reversed) {
      if (operation.terminalStatus == TerminalStatus.SUCCEEDED && operation.compensable) {
        compensationOrder.add(operation.id);
      } else if (operation.terminalStatus == TerminalStatus.SUCCEEDED) {
        phase = Phase.MANUAL_RECOVERY_REQUIRED;
        return;
      }
    }
    compensationCursor = 0;
    phase = compensationOrder.isEmpty()
        ? Phase.MANUAL_RECOVERY_REQUIRED : Phase.COMPENSATING;
  }

  private boolean isApproval() {
    return decision == Decision.APPROVE || decision == Decision.MODIFY_AND_APPROVE;
  }

  private String admissionViolation(Receipt receipt) {
    if (receipt instanceof DecisionReceipt decisionReceipt) {
      if (decisionReceipt.syntheticOnly() != start.syntheticOnly()) {
        return "OUTCOME_DECISION_RUNTIME_MISMATCH";
      }
      if (decisionReceipt.committedAt().isBefore(start.openedAt())) {
        return "OUTCOME_DECISION_BEFORE_REVIEW_OPEN";
      }
      if (decisionReceipt.committedAt().isAfter(start.reviewDeadlineAt())) {
        return "OUTCOME_DECISION_COMMITTED_AFTER_DEADLINE";
      }
      return null;
    }
    if (receipt instanceof SlaReceipt slaReceipt) {
      if (slaReceipt.syntheticOnly() != start.syntheticOnly()) {
        return "OUTCOME_SLA_RUNTIME_MISMATCH";
      }
      if (!slaReceipt.deadlineAt().equals(start.reviewDeadlineAt())
          || slaReceipt.committedAt().isBefore(start.reviewDeadlineAt())) {
        return "OUTCOME_SLA_COMMIT_BOUNDARY_INVALID";
      }
      return null;
    }
    if (receipt instanceof OperationCommandReceipt command) {
      if (command.runtimeMode() != start.runtimeMode()
          || command.syntheticOnly() != start.syntheticOnly()) {
        return "OUTCOME_OPERATION_COMMAND_RUNTIME_MISMATCH";
      }
      if (isShadow()
          && (!command.syntheticNoop() || command.compensable())) {
        return "OUTCOME_SHADOW_COMMAND_EFFECT_FORBIDDEN";
      }
      if (!isShadow() && command.syntheticNoop()) {
        return "OUTCOME_FORMAL_COMMAND_SYNTHETIC_MISMATCH";
      }
      return null;
    }
    if (receipt instanceof OperationReceipt operationReceipt) {
      if (isShadow()) {
        return "OUTCOME_SHADOW_FORMAL_OPERATION_RECEIPT_FORBIDDEN";
      }
      if (operationReceipt.runtimeMode() != start.runtimeMode()
          || operationReceipt.syntheticNoop()) {
        return "OUTCOME_OPERATION_RECEIPT_RUNTIME_MISMATCH";
      }
      return null;
    }
    if (receipt instanceof ReconciliationReceipt reconciliationReceipt
        && isShadow()
        && (reconciliationReceipt.resolution() == ReconciliationResolution.CONFIRMED_SUCCESS
            || reconciliationReceipt.resolution() == ReconciliationResolution.CONFIRMED_FAILURE)) {
      return "OUTCOME_SHADOW_AUTHORITATIVE_RECONCILIATION_FORBIDDEN";
    }
    if (receipt instanceof CompensationReceipt && isShadow()) {
      return "OUTCOME_SHADOW_COMPENSATION_RECEIPT_FORBIDDEN";
    }
    if (receipt instanceof ClosureReceipt && isShadow()) {
      return "OUTCOME_SHADOW_CLOSURE_RECEIPT_FORBIDDEN";
    }
    if (receipt instanceof EvaluationReceipt && isShadow()) {
      return "OUTCOME_SHADOW_EVALUATION_RECEIPT_FORBIDDEN";
    }
    return null;
  }

  private boolean isShadow() {
    return start.runtimeMode()
        == OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW;
  }

  private void reject(String code) {
    rejectedSignalCount++;
    protocolErrorCode = code;
  }

  private void fail(String code) {
    reject(code);
    phase = Phase.FAILED;
  }

  private void requireManualRecovery(String code) {
    reject(code);
    phase = Phase.MANUAL_RECOVERY_REQUIRED;
  }

  record Snapshot(
      Phase phase,
      long revision,
      long lastCommittedEventSequence,
      boolean reviewDeadlineReached,
      long duplicateSignalCount,
      long rejectedSignalCount,
      String protocolErrorCode,
      Decision decision,
      String decisionReceiptId,
      String decisionReceiptHash,
      String terminalReviewReceiptId,
      String terminalReviewReceiptHash,
      String approvedActionSnapshotRef,
      String approvedActionSnapshotHash,
      String requiredOperationSetRef,
      String requiredOperationSetHash,
      int requiredOperationCount,
      List<OperationSnapshot> operations,
      String ambiguousOperationId,
      String ambiguousObservationReceiptId,
      List<String> compensationOrder,
      int compensationCursor,
      String closedSnapshotRef,
      String closedSnapshotHash,
      String closureReceiptId,
      String closureReceiptHash,
      String evaluationReceiptId,
      String evaluationReceiptHash,
      int evaluationFailureCount,
      List<Long> pendingRevisions,
      List<String> orderedReceiptIds) {}

  record OperationSnapshot(
      String operationId,
      String operationKey,
      String requestHash,
      String externalIdempotencyKeyHash,
      int sequence,
      boolean compensable,
      long attemptNo,
      boolean retryAuthorized,
      TerminalStatus terminalStatus,
      String successReceiptId,
      String successReceiptHash,
      TerminalStatus compensationStatus,
      String compensationReceiptId) {}

  private static final class Operation {
    private final String id;
    private final String key;
    private final String requestHash;
    private final String externalIdempotencyKeyHash;
    private final int sequence;
    private final boolean compensable;
    private long attemptNo;
    private boolean retryAuthorized;
    private TerminalStatus terminalStatus;
    private String successReceiptId;
    private String successReceiptHash;
    private TerminalStatus compensationStatus;
    private String compensationReceiptId;

    private Operation(String id, String key, String requestHash, String externalIdempotencyKeyHash,
        int sequence, boolean compensable, long attemptNo) {
      this.id = id;
      this.key = key;
      this.requestHash = requestHash;
      this.externalIdempotencyKeyHash = externalIdempotencyKeyHash;
      this.sequence = sequence;
      this.compensable = compensable;
      this.attemptNo = attemptNo;
    }

    private OperationSnapshot snapshot() {
      return new OperationSnapshot(id, key, requestHash, externalIdempotencyKeyHash, sequence,
          compensable, attemptNo, retryAuthorized, terminalStatus, successReceiptId,
          successReceiptHash, compensationStatus, compensationReceiptId);
    }
  }

  private static void validateReceipt(
      Authority authority,
      String receiptId,
      String receiptHash,
      long sourceRevision,
      long revision,
      long committedEventSequence) {
    Objects.requireNonNull(authority, "authority must not be null");
    requireComponent(receiptId, "receiptId");
    requireHash(receiptHash, "receiptHash");
    requireNonNegative(sourceRevision, "sourceRevision");
    requireNonNegative(revision, "revision");
    if (revision < sourceRevision) {
      throw new IllegalArgumentException("revision cannot precede sourceRevision");
    }
    requirePositive(committedEventSequence, "committedEventSequence");
  }

  private static void validateOperationResult(
      String operationId,
      String operationKey,
      String requestHash,
      String externalIdempotencyKeyHash,
      int operationSequence,
      TerminalStatus terminalStatus,
      String resultRef,
      String resultHash) {
    requireComponent(operationId, "operationId");
    requireOperationKey(operationKey, "operationKey");
    requireHash(requestHash, "requestHash");
    requireHash(externalIdempotencyKeyHash, "externalIdempotencyKeyHash");
    if (operationSequence < 1 || operationSequence > MAX_OPERATIONS) {
      throw new IllegalArgumentException("operationSequence must be between 1 and 64");
    }
    Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
    requireRef(resultRef, "resultRef");
    requireHash(resultHash, "resultHash");
  }

  private static void requireComponent(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }

  private static void requireOperationKey(String value, String field) {
    if (value == null || value.length() > 512
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,511}")) {
      throw new IllegalArgumentException(field + " must be a bounded operation key");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }

  private static void requireRef(String value, String field) {
    if (value == null || value.length() > 256 || value.contains("://")
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
      throw new IllegalArgumentException(field + " must be a bounded immutable reference");
    }
  }

  private static void requirePositive(long value, String field) {
    if (value < 1 || value > 9_007_199_254_740_991L) {
      throw new IllegalArgumentException(field + " must be a positive safe integer");
    }
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0 || value > 9_007_199_254_740_991L) {
      throw new IllegalArgumentException(field + " must be a non-negative safe integer");
    }
  }
}

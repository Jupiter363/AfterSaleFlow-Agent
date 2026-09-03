package com.example.dispute.workflow.temporal.room.outcome;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeAttemptReconciliationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeCompensationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeExecutionAttemptObservation;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeSlaEscalationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

final class OutcomeReceiptTestFactory {

  static final String WORKFLOW_ID = "OUTCOME_WORKFLOW_P7";
  static final String CASE_ID = "CASE_P7_SYNTHETIC";
  static final String REVIEW_TASK_ID = "REVIEW_TASK_P7";
  static final String PACKET_REF = "ref:packet:p7";
  static final String DRAFT_REF = "ref:draft:p7";
  static final String ACTION_REF = "ref:action:p7";
  static final String MODIFIED_ACTION_REF = "ref:action:p7:modified";
  static final String OPERATION_SET_REF = "ref:operation-set:p7";
  static final long EPOCH = 0;
  static final long FENCE = 71;

  private final OutcomeWorkflowStart start;

  OutcomeReceiptTestFactory(OutcomeWorkflowStart start) {
    this.start = start;
  }

  static OutcomeWorkflowStart start(Instant now, Duration reviewWindow, int operationCount) {
    return new OutcomeWorkflowStart(
        OutcomeWorkflowStart.SCHEMA_VERSION,
        WORKFLOW_ID,
        CASE_ID,
        REVIEW_TASK_ID,
        PACKET_REF,
        hash("packet"),
        DRAFT_REF,
        hash("draft"),
        ACTION_REF,
        hash("action"),
        OPERATION_SET_REF,
        hash("operation-set:" + operationCount),
        operationCount,
        EPOCH,
        0,
        FENCE,
        now,
        now.plus(reviewWindow),
        OutcomeWireTypes.RuntimeMode.TEMPORAL,
        "outcome_workflow_v1",
        "outcome_policy_v1",
        "outcome_graph_v1",
        "outcome_prompt_v1",
        "outcome_model_v1",
        false);
  }

  OutcomeReviewDecisionReceipt decision(
      OutcomeWireTypes.ReviewDecision decision,
      long sourceRevision,
      long eventSequence,
      Instant committedAt) {
    boolean approves = decision == OutcomeWireTypes.ReviewDecision.APPROVE
        || decision == OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE;
    String approvedRef = decision == OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE
        ? MODIFIED_ACTION_REF : approves ? ACTION_REF : null;
    String approvedHash = decision == OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE
        ? hash("action-modified") : approves ? hash("action") : null;
    return new OutcomeReviewDecisionReceipt(
        OutcomeReviewDecisionReceipt.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        "DECISION_RECEIPT_" + eventSequence,
        hash("decision-receipt:" + eventSequence),
        start.reviewTaskId(),
        "ref:reviewer:p7",
        start.frozenReviewPacketRef(),
        start.frozenReviewPacketHash(),
        start.actionSnapshotRef(),
        start.actionSnapshotHash(),
        approvedRef,
        approvedHash,
        "ref:decision:" + eventSequence,
        hash("decision-record:" + eventSequence),
        "ref:reason:" + eventSequence,
        hash("reason:" + eventSequence),
        approves ? hash("decision-operation-key:" + eventSequence) : null,
        start.requiredOperationSetRef(),
        start.requiredOperationSetHash(),
        start.requiredOperationCount(),
        decision,
        approves,
        hash("decision-request:" + decision + ':' + eventSequence),
        hash("decision-idempotency:" + eventSequence),
        start.policyVersion(),
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence,
        committedAt,
        start.syntheticOnly());
  }

  OutcomeSlaEscalationReceipt sla(long sourceRevision, long eventSequence, Instant committedAt) {
    return new OutcomeSlaEscalationReceipt(
        OutcomeSlaEscalationReceipt.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        "SLA_RECEIPT_" + eventSequence,
        hash("sla-receipt:" + eventSequence),
        start.reviewTaskId(),
        start.frozenReviewPacketRef(),
        start.frozenReviewPacketHash(),
        OutcomeWireTypes.SlaFactType.SYSTEM_SLA_ESCALATION,
        OutcomeWireTypes.ActorType.SYSTEM,
        start.reviewDeadlineAt(),
        committedAt,
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence,
        false,
        false,
        start.syntheticOnly());
  }

  OutcomeOperationCommand operationCommand(
      OutcomeReviewDecisionReceipt decision,
      int operationSequence,
      long sourceRevision,
      long eventSequence,
      long attemptNo,
      boolean compensable) {
    return new OutcomeOperationCommand(
        OutcomeOperationCommand.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        "OPERATION_COMMAND_" + operationSequence + '_' + attemptNo,
        operationId(operationSequence),
        operationKeyHash(operationSequence),
        decision.receiptId(),
        decision.receiptHash(),
        decision.approvedActionSnapshotRef(),
        decision.approvedActionSnapshotHash(),
        "ref:operation-request:" + operationSequence,
        requestHash(operationSequence),
        idempotencyHash(operationSequence),
        OutcomeWireTypes.EffectClass.REVERSIBLE,
        true,
        compensable,
        operationSequence,
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence,
        attemptNo,
        start.reviewDeadlineAt().plus(Duration.ofHours(1)),
        "synthetic_tool_capability_v1",
        start.runtimeMode(),
        null,
        false);
  }

  OutcomeOperationReceipt operationReceipt(
      int operationSequence,
      OutcomeWireTypes.TerminalStatus status,
      long sourceRevision,
      long eventSequence,
      boolean compensable) {
    return new OutcomeOperationReceipt(
        OutcomeOperationReceipt.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        operationReceiptId(operationSequence, eventSequence),
        hash("operation-receipt:" + operationSequence + ':' + eventSequence),
        operationId(operationSequence),
        operationKeyHash(operationSequence),
        requestHash(operationSequence),
        idempotencyHash(operationSequence),
        "ref:operation-result:" + operationSequence + ':' + eventSequence,
        hash("operation-result:" + operationSequence + ':' + eventSequence),
        status,
        operationSequence,
        true,
        compensable,
        start.reviewDeadlineAt().plusSeconds(eventSequence),
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence,
        OutcomeWireTypes.RuntimeMode.TEMPORAL,
        false);
  }

  OutcomeExecutionAttemptObservation observation(
      int operationSequence, long sourceRevision, long eventSequence, boolean compensable) {
    return new OutcomeExecutionAttemptObservation(
        OutcomeExecutionAttemptObservation.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        observationId(eventSequence),
        hash("observation:" + eventSequence),
        operationId(operationSequence),
        operationKeyHash(operationSequence),
        requestHash(operationSequence),
        idempotencyHash(operationSequence),
        1,
        operationSequence,
        true,
        compensable,
        OutcomeWireTypes.AttemptObservationStatus.AMBIGUOUS,
        OutcomeWireTypes.ExternalEffectTruth.UNKNOWN,
        OutcomeWireTypes.OperationStatus.RECONCILING,
        start.reviewDeadlineAt().plusSeconds(eventSequence),
        start.reviewDeadlineAt().plusSeconds(eventSequence + 1),
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence,
        true,
        true,
        true);
  }

  OutcomeAttemptReconciliationReceipt reconciliation(
      int operationSequence,
      long sourceRevision,
      long eventSequence,
      long observationSequence,
      OutcomeWireTypes.ReconciliationResolution resolution,
      boolean compensable) {
    boolean confirmed = resolution == OutcomeWireTypes.ReconciliationResolution.CONFIRMED_SUCCESS
        || resolution == OutcomeWireTypes.ReconciliationResolution.CONFIRMED_FAILURE;
    return new OutcomeAttemptReconciliationReceipt(
        OutcomeAttemptReconciliationReceipt.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        "RECONCILIATION_RECEIPT_" + eventSequence,
        hash("reconciliation:" + eventSequence),
        observationId(observationSequence),
        hash("observation:" + observationSequence),
        operationId(operationSequence),
        operationKeyHash(operationSequence),
        requestHash(operationSequence),
        idempotencyHash(operationSequence),
        operationSequence,
        true,
        compensable,
        resolution,
        confirmed ? "SUCCESS_RECEIPT_" + operationSequence : null,
        confirmed ? hash("success-receipt:" + operationSequence) : null,
        resolution == OutcomeWireTypes.ReconciliationResolution.NOT_FOUND_SAFE_TO_RETRY,
        resolution == OutcomeWireTypes.ReconciliationResolution.UNRESOLVED,
        start.reviewDeadlineAt().plusSeconds(eventSequence),
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence);
  }

  OutcomeCompensationReceipt compensation(
      int originalOperationSequence,
      String originalSuccessReceiptId,
      String originalSuccessReceiptHash,
      long reverseOrder,
      OutcomeWireTypes.CompensationStatus status,
      long sourceRevision,
      long eventSequence) {
    String receiptId = "COMPENSATION_RECEIPT_" + eventSequence;
    String receiptHash = hash("compensation-receipt:" + eventSequence);
    return new OutcomeCompensationReceipt(
        OutcomeCompensationReceipt.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        receiptId,
        receiptHash,
        operationId(originalOperationSequence),
        originalSuccessReceiptId,
        originalSuccessReceiptHash,
        "COMPENSATION_OPERATION_" + originalOperationSequence,
        receiptId,
        receiptHash,
        hash("compensation-request:" + originalOperationSequence),
        "compensation_policy_v1",
        reverseOrder,
        status,
        start.reviewDeadlineAt().plusSeconds(eventSequence),
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence);
  }

  OutcomeClosureReceipt closure(
      OutcomeReviewDecisionReceipt decision, long sourceRevision, long eventSequence) {
    return new OutcomeClosureReceipt(
        OutcomeClosureReceipt.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        "CLOSURE_RECEIPT_" + eventSequence,
        hash("closure-receipt:" + eventSequence),
        decision.receiptId(),
        decision.receiptHash(),
        decision.approvedActionSnapshotRef(),
        decision.approvedActionSnapshotHash(),
        start.requiredOperationSetRef(),
        start.requiredOperationSetHash(),
        start.requiredOperationCount(),
        "ref:terminal-receipt-set:" + eventSequence,
        hash("terminal-receipt-set:" + eventSequence),
        "ref:closed-snapshot:" + eventSequence,
        hash("closed-snapshot:" + eventSequence),
        eventSequence,
        0,
        0,
        0,
        0,
        0,
        start.reviewDeadlineAt().plusSeconds(eventSequence),
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence,
        false);
  }

  OutcomeEvaluationReceipt evaluation(
      OutcomeClosureReceipt closure,
      OutcomeWireTypes.EvaluationStatus status,
      long sourceRevision,
      long eventSequence) {
    return new OutcomeEvaluationReceipt(
        OutcomeEvaluationReceipt.SCHEMA_VERSION,
        start.workflowId(),
        start.caseId(),
        "EVALUATION_RECEIPT_" + eventSequence,
        hash("evaluation-receipt:" + eventSequence),
        closure.closedSnapshotRef(),
        closure.closedSnapshotHash(),
        "ref:evaluation:" + eventSequence,
        hash("evaluation:" + eventSequence),
        status,
        start.reviewDeadlineAt().plusSeconds(eventSequence),
        start.epoch(),
        sourceRevision,
        sourceRevision + 1,
        start.fence(),
        eventSequence,
        false);
  }

  static String operationId(int sequence) {
    return "OPERATION_" + sequence;
  }

  static String operationKeyHash(int sequence) {
    return hash("operation-key:" + sequence);
  }

  static String requestHash(int sequence) {
    return hash("operation-request:" + sequence);
  }

  static String idempotencyHash(int sequence) {
    return hash("operation-idempotency:" + sequence);
  }

  static String operationReceiptId(int operationSequence, long eventSequence) {
    return "OPERATION_RECEIPT_" + operationSequence + '_' + eventSequence;
  }

  static String observationId(long eventSequence) {
    return "OBSERVATION_" + eventSequence;
  }

  static String hash(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}

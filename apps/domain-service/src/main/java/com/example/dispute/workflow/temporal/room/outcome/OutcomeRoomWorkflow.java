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
import com.example.dispute.workflow.contract.outcome.v1.OutcomeRoomProtocol;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeSlaEscalationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Unregistered Phase 7 Outcome workflow contract. */
@WorkflowInterface
public interface OutcomeRoomWorkflow {

  @WorkflowMethod(name = OutcomeRoomProtocol.WORKFLOW_TYPE)
  OutcomeProjection run(OutcomeWorkflowStart start);

  @SignalMethod(name = OutcomeRoomProtocol.REVIEW_DECISION_SIGNAL)
  void reviewDecisionCommitted(OutcomeReviewDecisionReceipt receipt);

  /**
   * Admits a Review receipt synchronously and returns the Outcome kernel's durable decision.
   * A delivered signal alone is intentionally not evidence that the kernel accepted the receipt.
   */
  @UpdateMethod(name = "outcomeReviewDecisionAccepted")
  OutcomeReviewDecisionAcceptance reviewDecisionAccepted(OutcomeReviewDecisionReceipt receipt);

  /**
   * Completes a formally accepted Review only after the parent has made command routing durable.
   * This is deliberately a separate Update: review acceptance must not race the Java-owned
   * completion Activity's revision mutation.
   */
  @UpdateMethod(name = "completeTargetOutcomeAfterRouting")
  OutcomeCompletionResult completeTargetOutcomeAfterRouting(OutcomeCompletionRequest request);

  @SignalMethod(name = OutcomeRoomProtocol.SLA_ESCALATION_SIGNAL)
  void slaEscalationCommitted(OutcomeSlaEscalationReceipt receipt);

  @SignalMethod(name = OutcomeRoomProtocol.OPERATION_COMMAND_SIGNAL)
  void operationCommandCommitted(OutcomeOperationCommand command);

  @SignalMethod(name = OutcomeRoomProtocol.OPERATION_RECEIPT_SIGNAL)
  void operationReceiptCommitted(OutcomeOperationReceipt receipt);

  @SignalMethod(name = OutcomeRoomProtocol.ATTEMPT_OBSERVATION_SIGNAL)
  void attemptObservationCommitted(OutcomeExecutionAttemptObservation observation);

  @SignalMethod(name = OutcomeRoomProtocol.RECONCILIATION_SIGNAL)
  void attemptReconciliationCommitted(OutcomeAttemptReconciliationReceipt receipt);

  @SignalMethod(name = OutcomeRoomProtocol.COMPENSATION_RECEIPT_SIGNAL)
  void compensationReceiptCommitted(OutcomeCompensationReceipt receipt);

  @SignalMethod(name = OutcomeRoomProtocol.CLOSURE_RECEIPT_SIGNAL)
  void closureReceiptCommitted(OutcomeClosureReceipt receipt);

  @SignalMethod(name = OutcomeRoomProtocol.EVALUATION_RECEIPT_SIGNAL)
  void evaluationReceiptCommitted(OutcomeEvaluationReceipt receipt);

  @QueryMethod(name = OutcomeRoomProtocol.PROJECTION_QUERY)
  OutcomeProjection projection();

  @QueryMethod(name = "outcomeDiagnostics")
  OutcomeWorkflowDiagnostics diagnostics();
}

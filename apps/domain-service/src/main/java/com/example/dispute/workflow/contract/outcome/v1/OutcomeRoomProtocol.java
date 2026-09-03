package com.example.dispute.workflow.contract.outcome.v1;

public final class OutcomeRoomProtocol {

    public static final String WORKFLOW_TYPE = "OutcomeRoomWorkflow";
    public static final String REVIEW_DECISION_SIGNAL = "reviewDecisionCommitted";
    public static final String SLA_ESCALATION_SIGNAL = "slaEscalationCommitted";
    public static final String OPERATION_COMMAND_SIGNAL = "operationCommandCommitted";
    public static final String OPERATION_RECEIPT_SIGNAL = "operationReceiptCommitted";
    public static final String ATTEMPT_OBSERVATION_SIGNAL = "attemptObservationCommitted";
    public static final String RECONCILIATION_SIGNAL = "attemptReconciliationCommitted";
    public static final String COMPENSATION_RECEIPT_SIGNAL = "compensationReceiptCommitted";
    public static final String CLOSURE_RECEIPT_SIGNAL = "closureReceiptCommitted";
    public static final String EVALUATION_RECEIPT_SIGNAL = "evaluationReceiptCommitted";
    public static final String PROJECTION_QUERY = "outcomeProjection";

    private OutcomeRoomProtocol() {}
}

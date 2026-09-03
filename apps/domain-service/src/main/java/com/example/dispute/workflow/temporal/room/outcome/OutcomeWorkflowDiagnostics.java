package com.example.dispute.workflow.temporal.room.outcome;

import java.util.List;

/** Bounded engineering-only query view for deterministic workflow verification. */
public record OutcomeWorkflowDiagnostics(
    String schemaVersion,
    String workflowId,
    String caseId,
    String phase,
    boolean reviewDeadlineReached,
    long revision,
    long lastCommittedEventSequence,
    long duplicateSignalCount,
    long rejectedSignalCount,
    String protocolErrorCode,
    String decision,
    String terminalReviewReceiptId,
    String ambiguousOperationId,
    String ambiguousObservationId,
    List<Long> pendingRevisions,
    List<String> orderedReceiptIds,
    List<String> orderedOperationIds,
    List<String> compensationOrder,
    int compensationCursor,
    String closureReceiptId,
    String evaluationReceiptId,
    int evaluationFailureCount) {

  public static final String SCHEMA_VERSION = "outcome-workflow-diagnostics.v1";

  public OutcomeWorkflowDiagnostics {
    pendingRevisions = pendingRevisions == null ? List.of() : List.copyOf(pendingRevisions);
    orderedReceiptIds = orderedReceiptIds == null ? List.of() : List.copyOf(orderedReceiptIds);
    orderedOperationIds =
        orderedOperationIds == null ? List.of() : List.copyOf(orderedOperationIds);
    compensationOrder = compensationOrder == null ? List.of() : List.copyOf(compensationOrder);
  }
}

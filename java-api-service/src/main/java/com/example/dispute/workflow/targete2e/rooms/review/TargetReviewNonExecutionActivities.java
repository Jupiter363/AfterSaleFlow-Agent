package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.ReviewDecision;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.time.Instant;
import java.util.Objects;

/** Java-owned disposition of a formal Review decision which authorizes no execution. */
@ActivityInterface
public interface TargetReviewNonExecutionActivities {
  @ActivityMethod(name = "CompleteTargetReviewNonExecution")
  CompletionResult complete(CompletionRequest request);

  @ActivityMethod(name = "LoadAppliedTargetReviewNonExecution")
  CompletionResult loadApplied(LoadRequest request);

  record CompletionRequest(
      OutcomeWorkflowStart start,
      OutcomeReviewDecisionReceipt decision,
      CaseCommandRef command,
      long expectedProcessRevision,
      long expectedRoomRevision) {
    public CompletionRequest {
      start = Objects.requireNonNull(start, "start");
      decision = Objects.requireNonNull(decision, "decision");
      command = Objects.requireNonNull(command, "command");
      if (expectedProcessRevision < 0
          || expectedRoomRevision < 0
          || command.expectedProcessRevision() != expectedProcessRevision
          || start.revision() != expectedRoomRevision) {
        throw new IllegalArgumentException("target Review non-execution revisions are invalid");
      }
      requireNonExecutable(decision);
    }
  }

  record LoadRequest(
      OutcomeWorkflowStart start,
      OutcomeReviewDecisionReceipt decision,
      CaseCommandRef command) {
    public LoadRequest {
      start = Objects.requireNonNull(start, "start");
      decision = Objects.requireNonNull(decision, "decision");
      command = Objects.requireNonNull(command, "command");
      requireNonExecutable(decision);
    }
  }

  record EvidenceTransition(
      String epochId,
      String roomId,
      long roomEpoch,
      long fencingToken,
      long processRevision,
      long roomRevision,
      String workflowId,
      Instant deadlineAt) {
    public EvidenceTransition {
      requireText(epochId, "epochId");
      requireText(roomId, "roomId");
      requireText(workflowId, "workflowId");
      deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
      if (roomEpoch < 0 || fencingToken < 1 || processRevision < 0 || roomRevision < 0) {
        throw new IllegalArgumentException("target Review Evidence transition coordinates are invalid");
      }
    }
  }

  record DispositionReceipt(
      String schemaVersion,
      String receiptId,
      String tenantSurrogate,
      String caseId,
      String caseWorkflowId,
      String caseWorkflowRunId,
      String commandId,
      String decisionRecordId,
      String decisionRecordHash,
      ReviewDecision decision,
      long sourceRoomEpoch,
      long sourceFencingToken,
      long terminalProcessRevision,
      long terminalRoomRevision,
      EvidenceTransition evidenceTransition,
      Instant committedAt) {
    public static final String SCHEMA_VERSION = "target-review-non-execution-disposition.v1";

    public DispositionReceipt {
      if (!SCHEMA_VERSION.equals(schemaVersion)) {
        throw new IllegalArgumentException("target Review disposition schema is invalid");
      }
      requireText(receiptId, "receiptId");
      requireText(tenantSurrogate, "tenantSurrogate");
      requireText(caseId, "caseId");
      requireText(caseWorkflowId, "caseWorkflowId");
      requireText(caseWorkflowRunId, "caseWorkflowRunId");
      requireText(commandId, "commandId");
      requireText(decisionRecordId, "decisionRecordId");
      requireHash(decisionRecordHash, "decisionRecordHash");
      decision = Objects.requireNonNull(decision, "decision");
      committedAt = Objects.requireNonNull(committedAt, "committedAt");
      if (sourceRoomEpoch < 1
          || sourceFencingToken < 1
          || terminalProcessRevision < 0
          || terminalRoomRevision < 0
          || !isNonExecutable(decision)
          || (decision == ReviewDecision.REQUEST_MORE_EVIDENCE) != (evidenceTransition != null)) {
        throw new IllegalArgumentException("target Review disposition branch is invalid");
      }
    }
  }

  record CompletionResult(DispositionReceipt receipt, String receiptHash) {
    public CompletionResult {
      receipt = Objects.requireNonNull(receipt, "receipt");
      requireHash(receiptHash, "receiptHash");
    }

    public boolean terminalCaseProcess() {
      return receipt.decision() != ReviewDecision.REQUEST_MORE_EVIDENCE;
    }

    public TargetRoomProgressReceipt sourceProgressReceipt() {
      return new TargetRoomProgressReceipt(
          com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.REVIEW,
          receipt.sourceRoomEpoch(),
          receipt.sourceFencingToken(),
          receipt.terminalProcessRevision(),
          receipt.terminalRoomRevision(),
          receipt.receiptId(),
          receiptHash);
    }
  }

  private static void requireNonExecutable(OutcomeReviewDecisionReceipt decision) {
    if (decision.executionAuthorized() || !isNonExecutable(decision.decision())) {
      throw new IllegalArgumentException("target Review disposition requires a non-executable decision");
    }
  }

  private static boolean isNonExecutable(ReviewDecision decision) {
    return decision == ReviewDecision.REJECT
        || decision == ReviewDecision.REQUEST_MORE_EVIDENCE
        || decision == ReviewDecision.ESCALATE_MANUAL;
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a sha256");
    }
  }
}

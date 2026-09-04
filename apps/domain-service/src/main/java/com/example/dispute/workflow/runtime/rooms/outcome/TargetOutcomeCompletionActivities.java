package com.example.dispute.workflow.runtime.rooms.outcome;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;
import java.util.Objects;

/**
 * Java-owned, durable completion relay for a formal target Outcome. It neither invokes a tool nor
 * accepts Graph output: a TEMPORAL workflow can only replay the facts returned by this activity.
 */
@ActivityInterface
public interface TargetOutcomeCompletionActivities {
  @ActivityMethod(name = "CompleteTargetOutcome")
  CompletionResult complete(CompletionRequest request);

  @ActivityMethod(name = "LoadTargetOutcomeTerminalProgress")
  TargetRoomProgressReceipt loadTerminalProgress(TerminalProgressRequest request);

  /** Exact durable identity needed to recover a parent after B committed but before it observed B. */
  record TerminalProgressRequest(
      String workflowId,
      String caseId,
      long outcomeEpoch,
      long fencingToken,
      String humanReceiptId,
      String humanReceiptHash,
      long humanReceiptRevision) {
    public TerminalProgressRequest {
      if (workflowId == null
          || workflowId.isBlank()
          || caseId == null
          || caseId.isBlank()
          || outcomeEpoch < 0
          || fencingToken < 1
          || humanReceiptId == null
          || humanReceiptId.isBlank()
          || humanReceiptHash == null
          || !humanReceiptHash.matches("[0-9a-f]{64}")
          || humanReceiptRevision < 0) {
        throw new IllegalArgumentException("target Outcome terminal progress lookup is invalid");
      }
    }
  }

  record CompletionRequest(
      OutcomeWorkflowStart start,
      OutcomeReviewDecisionReceipt humanDecision,
      long expectedRevision,
      long expectedCommittedEventSequence,
      OutcomeCompletionRequest completionRequest) {
    public CompletionRequest {
      start = Objects.requireNonNull(start, "start");
      humanDecision = Objects.requireNonNull(humanDecision, "humanDecision");
      if (start.runtimeMode() != OutcomeWireTypes.RuntimeMode.TEMPORAL || start.syntheticOnly()
          || expectedRevision < 0 || expectedCommittedEventSequence < 1
          || completionRequest == null) {
        throw new IllegalArgumentException("target Outcome completion requires formal current authority");
      }
    }
  }

  record CompletionResult(
      List<OutcomeOperationCommand> operationCommands,
      List<OutcomeOperationReceipt> operationReceipts,
      OutcomeClosureReceipt closureReceipt,
      OutcomeEvaluationReceipt evaluationReceipt,
      TargetRoomProgressReceipt terminalProgressReceipt) {
    public CompletionResult {
      operationCommands = List.copyOf(Objects.requireNonNull(operationCommands, "operationCommands"));
      operationReceipts = List.copyOf(Objects.requireNonNull(operationReceipts, "operationReceipts"));
      for (OutcomeOperationCommand command : operationCommands) {
        if (command.runtimeMode() != OutcomeWireTypes.RuntimeMode.TEMPORAL || command.syntheticOnly()) {
          throw new IllegalArgumentException("target Outcome command is not formal");
        }
      }
      for (OutcomeOperationReceipt receipt : operationReceipts) {
        if (receipt.runtimeMode() != OutcomeWireTypes.RuntimeMode.TEMPORAL || receipt.syntheticNoop()) {
          throw new IllegalArgumentException("target Outcome receipt is not formal");
        }
      }
      terminalProgressReceipt = Objects.requireNonNull(
          terminalProgressReceipt, "terminalProgressReceipt");
      if (terminalProgressReceipt.roomType()
              != com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.REVIEW
          || terminalProgressReceipt.fencingToken() < 1) {
        throw new IllegalArgumentException("target Outcome terminal progress receipt is invalid");
      }
    }
  }
}

package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionRequest;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionResult;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeReviewDecisionAcceptance;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/**
 * CONTROL activities that invoke Outcome Updates from a Temporal client boundary.
 *
 * <p>Temporal does not support invoking an Update directly from one workflow into another
 * workflow. These activities keep the parent deterministic while binding every client Update to
 * the exact child workflow id and run id that the parent provisioned.
 */
@ActivityInterface
public interface TargetReviewOutcomeChildUpdateActivities {

  @ActivityMethod(name = "AcceptTargetReviewDecisionOnOutcomeChild")
  OutcomeReviewDecisionAcceptance acceptDecision(AcceptRequest request);

  @ActivityMethod(name = "CompleteTargetOutcomeAfterReviewRouting")
  OutcomeCompletionResult completeAfterRouting(CompleteRequest request);

  record AcceptRequest(
      String outcomeWorkflowId,
      String outcomeRunId,
      OutcomeReviewDecisionReceipt decisionReceipt) {
    public AcceptRequest {
      outcomeWorkflowId = requireText(outcomeWorkflowId, "outcomeWorkflowId");
      outcomeRunId = requireText(outcomeRunId, "outcomeRunId");
      decisionReceipt = Objects.requireNonNull(decisionReceipt, "decisionReceipt");
    }
  }

  record CompleteRequest(
      String outcomeWorkflowId,
      String outcomeRunId,
      OutcomeCompletionRequest completionRequest) {
    public CompleteRequest {
      outcomeWorkflowId = requireText(outcomeWorkflowId, "outcomeWorkflowId");
      outcomeRunId = requireText(outcomeRunId, "outcomeRunId");
      completionRequest = Objects.requireNonNull(completionRequest, "completionRequest");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}

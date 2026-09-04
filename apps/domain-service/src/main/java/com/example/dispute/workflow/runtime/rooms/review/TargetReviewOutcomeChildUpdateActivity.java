package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionResult;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeReviewDecisionAcceptance;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeRoomWorkflow;
import io.temporal.client.WorkflowClient;
import java.util.Objects;
import java.util.Optional;

/** Executes child Outcome Updates from the supported client-to-workflow boundary. */
public final class TargetReviewOutcomeChildUpdateActivity
    implements TargetReviewOutcomeChildUpdateActivities {

  private final WorkflowClient workflowClient;

  public TargetReviewOutcomeChildUpdateActivity(WorkflowClient workflowClient) {
    this.workflowClient = Objects.requireNonNull(workflowClient, "workflowClient");
  }

  @Override
  public OutcomeReviewDecisionAcceptance acceptDecision(AcceptRequest request) {
    AcceptRequest value = Objects.requireNonNull(request, "request");
    return Objects.requireNonNull(
        exactOutcomeChild(value.outcomeWorkflowId(), value.outcomeRunId())
            .reviewDecisionAccepted(value.decisionReceipt()),
        "Outcome decision acceptance");
  }

  @Override
  public OutcomeCompletionResult completeAfterRouting(CompleteRequest request) {
    CompleteRequest value = Objects.requireNonNull(request, "request");
    return Objects.requireNonNull(
        exactOutcomeChild(value.outcomeWorkflowId(), value.outcomeRunId())
            .completeTargetOutcomeAfterRouting(value.completionRequest()),
        "Outcome completion result");
  }

  private OutcomeRoomWorkflow exactOutcomeChild(String workflowId, String runId) {
    return workflowClient.newWorkflowStub(
        OutcomeRoomWorkflow.class, workflowId, Optional.of(runId));
  }
}

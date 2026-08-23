package com.example.dispute.workflow.targete2e.rooms.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionRequest;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeCompletionResult;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeReviewDecisionAcceptance;
import com.example.dispute.workflow.temporal.room.outcome.OutcomeRoomWorkflow;
import io.temporal.client.WorkflowClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TargetReviewOutcomeChildUpdateActivityTest {

  @Test
  void invokesBothUpdatesAgainstTheExactOutcomeRun() {
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    OutcomeRoomWorkflow outcome = mock(OutcomeRoomWorkflow.class);
    OutcomeReviewDecisionReceipt receipt = mock(OutcomeReviewDecisionReceipt.class);
    OutcomeReviewDecisionAcceptance acceptance =
        new OutcomeReviewDecisionAcceptance("RECEIPT_1", "a".repeat(64), 3, 4, true);
    OutcomeCompletionRequest completionRequest =
        new OutcomeCompletionRequest(
            RoomType.REVIEW, 0, 7, 30, 4, "RECEIPT_1", "a".repeat(64), 4);
    OutcomeCompletionResult completionResult = mock(OutcomeCompletionResult.class);
    when(
            workflowClient.newWorkflowStub(
                eq(OutcomeRoomWorkflow.class),
                eq("room-workflow:CASE_1:REVIEW:0"),
                eq(Optional.of("RUN_1"))))
        .thenReturn(outcome);
    when(outcome.reviewDecisionAccepted(receipt)).thenReturn(acceptance);
    when(outcome.completeTargetOutcomeAfterRouting(completionRequest)).thenReturn(completionResult);
    TargetReviewOutcomeChildUpdateActivity activity =
        new TargetReviewOutcomeChildUpdateActivity(workflowClient);

    assertThat(
            activity.acceptDecision(
                new TargetReviewOutcomeChildUpdateActivities.AcceptRequest(
                    "room-workflow:CASE_1:REVIEW:0", "RUN_1", receipt)))
        .isSameAs(acceptance);
    assertThat(
            activity.completeAfterRouting(
                new TargetReviewOutcomeChildUpdateActivities.CompleteRequest(
                    "room-workflow:CASE_1:REVIEW:0", "RUN_1", completionRequest)))
        .isSameAs(completionResult);

    verify(outcome).reviewDecisionAccepted(receipt);
    verify(outcome).completeTargetOutcomeAfterRouting(completionRequest);
  }

  @Test
  void rejectsMissingRunIdentityBeforeCallingTemporal() {
    OutcomeReviewDecisionReceipt receipt = mock(OutcomeReviewDecisionReceipt.class);

    assertThatThrownBy(
            () ->
                new TargetReviewOutcomeChildUpdateActivities.AcceptRequest(
                    "room-workflow:CASE_1:REVIEW:0", " ", receipt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outcomeRunId");
  }
}

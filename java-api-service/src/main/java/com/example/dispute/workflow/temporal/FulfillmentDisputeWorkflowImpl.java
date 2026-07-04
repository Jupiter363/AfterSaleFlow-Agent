package com.example.dispute.workflow.temporal;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import com.example.dispute.workflow.domain.FulfillmentDisputeCommand;
import com.example.dispute.workflow.domain.FulfillmentDisputeResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import com.example.dispute.workflow.domain.ReviewGateSnapshot;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Deterministic owner of final dispute control flow.
 *
 * <p>This workflow chooses routes, creates durable child workflows and forwards
 * signals. It never performs model inference, network calls or database access;
 * all open-ended cognition and side effects remain inside Activities.
 */
public class FulfillmentDisputeWorkflowImpl
        implements FulfillmentDisputeWorkflow {

    private final FulfillmentDisputeActivities activities =
            Workflow.newActivityStub(
                    FulfillmentDisputeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    private final Deque<EvidenceSubmissionSignal> pendingEvidence =
            new ArrayDeque<>();
    private final Deque<HumanReviewSignal> pendingReview = new ArrayDeque<>();
    private DisputeHearingWorkflow hearingWorkflow;
    private HumanReviewWorkflow reviewWorkflow;

    @Override
    public FulfillmentDisputeResult run(FulfillmentDisputeCommand command) {
        if (command.routeType() == RouteType.TRANSFERRED) {
            activities.markTransferred(command.caseId(), command.workflowId());
            return result(
                    command,
                    "TRANSFERRED",
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        String draftId = null;
        String deliberationId = null;
        boolean manualRequired = false;
        if (command.routeType() == RouteType.FULL_HEARING) {
            hearingWorkflow =
                    Workflow.newChildWorkflowStub(
                            DisputeHearingWorkflow.class,
                            childOptions(command.workflowId() + "-hearing"));
            flushEvidence();
            HearingWorkflowResult hearing =
                    hearingWorkflow.run(
                            new HearingWorkflowCommand(
                                    command.caseId(),
                                    command.workflowId(),
                                    command.dossierVersion(),
                                    command.evidenceWaitTimeout(),
                                    command.maxEvidenceRounds(),
                                    Duration.ofHours(3),
                                    3));
            draftId = hearing.draftId();
            manualRequired = hearing.manualRequired();
            if (command.deliberationRequired()) {
                DeliberationPanelWorkflow panel =
                        Workflow.newChildWorkflowStub(
                                DeliberationPanelWorkflow.class,
                                childOptions(command.workflowId() + "-panel"));
                DeliberationPanelResult deliberation =
                        panel.run(
                                new DeliberationPanelCommand(
                                        command.caseId(),
                                        command.workflowId(),
                                        draftId,
                                        hearing.dossierVersion(),
                                        List.of(
                                                "EVIDENCE_CRITIC",
                                                "RULE_CRITIC",
                                                "RISK_CRITIC",
                                                "REMEDY_CRITIC",
                                                "FAIRNESS_CRITIC"),
                                        List.of(
                                                "RISK_LEVEL_"
                                                        + command.riskLevel(),
                                                "DELIBERATION_MODE_"
                                                        + command
                                                                .deliberationMode()
                                                                .name()),
                                        command.deliberationScoreThreshold(),
                                        command.deliberationMaxRegenerations()));
                deliberationId = deliberation.deliberationId();
                manualRequired =
                        manualRequired || deliberation.manualRequired();
            }
        }

        String remedyPlanId =
                activities.planRemedy(
                        command.caseId(),
                        command.workflowId(),
                        draftId,
                        deliberationId);
        ReviewGateSnapshot reviewGate =
                activities.createReviewPacket(
                        command.caseId(),
                        draftId,
                        deliberationId,
                        remedyPlanId);
        reviewWorkflow =
                Workflow.newChildWorkflowStub(
                        HumanReviewWorkflow.class,
                        childOptions(command.workflowId() + "-review"));
        flushReview();
        HumanReviewResult review =
                reviewWorkflow.run(
                        new HumanReviewCommand(
                                command.caseId(),
                                reviewGate.reviewPacketId(),
                                reviewGate.reviewPacketVersion(),
                                reviewGate.actionHash(),
                                reviewGate.expiresAtEpochMillis(),
                                command.reviewWaitTimeout(),
                                reviewGate.requiredRole()));
        if (!review.approved()) {
            return result(
                    command,
                    "HUMAN_HANDOFF",
                    true,
                    true,
                    draftId,
                    deliberationId,
                    remedyPlanId,
                    review.reviewId(),
                    null);
        }

        ExecutionWorkflow executionWorkflow =
                Workflow.newChildWorkflowStub(
                        ExecutionWorkflow.class,
                        childOptions(command.workflowId() + "-execution"));
        ExecutionResult execution =
                executionWorkflow.run(
                        new ExecutionCommand(
                                command.caseId(),
                                review.reviewId(),
                                reviewGate.reviewPacketVersion(),
                                reviewGate.actionHash(),
                                true,
                                reviewGate.expiresAtEpochMillis(),
                                List.of(
                                        new com.example.dispute.workflow.domain
                                                .ExecutionAction(
                                                "ACTION_APPROVED_PLAN",
                                                "APPROVED_PLAN",
                                                "EXECUTE_"
                                                        + command.caseId()
                                                        + "_"
                                                        + review.reviewId(),
                                                List.of()))));
        if (!"SUCCEEDED".equals(execution.status())) {
            return result(
                    command,
                    "HUMAN_HANDOFF",
                    true,
                    true,
                    draftId,
                    deliberationId,
                    remedyPlanId,
                    review.reviewId(),
                    execution.status());
        }
        activities.closeCaseAndEvaluate(command.caseId());
        return result(
                command,
                "EVALUATION_COMPLETE",
                true,
                manualRequired,
                draftId,
                deliberationId,
                remedyPlanId,
                review.reviewId(),
                execution.status());
    }

    private ChildWorkflowOptions childOptions(String workflowId) {
        return ChildWorkflowOptions.newBuilder().setWorkflowId(workflowId).build();
    }

    private FulfillmentDisputeResult result(
            FulfillmentDisputeCommand command,
            String nextStage,
            boolean humanReviewRequired,
            boolean manualRequired,
            String draftId,
            String deliberationId,
            String remedyPlanId,
            String reviewId,
            String executionStatus) {
        return new FulfillmentDisputeResult(
                command.caseId(),
                command.workflowId(),
                "COMPLETED",
                nextStage,
                humanReviewRequired,
                manualRequired,
                draftId,
                deliberationId,
                remedyPlanId,
                reviewId,
                executionStatus);
    }

    @Override
    public void submitEvidence(EvidenceSubmissionSignal signal) {
        if (hearingWorkflow == null) {
            pendingEvidence.addLast(signal);
        } else {
            hearingWorkflow.submitEvidence(signal);
        }
    }

    @Override
    public void submitReviewDecision(HumanReviewSignal signal) {
        if (reviewWorkflow == null) {
            pendingReview.addLast(signal);
        } else {
            reviewWorkflow.submitDecision(signal);
        }
    }

    private void flushEvidence() {
        while (!pendingEvidence.isEmpty()) {
            hearingWorkflow.submitEvidence(pendingEvidence.removeFirst());
        }
    }

    private void flushReview() {
        while (!pendingReview.isEmpty()) {
            reviewWorkflow.submitDecision(pendingReview.removeFirst());
        }
    }
}

package com.example.dispute.workflow.temporal;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.CaseWorkflowResult;
import com.example.dispute.workflow.domain.CaseWorkflowSnapshot;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

public class CaseFulfillmentDisputeWorkflowImpl
        implements CaseFulfillmentDisputeWorkflow {

    private final CaseFulfillmentDisputeActivities activities =
            Workflow.newActivityStub(
                    CaseFulfillmentDisputeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setInitialInterval(Duration.ofSeconds(1))
                                            .setMaximumInterval(Duration.ofSeconds(15))
                                            .setBackoffCoefficient(2)
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    private final Deque<PartyEvidenceSignal> partySignals = new ArrayDeque<>();
    private final Deque<ReviewerWorkflowSignal> reviewerSignals =
            new ArrayDeque<>();
    private String status = "PENDING";
    private int roundNo;
    private boolean waitingForEvidence;
    private boolean evidenceTimedOut;
    private boolean manualRequired;

    @Override
    public CaseWorkflowResult run(CaseWorkflowInput input) {
        status = "RUNNING";
        if (input.routeType() != RouteType.FULL_HEARING) {
            String planId =
                    activities.planRemedy(input.caseId(), input.workflowId());
            return awaitHumanReview(input, null, planId);
        }

        activities.initializeHearing(input);
        boolean evidenceReceived = false;
        while (true) {
            HearingAnalysisActivityResult analysis;
            try {
                analysis =
                        activities.analyzeHearing(
                                new HearingAnalysisActivityCommand(
                                        input.caseId(),
                                        input.workflowId(),
                                        roundNo,
                                        evidenceTimedOut,
                                        evidenceReceived));
            } catch (ActivityFailure failure) {
                manualRequired = true;
                activities.completeHearing(
                        input.caseId(), input.workflowId(), true, evidenceTimedOut);
                String planId =
                        activities.planRemedy(input.caseId(), input.workflowId());
                return awaitHumanReview(input, null, planId);
            }
            manualRequired = manualRequired || analysis.manualRequired();
            if (!analysis.requiresAdditionalEvidence()
                    || evidenceTimedOut
                    || roundNo >= input.maxEvidenceRounds()) {
                if (roundNo >= input.maxEvidenceRounds()
                        && analysis.requiresAdditionalEvidence()) {
                    manualRequired = true;
                }
                activities.completeHearing(
                        input.caseId(),
                        input.workflowId(),
                        manualRequired,
                        evidenceTimedOut);
                String planId =
                        activities.planRemedy(input.caseId(), input.workflowId());
                return awaitHumanReview(input, analysis.draftId(), planId);
            }

            status = "WAITING_EVIDENCE";
            waitingForEvidence = true;
            boolean signaled =
                    Workflow.await(
                            input.evidenceWaitTimeout(),
                            () -> !partySignals.isEmpty() || !reviewerSignals.isEmpty());
            waitingForEvidence = false;
            if (!signaled) {
                evidenceTimedOut = true;
                manualRequired = true;
                status = "RUNNING";
                continue;
            }

            evidenceReceived = drainSignals();
            roundNo += 1;
            status = "RUNNING";
        }
    }

    private boolean drainSignals() {
        boolean evidenceReceived = false;
        while (!partySignals.isEmpty()) {
            PartyEvidenceSignal signal = partySignals.removeFirst();
            activities.recordPartyEvidence(signal);
            evidenceReceived = true;
        }
        Deque<ReviewerWorkflowSignal> reviewDecisions = new ArrayDeque<>();
        while (!reviewerSignals.isEmpty()) {
            ReviewerWorkflowSignal signal = reviewerSignals.removeFirst();
            if (!"ESCALATE_MANUAL".equals(signal.decision())
                    && !"CONTINUE_WITH_AVAILABLE_EVIDENCE".equals(
                            signal.decision())) {
                reviewDecisions.addLast(signal);
                continue;
            }
            activities.recordReviewerSignal(signal);
            if ("ESCALATE_MANUAL".equals(signal.decision())) {
                manualRequired = true;
                evidenceReceived = true;
            }
            if ("CONTINUE_WITH_AVAILABLE_EVIDENCE".equals(signal.decision())) {
                evidenceReceived = true;
            }
        }
        reviewerSignals.addAll(reviewDecisions);
        return evidenceReceived;
    }

    private CaseWorkflowResult awaitHumanReview(
            CaseWorkflowInput input, String draftId, String planId) {
        while (true) {
            String taskId = activities.createReviewTask(input.caseId(), planId);
            status = "WAITING_HUMAN_REVIEW";
            boolean reviewed =
                    Workflow.await(
                            Duration.ofDays(7),
                            () -> hasFinalReviewDecision());
            if (!reviewed) {
                manualRequired = true;
                status = "COMPLETED";
                return result(input, draftId, planId, taskId, "HUMAN_HANDOFF");
            }
            ReviewerWorkflowSignal decision = removeFinalReviewDecision();
            activities.recordReviewerSignal(decision);
            if ("REQUEST_MORE_EVIDENCE".equals(decision.decision())) {
                status = "WAITING_EVIDENCE";
                boolean supplied =
                        Workflow.await(
                                input.evidenceWaitTimeout(),
                                () -> !partySignals.isEmpty());
                if (supplied) {
                    drainSignals();
                } else {
                    evidenceTimedOut = true;
                    manualRequired = true;
                }
                status = "RUNNING";
                continue;
            }
            if ("APPROVE".equals(decision.decision())
                    || "MODIFY_AND_APPROVE".equals(decision.decision())) {
                status = "EXECUTING";
                activities.executeApprovedPlan(input.caseId());
                status = "CLOSING";
                activities.closeCaseAndEvaluate(input.caseId());
                status = "COMPLETED";
                return result(
                        input,
                        draftId,
                        planId,
                        taskId,
                        "EVALUATION_COMPLETE");
            }
            status = "COMPLETED";
            manualRequired = true;
            return result(input, draftId, planId, taskId, "HUMAN_HANDOFF");
        }
    }

    private boolean hasFinalReviewDecision() {
        return reviewerSignals.stream()
                .anyMatch(
                        signal ->
                                !"CONTINUE_WITH_AVAILABLE_EVIDENCE"
                                                .equals(signal.decision())
                                        && !"ESCALATE_MANUAL"
                                                .equals(signal.decision()));
    }

    private ReviewerWorkflowSignal removeFinalReviewDecision() {
        Deque<ReviewerWorkflowSignal> deferred = new ArrayDeque<>();
        ReviewerWorkflowSignal selected = null;
        while (!reviewerSignals.isEmpty()) {
            ReviewerWorkflowSignal signal = reviewerSignals.removeFirst();
            if (selected == null
                    && !"CONTINUE_WITH_AVAILABLE_EVIDENCE"
                            .equals(signal.decision())
                    && !"ESCALATE_MANUAL".equals(signal.decision())) {
                selected = signal;
            } else {
                deferred.addLast(signal);
            }
        }
        reviewerSignals.addAll(deferred);
        return selected;
    }

    private CaseWorkflowResult result(
            CaseWorkflowInput input,
            String draftId,
            String remedyPlanId,
            String reviewTaskId,
            String nextStage) {
        return new CaseWorkflowResult(
                input.caseId(),
                input.workflowId(),
                status,
                nextStage,
                manualRequired,
                evidenceTimedOut,
                draftId,
                remedyPlanId,
                reviewTaskId);
    }

    @Override
    public void submitPartyEvidence(PartyEvidenceSignal signal) {
        partySignals.addLast(signal);
    }

    @Override
    public void submitReviewerSignal(ReviewerWorkflowSignal signal) {
        reviewerSignals.addLast(signal);
    }

    @Override
    public CaseWorkflowSnapshot queryState() {
        return new CaseWorkflowSnapshot(
                status,
                roundNo,
                waitingForEvidence,
                evidenceTimedOut,
                manualRequired);
    }
}

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
        if (input.routeType() != RouteType.DISPUTE_HEARING) {
            status = "COMPLETED";
            return result(input, null);
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
                status = "COMPLETED";
                return result(input, null);
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
                status = "COMPLETED";
                return result(input, analysis.draftId());
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
        while (!reviewerSignals.isEmpty()) {
            ReviewerWorkflowSignal signal = reviewerSignals.removeFirst();
            activities.recordReviewerSignal(signal);
            if ("ESCALATE_MANUAL".equals(signal.decision())) {
                manualRequired = true;
                evidenceReceived = true;
            }
            if ("CONTINUE_WITH_AVAILABLE_EVIDENCE".equals(signal.decision())) {
                evidenceReceived = true;
            }
        }
        return evidenceReceived;
    }

    private CaseWorkflowResult result(CaseWorkflowInput input, String draftId) {
        return new CaseWorkflowResult(
                input.caseId(),
                input.workflowId(),
                status,
                "REMEDY_PLANNER",
                manualRequired,
                evidenceTimedOut,
                draftId);
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

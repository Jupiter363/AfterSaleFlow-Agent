package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CaseFulfillmentDisputeActivities {

    @ActivityMethod
    void initializeHearing(CaseWorkflowInput input);

    @ActivityMethod
    HearingAnalysisActivityResult analyzeHearing(
            HearingAnalysisActivityCommand command);

    @ActivityMethod
    void recordPartyEvidence(PartyEvidenceSignal signal);

    @ActivityMethod
    void recordReviewerSignal(ReviewerWorkflowSignal signal);

    @ActivityMethod
    void completeHearing(
            String caseId,
            String workflowId,
            boolean manualRequired,
            boolean evidenceTimedOut);

    @ActivityMethod
    String planRemedy(String caseId, String workflowId);

    @ActivityMethod
    String createReviewTask(String caseId, String remedyPlanId);

    @ActivityMethod
    void executeApprovedPlan(String caseId);

    @ActivityMethod
    void closeCaseAndEvaluate(String caseId);
}

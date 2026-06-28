package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.CaseWorkflowResult;
import com.example.dispute.workflow.domain.CaseWorkflowSnapshot;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CaseFulfillmentDisputeWorkflow {

    @WorkflowMethod
    CaseWorkflowResult run(CaseWorkflowInput input);

    @SignalMethod
    void submitPartyEvidence(PartyEvidenceSignal signal);

    @SignalMethod
    void submitReviewerSignal(ReviewerWorkflowSignal signal);

    @QueryMethod
    CaseWorkflowSnapshot queryState();
}

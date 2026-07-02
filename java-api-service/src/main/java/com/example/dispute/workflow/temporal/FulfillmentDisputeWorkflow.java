package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.FulfillmentDisputeCommand;
import com.example.dispute.workflow.domain.FulfillmentDisputeResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface FulfillmentDisputeWorkflow {

    @WorkflowMethod
    FulfillmentDisputeResult run(FulfillmentDisputeCommand command);

    @SignalMethod
    void submitEvidence(EvidenceSubmissionSignal signal);

    @SignalMethod
    void submitReviewDecision(HumanReviewSignal signal);
}

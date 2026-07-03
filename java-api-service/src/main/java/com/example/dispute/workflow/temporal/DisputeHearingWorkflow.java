package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface DisputeHearingWorkflow {

    @WorkflowMethod
    HearingWorkflowResult run(HearingWorkflowCommand command);

    @SignalMethod
    void submitEvidence(EvidenceSubmissionSignal signal);

    @SignalMethod
    void hearingRoundCompleted(int roundNo, boolean factsSufficient);

    @SignalMethod
    void settlementConfirmed(int settlementVersion);
}

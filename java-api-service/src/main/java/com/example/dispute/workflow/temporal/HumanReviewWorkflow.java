package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface HumanReviewWorkflow {

    @WorkflowMethod
    HumanReviewResult run(HumanReviewCommand command);

    @SignalMethod
    void submitDecision(HumanReviewSignal signal);
}

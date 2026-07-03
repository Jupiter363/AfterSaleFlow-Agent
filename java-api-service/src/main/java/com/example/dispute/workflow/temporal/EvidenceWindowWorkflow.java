package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.domain.EvidenceWindowResult;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface EvidenceWindowWorkflow {

    @WorkflowMethod
    EvidenceWindowResult run(EvidenceWindowCommand command);

    @SignalMethod
    void partyCompleted(String role);
}

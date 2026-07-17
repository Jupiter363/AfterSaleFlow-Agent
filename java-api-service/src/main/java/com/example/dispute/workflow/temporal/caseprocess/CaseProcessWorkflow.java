package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CaseProcessWorkflow {

    @WorkflowMethod(name = CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE)
    void run(CaseProcessCarryState carryState);

    @UpdateMethod(name = CaseProcessWorkflowProtocol.ACCEPT_COMMAND_UPDATE)
    void acceptCommand(CaseCommandRef command);

    @UpdateValidatorMethod(updateName = CaseProcessWorkflowProtocol.ACCEPT_COMMAND_UPDATE)
    void validateAcceptCommand(CaseCommandRef command);

    @SignalMethod(name = CaseProcessWorkflowProtocol.DOMAIN_EVENT_SIGNAL)
    void domainEventCommitted(CaseDomainEventRef event);

    @SignalMethod(name = CaseProcessWorkflowProtocol.RETRY_SEQUENCE_GAP_SIGNAL)
    void retrySequenceGap();

    @SignalMethod(name = CaseProcessWorkflowProtocol.REQUEST_CONTINUE_AS_NEW_SIGNAL)
    void requestContinueAsNew();

    @QueryMethod(name = CaseProcessWorkflowProtocol.PROCESS_STATE_QUERY)
    CaseProcessSnapshot state();
}

package com.example.dispute.workflow.temporal.room.common;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RoomControlWorkflow {

    @WorkflowMethod(name = CaseProcessWorkflowProtocol.ROOM_WORKFLOW_TYPE)
    void run(RoomControlStart start);

    @SignalMethod(name = CaseProcessWorkflowProtocol.ROOM_COMMAND_SIGNAL)
    void commandAccepted(CaseCommandRef command);

    @SignalMethod(name = CaseProcessWorkflowProtocol.ROOM_EVENT_SIGNAL)
    void domainEventCommitted(CaseDomainEventRef event);

    @SignalMethod(name = CaseProcessWorkflowProtocol.ROOM_CLOSE_SIGNAL)
    void close(String reasonCode);

    @QueryMethod(name = CaseProcessWorkflowProtocol.ROOM_STATE_QUERY)
    RoomControlSnapshot state();
}

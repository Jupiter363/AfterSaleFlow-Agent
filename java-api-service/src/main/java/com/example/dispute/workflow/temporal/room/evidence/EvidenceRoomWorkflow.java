package com.example.dispute.workflow.temporal.room.evidence;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface EvidenceRoomWorkflow {

  @WorkflowMethod(name = "EvidenceRoomWorkflow")
  EvidenceRoomSnapshot run(EvidenceRoomStart start);

  @SignalMethod(name = "evidencePartyCompleted")
  void partyCompleted(EvidenceRoomSignal signal);

  @QueryMethod(name = "evidenceState")
  EvidenceRoomSnapshot state();
}

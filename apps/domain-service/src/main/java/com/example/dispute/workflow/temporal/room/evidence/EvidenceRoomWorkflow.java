package com.example.dispute.workflow.temporal.room.evidence;

import com.example.dispute.workflow.runtime.temporal.room.TargetRoomAgentRunFinalizationReceipt;
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

  /** Records a completed target AgentRun after its formal finalizer has returned successfully. */
  @SignalMethod(name = "evidenceAgentRunFinalized")
  void agentRunFinalized(TargetRoomAgentRunFinalizationReceipt receipt);

  @QueryMethod(name = "evidenceState")
  EvidenceRoomSnapshot state();
}

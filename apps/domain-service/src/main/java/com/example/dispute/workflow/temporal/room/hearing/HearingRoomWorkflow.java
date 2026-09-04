package com.example.dispute.workflow.temporal.room.hearing;

import com.example.dispute.workflow.runtime.temporal.room.TargetRoomAgentRunFinalizationReceipt;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface HearingRoomWorkflow {

  @WorkflowMethod(name = "HearingRoomWorkflow")
  HearingRoomSnapshot run(HearingRoomStart start);

  @SignalMethod(name = "hearingStageCompleted")
  void stageCompleted(HearingStageReceipt receipt);

  @SignalMethod(name = "hearingPartyTerminal")
  void partyTerminal(HearingPartyTerminalReceipt receipt);

  @SignalMethod(name = "hearingPartyCommandAccepted")
  void partyCommandAccepted(HearingPartyCommand command);

  /** Records a completed target AgentRun after its formal finalizer has returned successfully. */
  @SignalMethod(name = "hearingAgentRunFinalized")
  void agentRunFinalized(TargetRoomAgentRunFinalizationReceipt receipt);

  @QueryMethod(name = "hearingState")
  HearingRoomSnapshot state();
}

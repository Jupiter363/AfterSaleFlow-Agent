package com.example.dispute.workflow.temporal.room.intake;

/** Framework-free read port implemented by the target-only Java assembly. */
public interface IntakeAgentRunFinalizationReceiptReadPort {

  IntakeAgentRunFinalizationReadResult read(IntakeAgentRunFinalizationReadRequest request);
}

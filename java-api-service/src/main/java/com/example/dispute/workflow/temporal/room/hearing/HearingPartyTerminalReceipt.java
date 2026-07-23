package com.example.dispute.workflow.temporal.room.hearing;

import java.util.Objects;

/** Java-committed terminal party action delivered to the Workflow as a Signal. */
public record HearingPartyTerminalReceipt(
    String schemaVersion,
    String requestId,
    String participantId,
    HearingWorkflowStage stage,
    int stageSequence,
    TerminalStatus terminalStatus,
    String operationKey,
    String requestHash,
    long processRevision,
    long roomRevision,
    long committedEventSequence) {

  public HearingPartyTerminalReceipt {
    if (!"hearing-party-terminal-receipt.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be hearing-party-terminal-receipt.v1");
    }
    HearingStageReceipt.requireText(requestId, "requestId");
    HearingStageReceipt.requireText(participantId, "participantId");
    Objects.requireNonNull(stage, "stage must not be null");
    if (!stage.isPartyWait() || stageSequence != stage.sequence()) {
      throw new IllegalArgumentException("party receipt must target a party-wait stage");
    }
    Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
    HearingStageReceipt.requireText(operationKey, "operationKey");
    HearingStageReceipt.requireHash(requestHash, "requestHash");
    HearingStageReceipt.requirePositive(processRevision, "processRevision");
    HearingStageReceipt.requirePositive(roomRevision, "roomRevision");
    HearingStageReceipt.requirePositive(committedEventSequence, "committedEventSequence");
  }

  public enum TerminalStatus {
    SUBMITTED,
    AUTO_TIMEOUT,
    ABSENT
  }
}

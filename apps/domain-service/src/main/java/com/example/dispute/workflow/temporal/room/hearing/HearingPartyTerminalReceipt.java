package com.example.dispute.workflow.temporal.room.hearing;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import java.util.Objects;

/** Java-committed party terminal action delivered to the Workflow as a Signal. */
public record HearingPartyTerminalReceipt(
    String schemaVersion,
    String requestId,
    String participantId,
    TerminalStatus terminalStatus,
    HearingCommittedReceipt committed) {

  public static final String SCHEMA_VERSION = "hearing-party-terminal-receipt.v1";

  public HearingPartyTerminalReceipt {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
    }
    HearingStageReceipt.requireText(requestId, "requestId");
    HearingStageReceipt.requireText(participantId, "participantId");
    Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
    Objects.requireNonNull(committed, "committed must not be null");
    if (committed.operationType() != HearingAuthorityCommit.OperationType.PARTY_TERMINAL
        || !committed.sourceStage().isPartyWait()) {
      throw new IllegalArgumentException("party Signal requires a party-terminal authority receipt");
    }
    String expected = HearingOperationKeys.partyTerminal(
        committed.tenantSurrogate(),
        committed.caseId(),
        committed.roomEpoch(),
        committed.sourceStage(),
        committed.sourceStageSequence(),
        participantId,
        requestId);
    if (!expected.equals(committed.operationKey())) {
      throw new IllegalArgumentException("party receipt operationKey does not bind its participant");
    }
  }

  public enum TerminalStatus {
    SUBMITTED,
    AUTO_TIMEOUT
  }
}

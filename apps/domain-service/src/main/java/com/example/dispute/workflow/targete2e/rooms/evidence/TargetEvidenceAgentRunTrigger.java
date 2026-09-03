package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;

/** Replay-stable result of the CONTROL Evidence bridge, ready for the room signal. */
public record TargetEvidenceAgentRunTrigger(
    String schemaVersion,
    String commandId,
    long roomEpoch,
    long roomFencingToken,
    long expectedProcessRevision,
    long expectedRoomRevision,
    String commandHash,
    String commandEnvelopeHash,
    ExecuteAgentRunRequest request) {
  public TargetEvidenceAgentRunTrigger {
    if (!"target-e2e-evidence-agent-run-trigger.v1".equals(schemaVersion)
        || commandId == null || commandId.isBlank() || roomEpoch < 0 || roomFencingToken < 1
        || expectedProcessRevision < 0 || expectedRoomRevision < 0
        || commandHash == null || !commandHash.matches("[0-9a-f]{64}")
        || commandEnvelopeHash == null || !commandEnvelopeHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("target Evidence trigger is invalid");
    }
    request = Objects.requireNonNull(request, "request");
    if (!commandId.equals(request.command().commandId()) || roomEpoch != request.command().roomEpoch()) {
      throw new IllegalArgumentException("target Evidence trigger does not bind its request");
    }
  }
}

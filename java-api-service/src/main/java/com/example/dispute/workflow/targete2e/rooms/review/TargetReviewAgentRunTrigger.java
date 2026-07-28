package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;

/** Replay-stable CONTROL result for an advisory Review AgentRun. */
public record TargetReviewAgentRunTrigger(
    String schemaVersion, String activationId, String activationManifestHash, String commandId, long roomEpoch, long roomFencingToken,
    long expectedProcessRevision, long expectedRoomRevision, String commandHash,
    String commandEnvelopeHash, ExecuteAgentRunRequest request) {
  public static final String SCHEMA_VERSION = "target-e2e-review-agent-run-trigger.v1";
  public TargetReviewAgentRunTrigger {
    if (!SCHEMA_VERSION.equals(schemaVersion) || activationId == null || activationId.isBlank()
        || activationManifestHash == null || !activationManifestHash.matches("[0-9a-f]{64}")
        || commandId == null || commandId.isBlank()
        || roomEpoch < 0 || roomFencingToken < 1 || expectedProcessRevision < 0
        || expectedRoomRevision < 0 || commandHash == null || !commandHash.matches("[0-9a-f]{64}")
        || commandEnvelopeHash == null || !commandEnvelopeHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("target Review trigger is invalid");
    }
    request = Objects.requireNonNull(request, "request");
    if (!commandId.equals(request.command().commandId()) || roomEpoch != request.command().roomEpoch()
        || !"REVIEW".equals(request.command().roomType().name())) {
      throw new IllegalArgumentException("target Review trigger does not bind its request");
    }
  }
}

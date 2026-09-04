package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.util.Objects;

/** Immutable admitted input for a target-lane Review advisory run. */
public record TargetReviewCommandMaterial(
    String schemaVersion,
    String executionLane,
    String activationId,
    String activationManifestHash,
    long roomFencingToken,
    long expectedProcessRevision,
    long expectedRoomRevision,
    String commandHash,
    String commandEnvelopeHash,
    ExecuteAgentRunRequest request) {
  public static final String SCHEMA_VERSION = "production-runtime-review-command-material.v1";
  public static final String TARGET_LANE = "PRODUCTION";

  public TargetReviewCommandMaterial {
    require(SCHEMA_VERSION.equals(schemaVersion), "schemaVersion");
    require(TARGET_LANE.equals(executionLane), "executionLane");
    requireText(activationId, "activationId");
    requireHash(activationManifestHash, "activationManifestHash");
    requireHash(commandHash, "commandHash");
    requireHash(commandEnvelopeHash, "commandEnvelopeHash");
    if (roomFencingToken < 1 || expectedProcessRevision < 0 || expectedRoomRevision < 0) {
      throw new IllegalArgumentException("target Review fence or revision is invalid");
    }
    request = Objects.requireNonNull(request, "request");
    var command = request.command();
    require(command.roomType().name().equals("REVIEW"), "request.command.roomType");
    require(request.agentRunId().equals(command.logicalRunId()), "request.agentRunId");
  }

  private static void require(boolean value, String field) {
    if (!value) throw new IllegalArgumentException("target Review material has invalid " + field);
  }
  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
  }
  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}

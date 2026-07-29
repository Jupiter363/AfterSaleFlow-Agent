package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** Proposal-only result wrapper bound to one target-E2E command envelope. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TargetE2EGraphResultEnvelope(
    String schemaVersion,
    String executionLane,
    String activationId,
    long roomFencingToken,
    String commandHash,
    String commandEnvelopeHash,
    String executionProvider,
    String executionModel,
    String resultHash,
    String proposalHash,
    String resultEnvelopeHash,
    String graphOutputAuthority,
    RoomGraphResult result) {

  public static final String SCHEMA_VERSION = "target-e2e-graph-result-envelope.v1";
  public static final String GRAPH_OUTPUT_AUTHORITY = "PROPOSAL_ONLY";
  public static final int EXECUTION_PROVIDER_MAX_LENGTH = 64;
  public static final int EXECUTION_MODEL_MAX_LENGTH = 128;

  public TargetE2EGraphResultEnvelope {
    TargetE2EGraphCommandEnvelope.requireConstant(schemaVersion, SCHEMA_VERSION, "schemaVersion");
    TargetE2EGraphCommandEnvelope.requireConstant(
        executionLane, TargetE2EGraphCommandEnvelope.EXECUTION_LANE, "executionLane");
    TargetE2EGraphCommandEnvelope.requirePattern(
        activationId, TargetE2EGraphCommandEnvelope.ACTIVATION_ID, "activationId");
    if (roomFencingToken < 1 || roomFencingToken > 9_007_199_254_740_991L) {
      throw new IllegalArgumentException("roomFencingToken is outside the JSON-safe range");
    }
    TargetE2EGraphCommandEnvelope.requirePattern(
        commandHash, TargetE2EGraphCommandEnvelope.SHA256, "commandHash");
    TargetE2EGraphCommandEnvelope.requirePattern(
        commandEnvelopeHash, TargetE2EGraphCommandEnvelope.SHA256, "commandEnvelopeHash");
    requireBoundedNonBlank(
        executionProvider, EXECUTION_PROVIDER_MAX_LENGTH, "executionProvider");
    requireBoundedNonBlank(executionModel, EXECUTION_MODEL_MAX_LENGTH, "executionModel");
    TargetE2EGraphCommandEnvelope.requirePattern(
        resultHash, TargetE2EGraphCommandEnvelope.SHA256, "resultHash");
    TargetE2EGraphCommandEnvelope.requirePattern(
        proposalHash, TargetE2EGraphCommandEnvelope.SHA256, "proposalHash");
    TargetE2EGraphCommandEnvelope.requirePattern(
        resultEnvelopeHash, TargetE2EGraphCommandEnvelope.SHA256, "resultEnvelopeHash");
    TargetE2EGraphCommandEnvelope.requireConstant(
        graphOutputAuthority, GRAPH_OUTPUT_AUTHORITY, "graphOutputAuthority");
    Objects.requireNonNull(result, "result");
  }

  static void requireBoundedNonBlank(String value, int maximumLength, String field) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(field + " must be nonblank and at most " + maximumLength);
    }
  }
}

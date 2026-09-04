package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** Proposal-only result wrapper bound to one production-runtime command envelope. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionGraphResultEnvelope(
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

  public static final String SCHEMA_VERSION = "production-runtime-graph-result-envelope.v1";
  public static final String GRAPH_OUTPUT_AUTHORITY = "PROPOSAL_ONLY";
  public static final int EXECUTION_PROVIDER_MAX_LENGTH = 64;
  public static final int EXECUTION_MODEL_MAX_LENGTH = 128;

  public ProductionGraphResultEnvelope {
    ProductionGraphCommandEnvelope.requireConstant(schemaVersion, SCHEMA_VERSION, "schemaVersion");
    ProductionGraphCommandEnvelope.requireConstant(
        executionLane, ProductionGraphCommandEnvelope.EXECUTION_LANE, "executionLane");
    ProductionGraphCommandEnvelope.requirePattern(
        activationId, ProductionGraphCommandEnvelope.ACTIVATION_ID, "activationId");
    if (roomFencingToken < 1 || roomFencingToken > 9_007_199_254_740_991L) {
      throw new IllegalArgumentException("roomFencingToken is outside the JSON-safe range");
    }
    ProductionGraphCommandEnvelope.requirePattern(
        commandHash, ProductionGraphCommandEnvelope.SHA256, "commandHash");
    ProductionGraphCommandEnvelope.requirePattern(
        commandEnvelopeHash, ProductionGraphCommandEnvelope.SHA256, "commandEnvelopeHash");
    requireBoundedNonBlank(
        executionProvider, EXECUTION_PROVIDER_MAX_LENGTH, "executionProvider");
    requireBoundedNonBlank(executionModel, EXECUTION_MODEL_MAX_LENGTH, "executionModel");
    ProductionGraphCommandEnvelope.requirePattern(
        resultHash, ProductionGraphCommandEnvelope.SHA256, "resultHash");
    ProductionGraphCommandEnvelope.requirePattern(
        proposalHash, ProductionGraphCommandEnvelope.SHA256, "proposalHash");
    ProductionGraphCommandEnvelope.requirePattern(
        resultEnvelopeHash, ProductionGraphCommandEnvelope.SHA256, "resultEnvelopeHash");
    ProductionGraphCommandEnvelope.requireConstant(
        graphOutputAuthority, GRAPH_OUTPUT_AUTHORITY, "graphOutputAuthority");
    Objects.requireNonNull(result, "result");
  }

  static void requireBoundedNonBlank(String value, int maximumLength, String field) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(field + " must be nonblank and at most " + maximumLength);
    }
  }
}

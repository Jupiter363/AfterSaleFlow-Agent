package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;
import java.util.regex.Pattern;

/** Frozen additive wrapper around the unchanged room-graph-command.v1 contract. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionGraphCommandEnvelope(
    String schemaVersion,
    String executionLane,
    String activationId,
    long roomFencingToken,
    String commandHash,
    String commandEnvelopeHash,
    RoomGraphCommand command) {

  public static final String SCHEMA_VERSION = "production-runtime-graph-command-envelope.v1";
  public static final String EXECUTION_LANE = "PRODUCTION";
  static final Pattern ACTIVATION_ID = Pattern.compile("p9act\\.v1\\.[0-9a-f]{32}");
  static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public ProductionGraphCommandEnvelope {
    requireConstant(schemaVersion, SCHEMA_VERSION, "schemaVersion");
    requireConstant(executionLane, EXECUTION_LANE, "executionLane");
    requirePattern(activationId, ACTIVATION_ID, "activationId");
    if (roomFencingToken < 1 || roomFencingToken > 9_007_199_254_740_991L) {
      throw new IllegalArgumentException("roomFencingToken is outside the JSON-safe range");
    }
    requirePattern(commandHash, SHA256, "commandHash");
    requirePattern(commandEnvelopeHash, SHA256, "commandEnvelopeHash");
    Objects.requireNonNull(command, "command");
  }

  static void requireConstant(String actual, String expected, String field) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(field + " must be " + expected);
    }
  }

  static void requirePattern(String value, Pattern pattern, String field) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " has invalid format");
    }
  }
}

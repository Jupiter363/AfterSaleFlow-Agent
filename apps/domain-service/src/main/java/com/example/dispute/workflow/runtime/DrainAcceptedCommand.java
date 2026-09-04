package com.example.dispute.workflow.runtime;

import java.time.Instant;
import java.util.Objects;

/** Immutable identity of one command durably admitted before activation expiry. */
public record DrainAcceptedCommand(
    String commandId,
    String commandHash,
    String commandEnvelopeHash,
    long roomEpoch,
    long fencingToken,
    Instant admittedAt) {

  public DrainAcceptedCommand {
    ProductionActivationContract.identifier(commandId, "commandId");
    ProductionActivationContract.sha256(commandHash, "commandHash");
    ProductionActivationContract.sha256(commandEnvelopeHash, "commandEnvelopeHash");
    if (roomEpoch < 0 || fencingToken < 1) {
      throw new IllegalArgumentException("room epoch and fencing token must be positive");
    }
    Objects.requireNonNull(admittedAt, "admittedAt");
  }
}

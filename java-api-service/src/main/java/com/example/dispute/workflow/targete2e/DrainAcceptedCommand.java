package com.example.dispute.workflow.targete2e;

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
    TargetE2eActivationContract.identifier(commandId, "commandId");
    TargetE2eActivationContract.sha256(commandHash, "commandHash");
    TargetE2eActivationContract.sha256(commandEnvelopeHash, "commandEnvelopeHash");
    if (roomEpoch < 1 || fencingToken < 1) {
      throw new IllegalArgumentException("room epoch and fencing token must be positive");
    }
    Objects.requireNonNull(admittedAt, "admittedAt");
  }
}

package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireThreadId;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import java.util.Objects;

/** Durable proof that one private party thread published its initialization snapshot once. */
public record IntakeThreadInitialization(
    String schemaVersion,
    IntakeParty party,
    String actorScopeHash,
    String threadId,
    String agentSessionId,
    long domainRevision,
    SnapshotPublicationReceipt receipt) {

  public IntakeThreadInitialization {
    if (!"intake-thread-initialization.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-thread-initialization.v1");
    }
    Objects.requireNonNull(party, "party must not be null");
    requireHash(actorScopeHash, "actorScopeHash");
    requireThreadId(threadId, "threadId");
    requireIdentifier(agentSessionId, "agentSessionId");
    if (domainRevision < 0) {
      throw new IllegalArgumentException("domainRevision must not be negative");
    }
    Objects.requireNonNull(receipt, "receipt must not be null");
    if (receipt.domainRevision() != domainRevision) {
      throw new IllegalArgumentException("snapshot receipt domain revision does not match");
    }
  }
}

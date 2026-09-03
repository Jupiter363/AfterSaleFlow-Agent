package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireReference;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireThreadId;

public record IntakeGraphExecutionRef(
    String schemaVersion,
    String threadId,
    String graphCommandId,
    String graphKey,
    String graphVersion,
    String checkpointId,
    String resultRef,
    String resultHash,
    String proposalRef,
    String proposalHash) {

  public IntakeGraphExecutionRef {
    if (!"intake-graph-execution-ref.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-graph-execution-ref.v1");
    }
    requireThreadId(threadId, "threadId");
    requireIdentifier(graphCommandId, "graphCommandId");
    if (!"intake.v2".equals(graphKey)) {
      throw new IllegalArgumentException("graphKey must be intake.v2");
    }
    requireIdentifier(graphVersion, "graphVersion");
    requireIdentifier(checkpointId, "checkpointId");
    requireReference(resultRef, "resultRef");
    requireHash(resultHash, "resultHash");
    requireReference(proposalRef, "proposalRef");
    requireHash(proposalHash, "proposalHash");
  }
}

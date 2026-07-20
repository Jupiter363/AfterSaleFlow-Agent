package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireReference;

public record IntakeWorkflowCommand(
    String schemaVersion,
    String commandId,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    long sequence,
    IntakeCommandType commandType,
    IntakeParty party,
    String actorScopeHash,
    String payloadRef,
    String payloadHash,
    String operationKey,
    String requestHash) {

  public IntakeWorkflowCommand {
    if (!"intake-workflow-command.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-workflow-command.v1");
    }
    requireIdentifier(commandId, "commandId");
    requireIdentifier(tenantSurrogate, "tenantSurrogate");
    requireIdentifier(caseId, "caseId");
    requireReference(payloadRef, "payloadRef");
    requireIdentifier(operationKey, "operationKey");
    requireHash(actorScopeHash, "actorScopeHash");
    requireHash(payloadHash, "payloadHash");
    requireHash(requestHash, "requestHash");
    if (roomEpoch < 0 || fencingToken < 1 || sequence < 1) {
      throw new IllegalArgumentException("epoch, fence, and sequence must be valid");
    }
    if (commandType == null || party == null) {
      throw new IllegalArgumentException("commandType and party must not be null");
    }
  }

}

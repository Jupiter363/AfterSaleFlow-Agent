package com.example.dispute.workflow.temporal.room.intake;

import java.util.regex.Pattern;

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

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public IntakeWorkflowCommand {
    if (!"intake-workflow-command.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-workflow-command.v1");
    }
    requireIdentifier(commandId, "commandId");
    requireIdentifier(tenantSurrogate, "tenantSurrogate");
    requireIdentifier(caseId, "caseId");
    requireIdentifier(payloadRef, "payloadRef");
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

  private static void requireIdentifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a SHA-256 value");
    }
  }
}

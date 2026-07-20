package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;

import java.util.regex.Pattern;

public record IntakeRoomStart(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    long initialProcessRevision,
    long initialRoomRevision,
    long firstCommandSequence,
    long firstEventSequence,
    String workflowBuildId,
    String graphVersion,
    String checkpointSchemaVersion,
    String promptVersion,
    String modelProfileId,
    String outputSchemaVersion,
    String policyVersion,
    String guardrailVersion,
    String toolPolicyVersion,
    String initiatorActorScopeHash,
    String respondentActorScopeHash,
    IntakeRoomCarryState carryState) {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

  public IntakeRoomStart(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      long initialProcessRevision,
      long initialRoomRevision,
      long firstCommandSequence,
      long firstEventSequence,
      String workflowBuildId,
      String graphVersion,
      String checkpointSchemaVersion,
      String promptVersion,
      String modelProfileId,
      String outputSchemaVersion,
      String policyVersion,
      String guardrailVersion,
      String toolPolicyVersion,
      String initiatorActorScopeHash,
      String respondentActorScopeHash) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        roomEpoch,
        fencingToken,
        initialProcessRevision,
        initialRoomRevision,
        firstCommandSequence,
        firstEventSequence,
        workflowBuildId,
        graphVersion,
        checkpointSchemaVersion,
        promptVersion,
        modelProfileId,
        outputSchemaVersion,
        policyVersion,
        guardrailVersion,
        toolPolicyVersion,
        initiatorActorScopeHash,
        respondentActorScopeHash,
        null);
  }

  public IntakeRoomStart {
    if (!"intake-room-start.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-room-start.v1");
    }
    requireIdentifier(tenantSurrogate, "tenantSurrogate");
    requireIdentifier(caseId, "caseId");
    requireIdentifier(workflowBuildId, "workflowBuildId");
    requireIdentifier(graphVersion, "graphVersion");
    requireIdentifier(checkpointSchemaVersion, "checkpointSchemaVersion");
    requireIdentifier(promptVersion, "promptVersion");
    requireIdentifier(modelProfileId, "modelProfileId");
    requireIdentifier(outputSchemaVersion, "outputSchemaVersion");
    requireIdentifier(policyVersion, "policyVersion");
    requireIdentifier(guardrailVersion, "guardrailVersion");
    requireIdentifier(toolPolicyVersion, "toolPolicyVersion");
    requireHash(initiatorActorScopeHash, "initiatorActorScopeHash");
    requireHash(respondentActorScopeHash, "respondentActorScopeHash");
    if (initiatorActorScopeHash.equals(respondentActorScopeHash)) {
      throw new IllegalArgumentException("party actor scopes must be distinct");
    }
    if (roomEpoch < 0 || fencingToken < 1) {
      throw new IllegalArgumentException("roomEpoch and fencingToken must be valid");
    }
    if (initialProcessRevision < 0 || initialRoomRevision < 0) {
      throw new IllegalArgumentException("initial revisions must not be negative");
    }
    if (firstCommandSequence < 1 || firstEventSequence < 1) {
      throw new IllegalArgumentException("first sequences must be positive");
    }
  }

  public IntakeRoomStart withCarryState(IntakeRoomCarryState nextCarryState) {
    return new IntakeRoomStart(
        schemaVersion,
        tenantSurrogate,
        caseId,
        roomEpoch,
        fencingToken,
        initialProcessRevision,
        initialRoomRevision,
        firstCommandSequence,
        firstEventSequence,
        workflowBuildId,
        graphVersion,
        checkpointSchemaVersion,
        promptVersion,
        modelProfileId,
        outputSchemaVersion,
        policyVersion,
        guardrailVersion,
        toolPolicyVersion,
        initiatorActorScopeHash,
        respondentActorScopeHash,
        nextCarryState);
  }

  private static void requireIdentifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }
}

package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireReference;

public record IntakeDomainEventRef(
    String schemaVersion,
    String eventId,
    String eventRef,
    String eventHash,
    long eventSequence,
    IntakeDomainEventType eventType,
    IntakeParty party,
    String commandId,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    String actorScopeHash,
    String operationKey,
    String requestHash,
    String resultHash,
    long processRevision,
    long roomRevision,
    IntakeAgentRunRef agentRunRef,
    IntakeGraphExecutionRef graphExecutionRef) {

  public IntakeDomainEventRef {
    if (!"intake-domain-event-ref.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-domain-event-ref.v1");
    }
    requireIdentifier(eventId, "eventId");
    requireReference(eventRef, "eventRef");
    requireHash(eventHash, "eventHash");
    requireIdentifier(commandId, "commandId");
    requireIdentifier(tenantSurrogate, "tenantSurrogate");
    requireIdentifier(caseId, "caseId");
    requireHash(actorScopeHash, "actorScopeHash");
    IntakeOperationKeys.requireEventCorrelationKey(operationKey);
    requireHash(requestHash, "requestHash");
    requireHash(resultHash, "resultHash");
    if (eventSequence < 1 || roomEpoch < 0 || fencingToken < 1) {
      throw new IllegalArgumentException("event sequence, epoch, and fence must be valid");
    }
    if (processRevision < 0 || roomRevision < 0) {
      throw new IllegalArgumentException("revisions must not be negative");
    }
    if (eventType == null || party == null) {
      throw new IllegalArgumentException("eventType and party must not be null");
    }
    if ((agentRunRef == null) != (graphExecutionRef == null)) {
      throw new IllegalArgumentException("AgentRun and Graph references must be present together");
    }
    boolean turnEvent =
        eventType == IntakeDomainEventType.TURN_NEEDS_INPUT
            || eventType == IntakeDomainEventType.TURN_READY_TO_CONFIRM;
    if (turnEvent && agentRunRef == null) {
      throw new IllegalArgumentException("turn events require AgentRun and Graph references");
    }
    if (!turnEvent && agentRunRef != null) {
      throw new IllegalArgumentException(
          "non-turn events must not carry AgentRun or Graph references");
    }
    if (agentRunRef != null
        && (!resultHash.equals(agentRunRef.finalResultHash())
            || !resultHash.equals(graphExecutionRef.resultHash()))) {
      throw new IllegalArgumentException("AgentRun and Graph result hashes must match resultHash");
    }
  }
}

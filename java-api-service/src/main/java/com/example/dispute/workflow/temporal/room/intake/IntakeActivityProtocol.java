package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireReference;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireThreadId;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class IntakeActivityProtocol {

  private IntakeActivityProtocol() {}

  public enum BranchOperation {
    INITIATOR_ACCEPT,
    INITIATOR_REJECT,
    CANCEL,
    RESPONDENT_CONFIRM
  }

  public enum ReplayDisposition {
    REPLAY_COMMITTED,
    CONFLICT,
    DIFFERENT_OPERATION
  }

  public record RetryBudget(
      String schemaVersion,
      int providerAttemptsRemaining,
      int activityAttemptsRemaining,
      int repairsRemaining) {

    public RetryBudget {
      requireSchema(schemaVersion, "intake-retry-budget.v1");
      if (providerAttemptsRemaining < 0
          || activityAttemptsRemaining < 0
          || repairsRemaining < 0
          || providerAttemptsRemaining > 2
          || activityAttemptsRemaining > 3
          || repairsRemaining > 1) {
        throw new IllegalArgumentException(
            "retry budget must stay within RoomGraphCommand.v1 limits 2/3/1");
      }
    }

    public boolean doesNotIncreaseFrom(RetryBudget previous) {
      Objects.requireNonNull(previous, "previous must not be null");
      return providerAttemptsRemaining <= previous.providerAttemptsRemaining
          && activityAttemptsRemaining <= previous.activityAttemptsRemaining
          && repairsRemaining <= previous.repairsRemaining;
    }
  }

  public record PinnedVersions(
      String schemaVersion,
      String workflowBuildId,
      String graphVersion,
      String checkpointSchemaVersion,
      String promptVersion,
      String modelProfileId,
      String outputSchemaVersion,
      String policyVersion,
      String guardrailVersion,
      String toolPolicyVersion) {

    public PinnedVersions {
      requireSchema(schemaVersion, "intake-pinned-versions.v1");
      requireIdentifier(workflowBuildId, "workflowBuildId");
      requireIdentifier(graphVersion, "graphVersion");
      requireIdentifier(checkpointSchemaVersion, "checkpointSchemaVersion");
      requireIdentifier(promptVersion, "promptVersion");
      requireIdentifier(modelProfileId, "modelProfileId");
      if (!"intake-turn-proposal.v2".equals(outputSchemaVersion)) {
        throw new IllegalArgumentException("outputSchemaVersion must be intake-turn-proposal.v2");
      }
      requireIdentifier(policyVersion, "policyVersion");
      requireIdentifier(guardrailVersion, "guardrailVersion");
      requireIdentifier(toolPolicyVersion, "toolPolicyVersion");
    }
  }

  public record ImmutablePayloadRef(
      String schemaVersion,
      String artifactId,
      String artifactType,
      String artifactSchemaVersion,
      String uri,
      String objectVersion,
      String contentHash,
      long sizeBytes) {

    public ImmutablePayloadRef {
      requireSchema(schemaVersion, "immutable-payload-ref.v1");
      requireIdentifier(artifactId, "artifactId");
      if (artifactId.length() > 128) {
        throw new IllegalArgumentException("artifactId must be at most 128 characters");
      }
      requireIdentifier(artifactType, "artifactType");
      requireIdentifier(artifactSchemaVersion, "artifactSchemaVersion");
      requireReference(uri, "uri");
      requireBoundedText(objectVersion, 128, "objectVersion");
      requireHash(contentHash, "contentHash");
      if (sizeBytes < 1) {
        throw new IllegalArgumentException("sizeBytes must be positive");
      }
    }
  }

  public record ActivityEnvelope(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      String commandId,
      long commandSequence,
      IntakeCommandType commandType,
      IntakeParty party,
      String actorScopeHash,
      String commandPayloadRef,
      String commandPayloadHash,
      long processRevision,
      long roomRevision,
      long deadlineEpochMillis,
      RetryBudget retryBudget,
      PinnedVersions pinnedVersions) {

    public ActivityEnvelope {
      requireSchema(schemaVersion, "intake-activity-envelope.v1");
      requireIdentifier(tenantSurrogate, "tenantSurrogate");
      requireIdentifier(caseId, "caseId");
      requireIdentifier(commandId, "commandId");
      requireHash(actorScopeHash, "actorScopeHash");
      requireReference(commandPayloadRef, "commandPayloadRef");
      requireHash(commandPayloadHash, "commandPayloadHash");
      if (roomEpoch < 0
          || fencingToken < 1
          || commandSequence < 1
          || processRevision < 0
          || roomRevision < 0
          || deadlineEpochMillis < 1) {
        throw new IllegalArgumentException(
            "epoch, fence, sequence, revisions, and deadline must be valid");
      }
      if (commandType == null || party == null || retryBudget == null || pinnedVersions == null) {
        throw new IllegalArgumentException(
            "commandType, party, retryBudget, and pinnedVersions must not be null");
      }
    }
  }

  public record OperationReceipt(
      String schemaVersion,
      String operationKey,
      String requestHash,
      String resultHash,
      long processRevision,
      long roomRevision) {

    public OperationReceipt {
      requireSchema(schemaVersion, "intake-operation-receipt.v1");
      IntakeOperationKeys.requireValid(operationKey);
      requireHash(requestHash, "requestHash");
      requireHash(resultHash, "resultHash");
      requireRevisions(processRevision, roomRevision);
    }

    public ReplayDisposition replayDisposition(
        String incomingOperationKey, String incomingRequestHash) {
      IntakeOperationKeys.requireValid(incomingOperationKey);
      requireHash(incomingRequestHash, "incomingRequestHash");
      if (!operationKey.equals(incomingOperationKey)) {
        return ReplayDisposition.DIFFERENT_OPERATION;
      }
      return requestHash.equals(incomingRequestHash)
          ? ReplayDisposition.REPLAY_COMMITTED
          : ReplayDisposition.CONFLICT;
    }
  }

  public record SnapshotPublicationRequest(
      String schemaVersion,
      ActivityEnvelope envelope,
      String threadId,
      String agentSessionId,
      long domainRevision,
      String operationKey,
      String requestHash) {

    public SnapshotPublicationRequest {
      requireSchema(schemaVersion, "intake-snapshot-publication-request.v1");
      requireEnvelope(envelope);
      requireThreadId(threadId, "threadId");
      requireIdentifier(agentSessionId, "agentSessionId");
      if (domainRevision < 0) {
        throw new IllegalArgumentException("domainRevision must not be negative");
      }
      requireExactOperationKey(
          operationKey,
          IntakeOperationKeys.snapshotPublish(
              envelope.caseId(), envelope.roomEpoch(), envelope.actorScopeHash(), domainRevision));
      requireHash(requestHash, "requestHash");
    }
  }

  public record SnapshotPublicationReceipt(
      String schemaVersion,
      OperationReceipt operation,
      ImmutablePayloadRef snapshotPointer,
      long domainRevision) {

    public SnapshotPublicationReceipt {
      requireSchema(schemaVersion, "intake-snapshot-publication-receipt.v1");
      requireOperationPrefix(operation, "intake.snapshot.publish:");
      requireArtifact(
          snapshotPointer, "INTAKE_SNAPSHOT", "intake-domain-snapshot.v2", 256L * 1024L);
      if (domainRevision < 0) {
        throw new IllegalArgumentException("domainRevision must not be negative");
      }
    }
  }

  public record GraphExecutionRequest(
      String schemaVersion,
      ActivityEnvelope envelope,
      String threadId,
      String agentSessionId,
      String operationKey,
      String requestHash) {

    public GraphExecutionRequest {
      requireSchema(schemaVersion, "intake-graph-execution-request.v1");
      requireEnvelope(envelope);
      requireThreadId(threadId, "threadId");
      requireIdentifier(agentSessionId, "agentSessionId");
      requireExactOperationKey(
          operationKey,
          IntakeOperationKeys.graphExecute(
              envelope.caseId(), envelope.roomEpoch(), threadId, envelope.commandId()));
      requireHash(requestHash, "requestHash");
    }
  }

  public record GraphExecutionReceipt(
      String schemaVersion,
      OperationReceipt operation,
      IntakeAgentRunRef agentRunRef,
      IntakeGraphExecutionRef graphExecutionRef,
      ImmutablePayloadRef resultPointer,
      ImmutablePayloadRef proposalPointer) {

    public GraphExecutionReceipt {
      requireSchema(schemaVersion, "intake-graph-execution-receipt.v1");
      requireOperationPrefix(operation, "intake.graph.execute:");
      if (agentRunRef == null || graphExecutionRef == null) {
        throw new IllegalArgumentException("AgentRun and Graph references must not be null");
      }
      if (!operation.resultHash().equals(agentRunRef.finalResultHash())
          || !operation.resultHash().equals(graphExecutionRef.resultHash())) {
        throw new IllegalArgumentException("receipt and execution result hashes must match");
      }
      requireArtifact(resultPointer, "GRAPH_RESULT", "room-graph-result.v1", 64L * 1024L);
      requireArtifact(proposalPointer, "INTAKE_PROPOSAL", "intake-turn-proposal.v2", 64L * 1024L);
      if (!resultPointer.uri().equals(graphExecutionRef.resultRef())
          || !resultPointer.contentHash().equals(graphExecutionRef.resultHash())) {
        throw new IllegalArgumentException("result pointer must match the Graph result reference");
      }
      if (!proposalPointer.uri().equals(graphExecutionRef.proposalRef())
          || !proposalPointer.contentHash().equals(graphExecutionRef.proposalHash())) {
        throw new IllegalArgumentException(
            "proposal pointer must match the Graph proposal reference");
      }
    }
  }

  public record TurnFinalizationRequest(
      String schemaVersion,
      ActivityEnvelope envelope,
      String threadId,
      String agentSessionId,
      GraphExecutionReceipt graphExecution,
      String operationKey,
      String requestHash) {

    public TurnFinalizationRequest {
      requireSchema(schemaVersion, "intake-turn-finalization-request.v1");
      requireEnvelope(envelope);
      requireThreadId(threadId, "threadId");
      requireIdentifier(agentSessionId, "agentSessionId");
      Objects.requireNonNull(graphExecution, "graphExecution must not be null");
      IntakeGraphExecutionRef graphExecutionRef = graphExecution.graphExecutionRef();
      if (!threadId.equals(graphExecutionRef.threadId())
          || !envelope.commandId().equals(graphExecutionRef.graphCommandId())) {
        throw new IllegalArgumentException("Graph reference must match thread and command");
      }
      if (!envelope.pinnedVersions().graphVersion().equals(graphExecutionRef.graphVersion())) {
        throw new IllegalArgumentException("Graph reference must match the pinned graph version");
      }
      requireExactOperationKey(
          operationKey,
          IntakeOperationKeys.turnFinalize(
              envelope.caseId(),
              envelope.roomEpoch(),
              threadId,
              envelope.commandId(),
              graphExecutionRef.resultHash()));
      requireHash(requestHash, "requestHash");
    }
  }

  public record BranchCommitRequest(
      String schemaVersion,
      ActivityEnvelope envelope,
      BranchOperation operation,
      String operationKey,
      String requestHash) {

    public BranchCommitRequest {
      requireSchema(schemaVersion, "intake-branch-commit-request.v1");
      requireEnvelope(envelope);
      Objects.requireNonNull(operation, "operation must not be null");
      requireBranchShape(envelope, operation);
      requireExactOperationKey(operationKey, expectedBranchKey(envelope, operation));
      requireHash(requestHash, "requestHash");
    }
  }

  /** Exact frozen formal receipt; Activity metadata is kept in the wrapper below. */
  public record FormalFinalizationReceipt(
      String schemaVersion,
      String operationKey,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      String threadId,
      String actorScopeHash,
      String agentSessionId,
      String commandId,
      String logicalRunId,
      String attemptId,
      String resultHash,
      String proposalHash,
      long processRevision,
      long roomRevision,
      long fencingToken,
      String formalMessageId,
      Long dossierVersion,
      Long matrixVersion,
      List<String> domainEventIds,
      List<String> outboxIds,
      String status,
      String committedAt,
      String receiptHash) {

    public FormalFinalizationReceipt {
      requireSchema(schemaVersion, "intake-finalization-receipt.v1");
      IntakeOperationKeys.requireValid(operationKey);
      requireIdentifier(tenantSurrogate, "tenantSurrogate");
      requireIdentifier(caseId, "caseId");
      requireThreadId(threadId, "threadId");
      requireHash(actorScopeHash, "actorScopeHash");
      requireIdentifier(agentSessionId, "agentSessionId");
      requireIdentifier(commandId, "commandId");
      requireIdentifier(logicalRunId, "logicalRunId");
      requireIdentifier(attemptId, "attemptId");
      requireHash(resultHash, "resultHash");
      requireHash(proposalHash, "proposalHash");
      requireIdentifier(formalMessageId, "formalMessageId");
      requireHash(receiptHash, "receiptHash");
      requireRevisions(processRevision, roomRevision);
      if (roomEpoch < 0 || fencingToken < 1) {
        throw new IllegalArgumentException("roomEpoch and fencingToken must be valid");
      }
      requireOptionalVersion(dossierVersion, "dossierVersion");
      requireOptionalVersion(matrixVersion, "matrixVersion");
      domainEventIds = immutableIdentifiers(domainEventIds, "domainEventIds", 1, 16);
      outboxIds = immutableIdentifiers(outboxIds, "outboxIds", 0, 16);
      if (!"COMMITTED".equals(status)) {
        throw new IllegalArgumentException("status must be COMMITTED");
      }
      requireDateTime(committedAt);
      String expectedOperationKey =
          IntakeOperationKeys.turnFinalize(caseId, roomEpoch, threadId, commandId, resultHash);
      if (!expectedOperationKey.equals(operationKey)) {
        throw new IllegalArgumentException(
            "formal receipt operationKey does not match its authority fields");
      }
    }
  }

  /** Activity-only envelope that carries the frozen formal receipt and its committed event. */
  public record TurnFinalizationReceipt(
      String schemaVersion,
      OperationReceipt operation,
      FormalFinalizationReceipt formalReceipt,
      IntakeDomainEventRef committedEvent) {

    public TurnFinalizationReceipt {
      requireSchema(schemaVersion, "intake-turn-finalization-activity-receipt.v1");
      requireOperationPrefix(operation, "intake.turn.finalize:");
      Objects.requireNonNull(formalReceipt, "formalReceipt must not be null");
      requireTurnReceiptBindings(operation, formalReceipt, committedEvent);
    }
  }

  public record BranchCommitReceipt(
      String schemaVersion,
      BranchOperation branchOperation,
      OperationReceipt operation,
      IntakeDomainEventRef committedEvent) {

    public BranchCommitReceipt {
      requireSchema(schemaVersion, "intake-branch-commit-receipt.v1");
      Objects.requireNonNull(branchOperation, "branchOperation must not be null");
      Objects.requireNonNull(operation, "operation must not be null");
      Objects.requireNonNull(committedEvent, "committedEvent must not be null");
      IntakeDomainEventType expected = expectedEventType(branchOperation);
      if (committedEvent.eventType() != expected) {
        throw new IllegalArgumentException(
            branchOperation + " requires committed event type " + expected);
      }
      requireOperationEventBinding(operation, committedEvent);
    }
  }

  public static List<Class<?>> payloadTypes() {
    return List.of(
        RetryBudget.class,
        PinnedVersions.class,
        ImmutablePayloadRef.class,
        ActivityEnvelope.class,
        OperationReceipt.class,
        SnapshotPublicationRequest.class,
        SnapshotPublicationReceipt.class,
        GraphExecutionRequest.class,
        GraphExecutionReceipt.class,
        TurnFinalizationRequest.class,
        FormalFinalizationReceipt.class,
        TurnFinalizationReceipt.class,
        BranchCommitRequest.class,
        BranchCommitReceipt.class);
  }

  private static void requireBranchShape(ActivityEnvelope envelope, BranchOperation operation) {
    IntakeCommandType expectedCommandType =
        operation == BranchOperation.CANCEL
            ? IntakeCommandType.INTAKE_CANCEL
            : IntakeCommandType.INTAKE_CONFIRM;
    IntakeParty expectedParty =
        operation == BranchOperation.RESPONDENT_CONFIRM
            ? IntakeParty.RESPONDENT
            : IntakeParty.INITIATOR;
    if (envelope.commandType() != expectedCommandType || envelope.party() != expectedParty) {
      throw new IllegalArgumentException(
          operation + " requires " + expectedCommandType + " from " + expectedParty);
    }
  }

  private static String expectedBranchKey(ActivityEnvelope envelope, BranchOperation operation) {
    return switch (operation) {
      case INITIATOR_ACCEPT ->
          IntakeOperationKeys.initiatorAccept(
              envelope.caseId(), envelope.roomEpoch(), envelope.commandId());
      case INITIATOR_REJECT ->
          IntakeOperationKeys.initiatorReject(
              envelope.caseId(), envelope.roomEpoch(), envelope.commandId());
      case CANCEL ->
          IntakeOperationKeys.cancel(envelope.caseId(), envelope.roomEpoch(), envelope.commandId());
      case RESPONDENT_CONFIRM ->
          IntakeOperationKeys.respondentConfirm(
              envelope.caseId(), envelope.roomEpoch(), envelope.commandId());
    };
  }

  private static void requireSchema(String actual, String expected) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("schemaVersion must be " + expected);
    }
  }

  private static void requireEnvelope(ActivityEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope must not be null");
  }

  private static void requireExactOperationKey(String actual, String expected) {
    IntakeOperationKeys.requireValid(actual);
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("operationKey does not match the activity stage");
    }
  }

  private static void requireOperationPrefix(OperationReceipt operation, String prefix) {
    Objects.requireNonNull(operation, "operation must not be null");
    if (!operation.operationKey().startsWith(prefix)) {
      throw new IllegalArgumentException("operation receipt does not match the activity stage");
    }
  }

  private static void requireRevisions(long processRevision, long roomRevision) {
    if (processRevision < 0 || roomRevision < 0) {
      throw new IllegalArgumentException("revisions must not be negative");
    }
  }

  private static void requireBoundedText(String value, int maximumLength, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > maximumLength
        || value.chars().anyMatch(character -> Character.isISOControl(character))) {
      throw new IllegalArgumentException(field + " must be bounded non-control text");
    }
  }

  private static void requireArtifact(
      ImmutablePayloadRef reference,
      String expectedType,
      String expectedSchema,
      long maxSizeBytes) {
    Objects.requireNonNull(reference, "immutable payload reference must not be null");
    if (!expectedType.equals(reference.artifactType())
        || !expectedSchema.equals(reference.artifactSchemaVersion())
        || reference.sizeBytes() > maxSizeBytes) {
      throw new IllegalArgumentException(
          "immutable payload reference does not match the bounded artifact contract");
    }
  }

  private static void requireOptionalVersion(Long value, String field) {
    if (value != null && value < 1) {
      throw new IllegalArgumentException(field + " must be positive when present");
    }
  }

  private static List<String> immutableIdentifiers(
      List<String> values, String field, int minimumSize, int maximumSize) {
    if (values == null || values.size() < minimumSize || values.size() > maximumSize) {
      throw new IllegalArgumentException(field + " has an invalid item count");
    }
    List<String> copy = List.copyOf(values);
    copy.forEach(value -> requireIdentifier(value, field));
    if (new HashSet<>(copy).size() != copy.size()) {
      throw new IllegalArgumentException(field + " must contain unique identifiers");
    }
    return copy;
  }

  private static void requireDateTime(String value) {
    try {
      OffsetDateTime.parse(value);
    } catch (DateTimeParseException | NullPointerException exception) {
      throw new IllegalArgumentException("committedAt must be an offset date-time", exception);
    }
  }

  private static void requireTurnReceiptBindings(
      OperationReceipt operation,
      FormalFinalizationReceipt formalReceipt,
      IntakeDomainEventRef event) {
    Objects.requireNonNull(event, "committedEvent must not be null");
    if ((event.eventType() != IntakeDomainEventType.TURN_NEEDS_INPUT
            && event.eventType() != IntakeDomainEventType.TURN_READY_TO_CONFIRM)
        || event.agentRunRef() == null
        || event.graphExecutionRef() == null) {
      throw new IllegalArgumentException("turn finalization requires one committed turn event");
    }
    if (!operation.operationKey().equals(formalReceipt.operationKey())
        || !operation.operationKey().equals(event.operationKey())
        || !operation.requestHash().equals(event.requestHash())
        || !operation.resultHash().equals(formalReceipt.resultHash())
        || !operation.resultHash().equals(event.resultHash())
        || operation.processRevision() != formalReceipt.processRevision()
        || operation.roomRevision() != formalReceipt.roomRevision()
        || formalReceipt.processRevision() != event.processRevision()
        || formalReceipt.roomRevision() != event.roomRevision()
        || !formalReceipt.tenantSurrogate().equals(event.tenantSurrogate())
        || !formalReceipt.caseId().equals(event.caseId())
        || formalReceipt.roomEpoch() != event.roomEpoch()
        || formalReceipt.fencingToken() != event.fencingToken()
        || !formalReceipt.actorScopeHash().equals(event.actorScopeHash())
        || !formalReceipt.commandId().equals(event.commandId())
        || !formalReceipt.threadId().equals(event.graphExecutionRef().threadId())
        || !formalReceipt.logicalRunId().equals(event.agentRunRef().logicalRunId())
        || !formalReceipt.attemptId().equals(event.agentRunRef().attemptId())
        || !formalReceipt.proposalHash().equals(event.graphExecutionRef().proposalHash())
        || !formalReceipt.domainEventIds().contains(event.eventId())) {
      throw new IllegalArgumentException("finalization receipt authority bindings do not match");
    }
  }

  private static IntakeDomainEventType expectedEventType(BranchOperation operation) {
    return switch (operation) {
      case INITIATOR_ACCEPT -> IntakeDomainEventType.INITIATOR_ACCEPTED;
      case INITIATOR_REJECT -> IntakeDomainEventType.NOT_ADMISSIBLE;
      case CANCEL -> IntakeDomainEventType.CANCELLED;
      case RESPONDENT_CONFIRM -> IntakeDomainEventType.RESPONDENT_CONFIRMED;
    };
  }

  private static void requireOperationEventBinding(
      OperationReceipt operation, IntakeDomainEventRef event) {
    if (!operation.operationKey().equals(event.operationKey())
        || !operation.requestHash().equals(event.requestHash())
        || !operation.resultHash().equals(event.resultHash())
        || operation.processRevision() != event.processRevision()
        || operation.roomRevision() != event.roomRevision()) {
      throw new IllegalArgumentException("operation receipt and committed event must match");
    }
  }
}

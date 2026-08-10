package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import java.util.Objects;

public record CaseProcessIntakeProjectionRecoveryRequest(
    String schemaVersion,
    String workflowId,
    String workflowRunId,
    String firstExecutionRunId,
    String tenantSurrogate,
    String caseId,
    RoomType roomType,
    long roomEpoch,
    long fencingToken,
    String activeChildWorkflowId,
    String activeChildRunId,
    long expectedProcessRevision,
    long expectedRoomRevision,
    long nextCommandSequence,
    long nextCaseEventSequence,
    long processedCommandCount,
    long processedEventCount,
    ProcessedCommandIdentity recentCommand,
    CaseDomainEventRef event,
    CompleteConsumedIntakeProjectionCommand projectionCommand) {

  public static final String SCHEMA_VERSION =
      "case-process-intake-projection-recovery-request.v1";

  public CaseProcessIntakeProjectionRecoveryRequest {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be case-process-intake-projection-recovery-request.v1");
    }
    requireText(workflowId, "workflowId");
    requireText(workflowRunId, "workflowRunId");
    requireText(firstExecutionRunId, "firstExecutionRunId");
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    requireText(activeChildWorkflowId, "activeChildWorkflowId");
    requireText(activeChildRunId, "activeChildRunId");
    if (!CaseProcessWorkflowProtocol.caseWorkflowId(tenantSurrogate, caseId).equals(workflowId)) {
      throw new IllegalArgumentException("workflowId does not match the case authority");
    }
    if (roomType != RoomType.INTAKE) {
      throw new IllegalArgumentException("roomType must be INTAKE");
    }
    if (roomEpoch < 0 || fencingToken < 1) {
      throw new IllegalArgumentException("room epoch or fencing token is invalid");
    }
    if (expectedProcessRevision < 1 || expectedRoomRevision < 1) {
      throw new IllegalArgumentException("expected revisions are invalid");
    }
    if (nextCommandSequence < 2
        || nextCaseEventSequence < 1
        || processedCommandCount < 1
        || processedEventCount < 0
        || processedCommandCount != nextCommandSequence - 1
        || processedEventCount != nextCaseEventSequence - 1) {
      throw new IllegalArgumentException("pre-recovery sequence accounting is invalid");
    }
    recentCommand = Objects.requireNonNull(recentCommand, "recentCommand must not be null");
    event = Objects.requireNonNull(event, "event must not be null");
    projectionCommand =
        Objects.requireNonNull(projectionCommand, "projectionCommand must not be null");
    if (recentCommand.caseCommandSequence() != nextCommandSequence - 1
        || recentCommand.caseCommandSequence() != projectionCommand.lastCommandSequence()) {
      throw new IllegalArgumentException("recent command does not match the projection cursor");
    }
    if (!tenantSurrogate.equals(event.tenantSurrogate())
        || !caseId.equals(event.caseId())
        || event.roomType() != RoomType.INTAKE
        || event.roomEpoch() != roomEpoch
        || event.caseEventSequence() != nextCaseEventSequence) {
      throw new IllegalArgumentException("event does not match the Intake recovery authority");
    }
    if (!tenantSurrogate.equals(projectionCommand.tenantSurrogate())
        || !caseId.equals(projectionCommand.caseId())
        || !event.eventId().equals(projectionCommand.eventId())
        || event.caseEventSequence() != projectionCommand.caseEventSequence()
        || !event.eventType().equals(projectionCommand.eventType())
        || projectionCommand.roomEpoch() != roomEpoch
        || projectionCommand.fencingToken() != fencingToken
        || projectionCommand.processRevision() != expectedProcessRevision
        || projectionCommand.roomRevision() != expectedRoomRevision
        || !workflowId.equals(projectionCommand.temporalWorkflowId())
        || !firstExecutionRunId.equals(projectionCommand.firstExecutionRunId())
        || !activeChildRunId.equals(projectionCommand.activeChildRunId())) {
      throw new IllegalArgumentException(
          "projection command does not match the recovery authority");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}

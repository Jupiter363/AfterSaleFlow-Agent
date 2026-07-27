package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.util.Objects;

/** Immutable target-lane authority and the exact AgentRun V2 child request. */
public record IntakeTargetAgentRunContext(
    String schemaVersion,
    String executionLane,
    String activationId,
    String activationManifestHash,
    long roomFencingToken,
    long expectedProcessRevision,
    long expectedRoomRevision,
    String caseBuildId,
    String controlBuildId,
    String agentBuildId,
    String graphBindingHash,
    String graphCodeBuildId,
    String commandHash,
    String commandEnvelopeHash,
    ExecuteAgentRunRequest request) {

  public static final String TARGET_LANE = "TARGET_E2E_CANDIDATE";

  public IntakeTargetAgentRunContext {
    if (!"intake-target-agent-run-context.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-target-agent-run-context.v1");
    }
    if (!TARGET_LANE.equals(executionLane)) {
      throw new IllegalArgumentException("executionLane must be TARGET_E2E_CANDIDATE");
    }
    if (activationId == null || !activationId.matches("p9act\\.v1\\.[0-9a-f]{32}")) {
      throw new IllegalArgumentException("activationId must be a target-E2E activation id");
    }
    requireHash(activationManifestHash, "activationManifestHash");
    if (roomFencingToken < 1 || expectedProcessRevision < 0 || expectedRoomRevision < 0) {
      throw new IllegalArgumentException("target authority revisions and fence are invalid");
    }
    requireIdentifier(caseBuildId, "caseBuildId");
    requireIdentifier(controlBuildId, "controlBuildId");
    requireIdentifier(agentBuildId, "agentBuildId");
    requireHash(graphBindingHash, "graphBindingHash");
    requireIdentifier(graphCodeBuildId, "graphCodeBuildId");
    requireHash(commandHash, "commandHash");
    requireHash(commandEnvelopeHash, "commandEnvelopeHash");
    Objects.requireNonNull(request, "request must not be null");
    if (request.attemptNo() != 1) {
      throw new IllegalArgumentException("Intake child must start AgentRun attempt one");
    }
    RoomGraphCommand graphCommand = request.command();
    if (graphCommand.roomType() != RoomType.INTAKE) {
      throw new IllegalArgumentException("target AgentRun command must be for Intake");
    }
    requireHash(request.logicalInputHash(), "logicalInputHash");
    requireHash(graphCommand.requestHash(), "graph command requestHash");
  }

  public void requireMatches(
      IntakeRoomStart start,
      IntakeWorkflowCommand command,
      long processRevision,
      long roomRevision) {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(command, "command");
    RoomGraphCommand graphCommand = request.command();
    if (!start.tenantSurrogate().equals(command.tenantSurrogate())
        || !start.tenantSurrogate().equals(graphCommand.tenantSurrogate())
        || !start.caseId().equals(command.caseId())
        || !start.caseId().equals(graphCommand.caseId())
        || start.roomEpoch() != command.roomEpoch()
        || start.roomEpoch() != graphCommand.roomEpoch()
        || start.fencingToken() != command.fencingToken()
        || start.fencingToken() != roomFencingToken
        || expectedProcessRevision != processRevision
        || expectedRoomRevision != roomRevision
        || graphCommand.processRevision() != processRevision
        || !start.workflowBuildId().equals(controlBuildId)
        || !start.graphVersion().equals(graphCommand.graphVersion())
        || !start.checkpointSchemaVersion().equals(graphCommand.checkpointSchemaVersion())
        || !command.commandId().equals(graphCommand.commandId())
        || graphCommand.eventRef() == null
        || !command.payloadHash().equals(graphCommand.eventRef().sha256())
        || !command.payloadRef().equals(graphCommand.eventRef().uri())
        || command.executionContext() == null
        || !command.executionContext().threadId().equals(graphCommand.threadId())
        || command.executionContext().deadlineEpochMillis()
            != graphCommand.deadlineAt().toEpochMilli()
        || !request.agentRunId().equals(graphCommand.logicalRunId())) {
      throw new IllegalArgumentException(
          "target AgentRun authority does not match the current Intake command");
    }
  }
}

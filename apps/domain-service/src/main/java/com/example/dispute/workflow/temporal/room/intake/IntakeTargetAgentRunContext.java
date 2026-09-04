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
    ExecuteAgentRunRequest request,
    IntakeParallelTurnContext parallelTurnContext) {

  public static final String TARGET_LANE = "PRODUCTION";
  public static final String INITIAL_SCHEMA_VERSION = "intake-target-agent-run-context.v1";
  public static final String RETRY_SCHEMA_VERSION = "intake-target-agent-run-context.v2";

  public IntakeTargetAgentRunContext(
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
    this(
        schemaVersion,
        executionLane,
        activationId,
        activationManifestHash,
        roomFencingToken,
        expectedProcessRevision,
        expectedRoomRevision,
        caseBuildId,
        controlBuildId,
        agentBuildId,
        graphBindingHash,
        graphCodeBuildId,
        commandHash,
        commandEnvelopeHash,
        request,
        null);
  }

  public IntakeTargetAgentRunContext {
    if (!INITIAL_SCHEMA_VERSION.equals(schemaVersion)
        && !RETRY_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-target-agent-run-context.v1 or v2");
    }
    if (!TARGET_LANE.equals(executionLane)) {
      throw new IllegalArgumentException("executionLane must be PRODUCTION");
    }
    if (activationId == null || !activationId.matches("p9act\\.v1\\.[0-9a-f]{32}")) {
      throw new IllegalArgumentException("activationId must be a production-runtime activation id");
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
    if ((INITIAL_SCHEMA_VERSION.equals(schemaVersion) && request.attemptNo() != 1)
        || (RETRY_SCHEMA_VERSION.equals(schemaVersion)
            && (request.attemptNo() <= 1 || request.previousAttemptId() == null))) {
      throw new IllegalArgumentException(
          "Intake target context attempt does not match its schema version");
    }
    RoomGraphCommand graphCommand = request.command();
    if (graphCommand.roomType() != RoomType.INTAKE) {
      throw new IllegalArgumentException("target AgentRun command must be for Intake");
    }
    requireHash(request.logicalInputHash(), "logicalInputHash");
    requireHash(graphCommand.requestHash(), "graph command requestHash");
    if (ExecuteAgentRunRequest.isParallelIntakeCommand(graphCommand)) {
      Objects.requireNonNull(
              parallelTurnContext,
              "parallel Intake AgentRun requires frozen per-command turn context")
          .requireMatches(graphCommand);
    } else if (parallelTurnContext != null) {
      parallelTurnContext.requireMatches(graphCommand);
    }
  }

  public void requireMatches(
      IntakeRoomStart start,
      IntakeWorkflowCommand command,
      long processRevision,
      long roomRevision) {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(command, "command");
    if (!INITIAL_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "only the initial target context may start an Intake AgentRun child");
    }
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

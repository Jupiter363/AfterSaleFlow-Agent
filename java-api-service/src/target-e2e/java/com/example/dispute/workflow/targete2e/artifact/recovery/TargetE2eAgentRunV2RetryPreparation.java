package com.example.dispute.workflow.targete2e.artifact.recovery;

import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.RecoveryState;
import com.example.dispute.agentstream.application.AgentRunV2NextAttemptFactory;
import com.example.dispute.agentstream.application.AgentRunV2RetryPreparation;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphCommandEnvelope;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Target-artifact adapter that seals and admits every later AgentRun command transactionally. */
public final class TargetE2eAgentRunV2RetryPreparation
    implements AgentRunV2RetryPreparation {

  private static final String TARGET_GRAPH_KEY = "all-rooms.target-e2e.v1";

  private final ObjectMapper objectMapper;
  private final TargetE2EGraphEnvelopeCodec envelopes;
  private final TargetIntakeCommandMaterialStore intake;
  private final TargetEvidenceCommandMaterialStore evidence;
  private final TargetHearingCommandMaterialStore hearing;
  private final TargetReviewCommandMaterialStore review;
  private final TargetE2eRoomObjectIndex objectIndex;

  public TargetE2eAgentRunV2RetryPreparation(
      ObjectMapper objectMapper,
      TargetE2EGraphEnvelopeCodec envelopes,
      TargetIntakeCommandMaterialStore intake,
      TargetEvidenceCommandMaterialStore evidence,
      TargetHearingCommandMaterialStore hearing,
      TargetReviewCommandMaterialStore review,
      TargetE2eRoomObjectIndex objectIndex) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
    this.intake = Objects.requireNonNull(intake, "intake");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.hearing = Objects.requireNonNull(hearing, "hearing");
    this.review = Objects.requireNonNull(review, "review");
    this.objectIndex = Objects.requireNonNull(objectIndex, "objectIndex");
  }

  @Override
  public boolean supports(RecoveryState state) {
    Objects.requireNonNull(state, "state");
    if (state.logicalRun().protocol() != AgentRunProtocol.V2
        || state.logicalRun().executorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY) {
      return false;
    }
    try {
      RoomGraphCommand command = objectMapper.readValue(
          state.latestAttempt().canonicalCommandJson(), RoomGraphCommand.class);
      return TARGET_GRAPH_KEY.equals(command.graphKey())
          && switch (command.roomType()) {
            case INTAKE, EVIDENCE, HEARING, REVIEW -> true;
            default -> false;
          };
    } catch (Exception failure) {
      return false;
    }
  }

  @Override
  public AttemptAllocation prepareNextAttempt(
      RecoveryState state, AgentRunV2NextAttemptFactory factory, java.time.Instant preparedAt) {
    Objects.requireNonNull(preparedAt, "preparedAt");
    return Objects.requireNonNull(factory, "factory").next(state);
  }

  @Override
  public void persistAllocatedRequest(RecoveryState predecessor, ExecuteAgentRunRequest request) {
    requireLaterAttempt(predecessor, request);
    switch (request.command().roomType()) {
      case INTAKE -> persistIntake(predecessor, request);
      case EVIDENCE -> persistEvidence(predecessor, request);
      case HEARING -> persistHearing(predecessor, request);
      case REVIEW -> persistReview(predecessor, request);
      default -> throw new IllegalStateException("target retry room is unsupported");
    }
  }

  @Override
  public void verifyAllocatedRequest(RecoveryState current, ExecuteAgentRunRequest request) {
    if (!current.logicalRun().agentRunId().equals(request.agentRunId())
        || current.latestAttempt().attemptNo() != request.attemptNo()
        || !current.latestAttempt().attemptId().equals(request.attemptId())) {
      throw new IllegalStateException("allocated target retry identity is inconsistent");
    }
    RoomGraphCommand command = request.command();
    switch (command.roomType()) {
      case INTAKE -> {
        var snapshot = intake.readByRoute(
                intakeRoute(command, command.commandId(), current.logicalRun().fencingToken()))
            .orElseThrow(() -> new IllegalStateException("allocated Intake retry material is absent"));
        requireExactRequest(snapshot.context().targetAgentRun().request(), request);
      }
      case EVIDENCE -> {
        var snapshot = evidence.readByRoute(
                evidenceRoute(command, command.commandId(), current.logicalRun().fencingToken()))
            .orElseThrow(() -> new IllegalStateException("allocated Evidence retry material is absent"));
        requireExactRequest(snapshot.material().request(), request);
      }
      case HEARING -> {
        var snapshot = hearing.readByRoute(
                hearingRoute(command, command.commandId(), current.logicalRun().fencingToken()))
            .orElseThrow(() -> new IllegalStateException("allocated Hearing retry material is absent"));
        requireExactRequest(snapshot.material().request(), request);
      }
      case REVIEW -> {
        var snapshot = review.readByRoute(
                reviewRoute(command, command.commandId(), current.logicalRun().fencingToken()))
            .orElseThrow(() -> new IllegalStateException("allocated Review retry material is absent"));
        requireExactRequest(snapshot.material().request(), request);
      }
      default -> throw new IllegalStateException("target retry room is unsupported");
    }
  }

  private void persistIntake(RecoveryState state, ExecuteAgentRunRequest request) {
    RoomGraphCommand command = request.command();
    var previous = intake.readByRoute(
            intakeRoute(command, state.latestAttempt().commandId(), state.logicalRun().fencingToken()))
        .orElseThrow(() -> new IllegalStateException("predecessor Intake material is absent"));
    IntakeCommandExecutionContext context = previous.context();
    IntakeTargetAgentRunContext target = context.targetAgentRun();
    requirePredecessor(target.request(), state);
    TargetE2EGraphCommandEnvelope sealed = seal(previous.admission(), command);
    IntakeTargetAgentRunContext retryTarget = new IntakeTargetAgentRunContext(
        IntakeTargetAgentRunContext.RETRY_SCHEMA_VERSION,
        target.executionLane(), target.activationId(), target.activationManifestHash(),
        target.roomFencingToken(), target.expectedProcessRevision(), target.expectedRoomRevision(),
        target.caseBuildId(), target.controlBuildId(), target.agentBuildId(),
        target.graphBindingHash(), target.graphCodeBuildId(), sealed.commandHash(),
        sealed.commandEnvelopeHash(), request);
    var graphBudget = command.retryBudget();
    RetryBudget retryBudget = new RetryBudget(
        context.retryBudget().schemaVersion(), graphBudget.providerAttemptsRemaining(),
        graphBudget.activityAttemptsRemaining(), graphBudget.repairsRemaining());
    IntakeCommandExecutionContext retryContext = new IntakeCommandExecutionContext(
        context.schemaVersion(), context.threadId(), context.agentSessionId(),
        context.deadlineEpochMillis(), retryBudget, context.branchOperation(), retryTarget);
    intake.append(admission(previous.admission(), command, sealed), retryContext);
  }

  private void persistEvidence(RecoveryState state, ExecuteAgentRunRequest request) {
    RoomGraphCommand command = request.command();
    var previous = evidence.readByRoute(
            evidenceRoute(command, state.latestAttempt().commandId(), state.logicalRun().fencingToken()))
        .orElseThrow(() -> new IllegalStateException("predecessor Evidence material is absent"));
    requirePredecessor(previous.material().request(), state);
    TargetE2EGraphCommandEnvelope sealed = seal(previous.admission(), command);
    CommandAdmission admission = admission(previous.admission(), command, sealed);
    TargetEvidenceCommandMaterial source = previous.material();
    evidence.append(admission, new TargetEvidenceCommandMaterial(
        source.schemaVersion(), source.executionLane(), source.activationId(),
        source.activationManifestHash(), source.roomFencingToken(),
        source.expectedProcessRevision(), source.expectedRoomRevision(), sealed.commandHash(),
        sealed.commandEnvelopeHash(), source.caseCommandRequestHash(), request));
    rebind(previous.admission(), source.request().command(), admission, command, sealed);
  }

  private void persistHearing(RecoveryState state, ExecuteAgentRunRequest request) {
    RoomGraphCommand command = request.command();
    var previous = hearing.readByRoute(
            hearingRoute(command, state.latestAttempt().commandId(), state.logicalRun().fencingToken()))
        .orElseThrow(() -> new IllegalStateException("predecessor Hearing material is absent"));
    requirePredecessor(previous.material().request(), state);
    TargetE2EGraphCommandEnvelope sealed = seal(previous.admission(), command);
    CommandAdmission admission = admission(previous.admission(), command, sealed);
    hearing.append(new TargetHearingCommandMaterial(
        TargetHearingCommandMaterial.SCHEMA_VERSION, admission, request,
        sealed.commandHash(), sealed.commandEnvelopeHash()));
    rebind(previous.admission(), previous.material().request().command(), admission, command, sealed);
  }

  private void persistReview(RecoveryState state, ExecuteAgentRunRequest request) {
    RoomGraphCommand command = request.command();
    var previous = review.readByRoute(
            reviewRoute(command, state.latestAttempt().commandId(), state.logicalRun().fencingToken()))
        .orElseThrow(() -> new IllegalStateException("predecessor Review material is absent"));
    requirePredecessor(previous.material().request(), state);
    TargetE2EGraphCommandEnvelope sealed = seal(previous.admission(), command);
    CommandAdmission admission = admission(previous.admission(), command, sealed);
    TargetReviewCommandMaterial source = previous.material();
    review.append(admission, new TargetReviewCommandMaterial(
        source.schemaVersion(), source.executionLane(), source.activationId(),
        source.activationManifestHash(), source.roomFencingToken(),
        source.expectedProcessRevision(), source.expectedRoomRevision(), sealed.commandHash(),
        sealed.commandEnvelopeHash(), request));
    rebind(previous.admission(), source.request().command(), admission, command, sealed);
  }

  private void rebind(
      CommandAdmission previousAdmission,
      RoomGraphCommand previousCommand,
      CommandAdmission nextAdmission,
      RoomGraphCommand nextCommand,
      TargetE2EGraphCommandEnvelope nextEnvelope) {
    TargetE2EGraphCommandEnvelope previousEnvelope =
        envelopes.wrapCommand(
            previousAdmission.activationId(), previousAdmission.roomFencingToken(), previousCommand);
    if (!previousEnvelope.commandHash().equals(previousAdmission.commandHash())
        || !previousEnvelope.commandEnvelopeHash().equals(
            previousAdmission.commandEnvelopeHash())) {
      throw new IllegalStateException("predecessor target command envelope is inconsistent");
    }
    objectIndex.rebindInputs(
        authority(previousAdmission, previousCommand, previousEnvelope),
        previousCommand,
        authority(nextAdmission, nextCommand, nextEnvelope),
        nextCommand);
  }

  private TargetE2EGraphCommandEnvelope seal(CommandAdmission source, RoomGraphCommand command) {
    return envelopes.wrapCommand(source.activationId(), source.roomFencingToken(), command);
  }

  private static CommandAdmission admission(
      CommandAdmission source,
      RoomGraphCommand command,
      TargetE2EGraphCommandEnvelope envelope) {
    return new CommandAdmission(
        source.activationId(), source.manifestHash(), source.isolatedDomainDbBindingHash(),
        source.tenantSurrogate(), source.caseId(), command.commandId(), envelope.commandHash(),
        envelope.commandEnvelopeHash(), source.roomEpoch(), source.roomFencingToken());
  }

  private static Authority authority(
      CommandAdmission admission,
      RoomGraphCommand command,
      TargetE2EGraphCommandEnvelope envelope) {
    return new Authority(
        "target-e2e-room-exchange-authority.v1", admission.activationId(),
        admission.roomFencingToken(), envelope.commandHash(), envelope.commandEnvelopeHash(),
        command.tenantSurrogate(), command.caseId(), command.roomType().name(), command.roomEpoch(),
        command.threadId(), command.commandId(), command.logicalRunId(), command.attemptId(),
        command.requestHash(), command.graphKey(), command.graphVersion(),
        command.checkpointSchemaVersion(), command.processRevision(), command.stageCode(),
        command.stageSequence());
  }

  private static TargetIntakeCommandMaterialStore.CommandLookup intakeRoute(
      RoomGraphCommand command, String commandId, long fencingToken) {
    return new TargetIntakeCommandMaterialStore.CommandLookup(
        command.tenantSurrogate(), command.caseId(), commandId, command.roomEpoch(), fencingToken);
  }

  private static TargetEvidenceCommandMaterialStore.CommandLookup evidenceRoute(
      RoomGraphCommand command, String commandId, long fencingToken) {
    return new TargetEvidenceCommandMaterialStore.CommandLookup(
        command.tenantSurrogate(), command.caseId(), commandId, command.roomEpoch(), fencingToken);
  }

  private static TargetHearingCommandMaterialStore.Route hearingRoute(
      RoomGraphCommand command, String commandId, long fencingToken) {
    return new TargetHearingCommandMaterialStore.Route(
        command.tenantSurrogate(), command.caseId(), commandId, command.roomEpoch(), fencingToken);
  }

  private static TargetReviewCommandMaterialStore.Route reviewRoute(
      RoomGraphCommand command, String commandId, long fencingToken) {
    return new TargetReviewCommandMaterialStore.Route(
        command.tenantSurrogate(), command.caseId(), commandId, command.roomEpoch(), fencingToken);
  }

  private static void requireLaterAttempt(RecoveryState state, ExecuteAgentRunRequest request) {
    if (!state.logicalRun().agentRunId().equals(request.agentRunId())
        || request.attemptNo() != state.latestAttempt().attemptNo() + 1
        || !state.latestAttempt().attemptId().equals(request.previousAttemptId())) {
      throw new IllegalStateException("target retry request does not follow its predecessor");
    }
  }

  private static void requirePredecessor(ExecuteAgentRunRequest request, RecoveryState state) {
    if (!request.agentRunId().equals(state.logicalRun().agentRunId())
        || request.attemptNo() != state.latestAttempt().attemptNo()
        || !request.attemptId().equals(state.latestAttempt().attemptId())
        || !request.command().commandId().equals(state.latestAttempt().commandId())) {
      throw new IllegalStateException("target retry material is not the durable predecessor");
    }
  }

  private static void requireExactRequest(
      ExecuteAgentRunRequest actual, ExecuteAgentRunRequest expected) {
    if (!expected.equals(actual)) {
      throw new IllegalStateException("allocated target retry material differs from its request");
    }
  }
}

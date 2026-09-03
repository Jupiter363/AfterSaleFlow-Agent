package com.example.dispute.workflow.targete2e.temporal.intake;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.MaterialSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakePartyScopeSource.PartyBinding;
import com.example.dispute.workflow.targete2e.temporal.intake.TargetIntakePartyScopeSource.ResolvedPartyScopes;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/**
 * CONTROL-side authority bridge for target Intake. Persisted message material supplies the v2
 * execution context, while exact registered private-thread authority supplies branch v5; the
 * Activity result is the replay-stable workflow input.
 */
public final class TargetIntakeCommandBridgeActivity implements TargetIntakeCommandBridgeActivities {

  static final String BINDING_INVALID = "TARGET_INTAKE_COMMAND_BINDING_INVALID";

  private final TargetIntakeCommandMaterialStore materialStore;
  private final ObjectMapper objectMapper;
  private final TargetIntakePartyScopeSource partyScopeSource;
  private final TargetIntakeBranchContextSource branchContextSource;

  public TargetIntakeCommandBridgeActivity(
      TargetIntakeCommandMaterialStore materialStore,
      ObjectMapper objectMapper,
      TargetIntakePartyScopeSource partyScopeSource) {
    this(materialStore, objectMapper, partyScopeSource, null);
  }

  public TargetIntakeCommandBridgeActivity(
      TargetIntakeCommandMaterialStore materialStore,
      ObjectMapper objectMapper,
      TargetIntakePartyScopeSource partyScopeSource,
      TargetIntakeBranchContextSource branchContextSource) {
    this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.partyScopeSource = Objects.requireNonNull(partyScopeSource, "partyScopeSource");
    this.branchContextSource = branchContextSource;
  }

  @Override
  public IntakeWorkflowCommand bindCommand(BindRequest request) {
    try {
      Objects.requireNonNull(request, "request");
      CaseCommandRef command = request.command();
      require(command.roomType() == RoomType.INTAKE, "command is not routed to Intake");
      ResolvedPartyScopes partyScopes = resolvePartyScopes(command, request.roomFencingToken());
      if (command.commandType() != CommandType.INTAKE_MESSAGE) {
        return bindBranch(command, request, partyScopes);
      }

      MaterialSnapshot material =
          materialStore
              .readByRoute(
                  new TargetIntakeCommandMaterialStore.CommandLookup(
                      command.tenantSurrogate(),
                      command.caseId(),
                      command.commandId(),
                      command.roomEpoch(),
                      request.roomFencingToken()))
              .orElseThrow(() -> new IllegalArgumentException("target Intake material is absent"));
      return bind(command, request, material, partyScopes);
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(
          failure.getMessage(), BINDING_INVALID);
    }
  }

  private IntakeWorkflowCommand bindBranch(
      CaseCommandRef command, BindRequest request, ResolvedPartyScopes partyScopes) {
    require(
        command.commandType() == CommandType.INTAKE_CONFIRM
            || command.commandType() == CommandType.INTAKE_CANCEL,
        "target Intake bridge command type is unsupported");
    PartyBinding actor =
        partyScopes.actor(command.actorRef().actorId(), command.actorRef().actorRole());
    IntakeParty party = actor.party();
    String actorScopeHash = actor.actorScopeHash();
    TargetIntakeBranchContextSource source =
        Objects.requireNonNull(branchContextSource, "target Intake branch context source is not configured");
    TargetIntakeBranchContextSource.ResolvedBranchContext resolved =
        source.resolve(
            new TargetIntakeBranchContextSource.Request(
                command,
                request.roomFencingToken(),
                party,
                actorScopeHash,
                partyScopes.activationId(),
                partyScopes.activationManifestHash()));
    IntakeCommandType type =
        command.commandType() == CommandType.INTAKE_CONFIRM
            ? IntakeCommandType.INTAKE_CONFIRM
            : IntakeCommandType.INTAKE_CANCEL;
    PinnedVersions branchPinnedVersions = resolved.branchPinnedVersions();
    require(
        branchPinnedVersions != null,
        "target Intake branch private-thread pins are absent");
    IntakeCommandExecutionContext context =
        new IntakeCommandExecutionContext(
            "intake-command-execution-context.v5",
            resolved.threadId(),
            resolved.agentSessionId(),
            command.deadlineAt().toEpochMilli(),
            new RetryBudget("intake-retry-budget.v1", 0, 3, 0),
            resolved.operation(),
            null,
            command.expectedProcessRevision(),
            request.expectedRoomRevision(),
            branchPinnedVersions);
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        command.commandId(),
        command.tenantSurrogate(),
        command.caseId(),
        command.roomEpoch(),
        request.roomFencingToken(),
        command.caseCommandSequence(),
        type,
        party,
        actorScopeHash,
        command.payloadRef().uri(),
        command.payloadRef().sha256(),
        operationKey(command, resolved.operation()),
        command.requestHash(),
        context);
  }

  private static String operationKey(
      CaseCommandRef command,
      com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation operation) {
    return switch (operation) {
      case INITIATOR_ACCEPT ->
          IntakeOperationKeys.initiatorAccept(command.caseId(), command.roomEpoch(), command.commandId());
      case INITIATOR_REJECT ->
          IntakeOperationKeys.initiatorReject(command.caseId(), command.roomEpoch(), command.commandId());
      case CANCEL -> IntakeOperationKeys.cancel(command.caseId(), command.roomEpoch(), command.commandId());
      case RESPONDENT_CONFIRM ->
          IntakeOperationKeys.respondentConfirm(command.caseId(), command.roomEpoch(), command.commandId());
    };
  }

  private IntakeWorkflowCommand bind(
      CaseCommandRef command,
      BindRequest request,
      MaterialSnapshot material,
      ResolvedPartyScopes partyScopes) {
    IntakeCommandExecutionContext context = material.context();
    require(
        "intake-command-execution-context.v2".equals(context.schemaVersion()),
        "target Intake execution context must be v2");
    IntakeTargetAgentRunContext target = context.targetAgentRun();
    require(target != null, "target Intake execution context is absent");
    RoomGraphCommand graph = target.request().command();

    require(material.admission().activationId().equals(target.activationId()), "admission activation");
    require(
        material.admission().manifestHash().equals(target.activationManifestHash()),
        "admission manifest hash");
    require(material.admission().commandHash().equals(target.commandHash()), "admission command hash");
    require(
        material.admission().commandEnvelopeHash().equals(target.commandEnvelopeHash()),
        "admission command envelope hash");
    require(material.admission().tenantSurrogate().equals(command.tenantSurrogate()), "admission tenant");
    require(material.admission().caseId().equals(command.caseId()), "admission case");
    require(material.admission().commandId().equals(command.commandId()), "admission command id");
    require(material.admission().roomEpoch() == command.roomEpoch(), "admission room epoch");
    require(
        material.admission().roomFencingToken() == request.roomFencingToken(), "admission fence");
    require(target.roomFencingToken() == request.roomFencingToken(), "target context fence");
    require(target.expectedProcessRevision() == command.expectedProcessRevision(), "target process revision");
    require(target.expectedRoomRevision() == request.expectedRoomRevision(), "target room revision");
    require(partyScopes.activationId().equals(target.activationId()), "party authority activation");
    require(
        partyScopes.activationManifestHash().equals(target.activationManifestHash()),
        "party authority manifest hash");

    require(graph.roomType() == RoomType.INTAKE, "graph room type");
    require(graph.commandId().equals(command.commandId()), "graph command id");
    require(graph.tenantSurrogate().equals(command.tenantSurrogate()), "graph tenant");
    require(graph.caseId().equals(command.caseId()), "graph case");
    require(graph.roomEpoch() == command.roomEpoch(), "graph room epoch");
    require(graph.processRevision() == command.expectedProcessRevision(), "graph process revision");
    require(graph.deadlineAt().equals(command.deadlineAt()), "graph deadline");
    require(graph.eventRef() != null, "graph event reference");
    require(graph.eventRef().uri().equals(command.payloadRef().uri()), "graph payload URI");
    require(graph.eventRef().sha256().equals(command.payloadRef().sha256()), "graph payload hash");
    PartyBinding actor =
        partyScopes.actor(command.actorRef().actorId(), command.actorRef().actorRole());
    IntakeParty party = actor.party();
    require(
        graph.actorScope().equals(
            TargetIntakeActorScopes.scope(
                command.caseId(), actor.actorId(), actor.actorRole())),
        "graph actor scope");
    require(graph.actorScope().actorId().equals(command.actorRef().actorId()), "graph actor id");
    require(graph.actorScope().actorRole() == command.actorRef().actorRole(), "graph actor role");
    require(graph.actorScope().capabilities().equals(command.actorRef().actorScopes()), "graph actor scopes");
    require(context.threadId().equals(graph.threadId()), "context thread id");
    require(
        context.deadlineEpochMillis() == graph.deadlineAt().toEpochMilli(), "context deadline");
    require(target.request().agentRunId().equals(graph.logicalRunId()), "AgentRun id");

    String actorScopeHash = actor.actorScopeHash();
    require(
        actorScopeHash.equals(ContractJson.sha256Hex(objectMapper.valueToTree(graph.actorScope()))),
        "graph actor scope hash");
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        command.commandId(),
        command.tenantSurrogate(),
        command.caseId(),
        command.roomEpoch(),
        request.roomFencingToken(),
        command.caseCommandSequence(),
        IntakeCommandType.INTAKE_MESSAGE,
        party,
        actorScopeHash,
        command.payloadRef().uri(),
        command.payloadRef().sha256(),
        IntakeOperationKeys.graphExecute(
            command.caseId(), command.roomEpoch(), graph.threadId(), command.commandId()),
        command.requestHash(),
        context);
  }

  private ResolvedPartyScopes resolvePartyScopes(
      CaseCommandRef command, long roomFencingToken) {
    TargetIntakePartyScopeSource.Request request =
        new TargetIntakePartyScopeSource.Request(
            command.tenantSurrogate(),
            command.caseId(),
            command.roomEpoch(),
            roomFencingToken);
    ResolvedPartyScopes resolved = partyScopeSource.resolve(request);
    Objects.requireNonNull(resolved, "target Intake party scope source returned null")
        .requireMatches(request);
    return resolved;
  }

  private static void require(boolean condition, String field) {
    if (!condition) {
      throw new IllegalArgumentException("target Intake material does not match " + field);
    }
  }
}

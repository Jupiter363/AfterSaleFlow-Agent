package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.targete2e.ingress.rooms.MinioTargetE2eRoomCommandPayloadPublisher;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetE2eHearingInvocationPublisher;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CONTROL-only producer of the seven internal Hearing AgentRun requests.
 * Browser commands cannot enter this type: it accepts a non-party workflow stage only and is
 * invoked by {@link TargetHearingFormalizationActivities#prepareAgentStage}.
 */
public final class TargetHearingInternalStageMaterializer {
  private static final int ATTEMPT_LIMIT = 3;
  private final JdbcTargetE2eApiAuthority authority;
  private final TargetIntakeRuntimePins pins;
  private final AgentRunLedger ledger;
  private final AgentRunCommandBindingFactory bindings;
  private final com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec envelopes;
  private final MinioTargetE2eRoomCommandPayloadPublisher payloads;
  private final TargetE2eHearingInvocationPublisher hearingPublisher;
  private final TargetHearingCommandMaterialStore materialStore;
  private final JdbcTargetHearingAgentStageInputFactory inputs;
  private final ObjectMapper mapper;
  private final Clock clock;

  public TargetHearingInternalStageMaterializer(
      JdbcTargetE2eApiAuthority authority, TargetIntakeRuntimePins pins, AgentRunLedger ledger,
      AgentRunCommandBindingFactory bindings,
      com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec envelopes,
      MinioTargetE2eRoomCommandPayloadPublisher payloads,
      TargetE2eHearingInvocationPublisher hearingPublisher,
      TargetHearingCommandMaterialStore materialStore,
      JdbcTargetHearingAgentStageInputFactory inputs, ObjectMapper mapper, Clock clock) {
    this.authority = Objects.requireNonNull(authority); this.pins = Objects.requireNonNull(pins);
    this.ledger = Objects.requireNonNull(ledger); this.bindings = Objects.requireNonNull(bindings);
    this.envelopes = Objects.requireNonNull(envelopes); this.payloads = Objects.requireNonNull(payloads);
    this.hearingPublisher = Objects.requireNonNull(hearingPublisher); this.materialStore = Objects.requireNonNull(materialStore);
    this.inputs = Objects.requireNonNull(inputs); this.mapper = Objects.requireNonNull(mapper).copy(); this.clock = Objects.requireNonNull(clock);
  }

  public ExecuteAgentRunRequest materialize(TargetHearingFormalizationActivities.TransitionRequest transition) {
    HearingRoomStart start = transition.start(); HearingWorkflowStage stage = transition.expectedStage();
    if (!stage.requiresAgentRun()) throw new IllegalArgumentException("only an internal Hearing agent stage can materialize");
    TargetRoomEpochSelectionAuthority.Grant grant = authority.authorize(new TargetRoomEpochSelectionAuthority.Request(
        TargetRoomEpochSelectionAuthority.PROFILE, TargetRoomEpochSelectionAuthority.EXECUTION_LANE,
        start.tenantSurrogate(), start.caseId(), RoomType.HEARING,
        com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC))
        .orElseThrow(() -> new IllegalStateException("target Hearing activation authority rejected stage"));
    require(grant.activationId() != null && grant.graphKey().equals(TargetTypedRoomProtocol.GRAPH_KEY)
        && grant.graphVersion().equals(TargetTypedRoomProtocol.GRAPH_VERSION)
        && grant.checkpointSchemaVersion().equals(TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION), "activation pins");
    String identity = stable(start.tenantSurrogate() + '\n' + start.caseId() + '\n' + start.roomEpoch() + '\n' + stage.name());
    String commandId = "hearing-stage:" + stage.sequence() + ':' + identity;
    String logicalRunId = "target-hearing-run:" + identity;
    String attemptId = logicalRunId + ":1";
    JdbcTargetHearingAgentStageInputFactory.StageInput input = inputs.load(start, stage);
    ObjectNode event = mapper.createObjectNode(); event.put("schema_version", "target-e2e-hearing-stage-event.v1");
    event.put("case_id", start.caseId()); event.put("stage_sequence", stage.sequence()); event.put("operation", input.operation());
    var published = hearingPublisher.publish(commandId, input.operation(), input.request(), input.fixtureProposal(),
        input.fixtureWorkResults(), event);
    Instant now = clock.instant();
    RoomGraphCommand command = graph(commandId, logicalRunId, attemptId, start, transition.expectedProcessRevision(),
        stage, published.domainSnapshotRef(), published.eventRef(), now);
    var envelope = envelopes.wrapCommand(grant.activationId(), start.fencingToken(), command);
    Authority exchange = new Authority("target-e2e-room-exchange-authority.v1", grant.activationId(), start.fencingToken(),
        envelope.commandHash(), envelope.commandEnvelopeHash(), start.tenantSurrogate(), start.caseId(), "HEARING", start.roomEpoch(),
        command.threadId(), command.commandId(), command.logicalRunId(), command.attemptId(), command.requestHash(), command.graphKey(),
        command.graphVersion(), command.checkpointSchemaVersion(), command.processRevision(), command.stageCode(), command.stageSequence());
    hearingPublisher.bind(exchange, command, input.operation(), published);
    AgentRunCommandBindingFactory.Binding binding = bindings.bind(new AgentRunCommandBindingFactory.Context(
        start.roomId(), start.caseId() + ':' + start.roomEpoch(), "HEARING_" + input.operation().toUpperCase(), commandId), command);
    LogicalRun logical = ledger.createOrLoad(new CreateLogicalRun(logicalRunId, start.tenantSurrogate(), start.caseId(), start.roomId(),
        "HEARING_" + input.operation().toUpperCase(), commandId, AgentRunProtocol.V2, AgentRunExecutorKind.TEMPORAL_ACTIVITY,
        start.caseId() + ':' + start.roomEpoch(), RoomType.HEARING, start.roomEpoch(), transition.expectedProcessRevision(),
        start.fencingToken(), command.requestHash(), binding.logicalInputHash(), ATTEMPT_LIMIT, command.deadlineAt(), now));
    require(logical.agentRunId().equals(logicalRunId), "logical run replay");
    Attempt attempt = ledger.startNextAttempt(logicalRunId, new AttemptAllocation(1, command, binding), now);
    require(attempt.attemptId().equals(attemptId) && attempt.attemptNo() == 1, "attempt replay");
    ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(ExecuteAgentRunRequest.SCHEMA_VERSION, logicalRunId, 1, ATTEMPT_LIMIT,
        "agent-stream.v2", binding.logicalInputHash(), null, false, 0, command);
    CommandAdmission admission = new CommandAdmission(grant.activationId(), grant.activationManifestHash(),
        grant.isolatedDomainDbBindingHash(), start.tenantSurrogate(), start.caseId(), commandId,
        envelope.commandHash(), envelope.commandEnvelopeHash(), start.roomEpoch(), start.fencingToken());
    materialStore.append(new TargetHearingCommandMaterial(TargetHearingCommandMaterial.SCHEMA_VERSION, admission, request,
        envelope.commandHash(), envelope.commandEnvelopeHash()));
    return request;
  }

  private RoomGraphCommand graph(String commandId, String logicalRunId, String attemptId, HearingRoomStart start,
      long processRevision, HearingWorkflowStage stage, RoomGraphCommand.SnapshotRef domain,
      RoomGraphCommand.SnapshotRef event, Instant now) {
    RoomGraphCommand.InvocationContext invocation = new RoomGraphCommand.InvocationContext(pins.agentProfileId(),
        pins.promptVersion(), pins.modelProfileId(), "target-e2e-room-proposal-source.v1", pins.policyVersion(),
        pins.guardrailVersion(), List.of(), pins.envelopeKeyId(), "target-hearing-nonce:" + stable(commandId));
    RoomGraphCommand provisional = new RoomGraphCommand("room-graph-command.v1", commandId, logicalRunId, attemptId,
        start.tenantSurrogate(), start.caseId(), RoomType.HEARING, start.roomEpoch(), TargetTypedRoomProtocol.GRAPH_KEY,
        TargetTypedRoomProtocol.GRAPH_VERSION, TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION, "grt.v1." + stable(start.caseId()),
        new RoomGraphCommand.ActorScope("hearing-control", ActorRole.SYSTEM, Audience.SYSTEM, List.of("hearing:" + stage.name())),
        processRevision, stage.name(), stage.sequence(), domain, event, invocation,
        new RoomGraphCommand.RetryBudget(2, ATTEMPT_LIMIT, 1),
        start.hearingDeadlineAt().isBefore(now.plusSeconds(300)) ? start.hearingDeadlineAt() : now.plusSeconds(300),
        "00-" + stable(commandId).substring(0, 32) + "-0000000000000001-01", "0".repeat(64));
    ObjectNode body = mapper.valueToTree(provisional); body.remove("request_hash"); String requestHash = ContractJson.sha256Hex(body);
    return new RoomGraphCommand(provisional.schemaVersion(), provisional.commandId(), provisional.logicalRunId(), provisional.attemptId(),
        provisional.tenantSurrogate(), provisional.caseId(), provisional.roomType(), provisional.roomEpoch(), provisional.graphKey(),
        provisional.graphVersion(), provisional.checkpointSchemaVersion(), provisional.threadId(), provisional.actorScope(), provisional.processRevision(),
        provisional.stageCode(), provisional.stageSequence(), provisional.domainSnapshotRef(), provisional.eventRef(), provisional.invocationContext(),
        provisional.retryBudget(), provisional.deadlineAt(), provisional.traceparent(), requestHash);
  }
  private static String stable(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""); }
  private static void require(boolean value, String label) { if (!value) throw new IllegalStateException("target Hearing " + label + " drifted"); }
}

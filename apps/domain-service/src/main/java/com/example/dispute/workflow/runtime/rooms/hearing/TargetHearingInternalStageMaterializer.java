package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunStreamProjection;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomExchangeContract.Authority;
import com.example.dispute.workflow.runtime.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.runtime.ingress.rooms.MinioProductionRoomCommandPayloadPublisher;
import com.example.dispute.workflow.runtime.ingress.rooms.ProductionHearingInvocationPublisher;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionApiAuthority;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import com.example.dispute.workflow.runtime.temporal.TargetRoomEpochSelectionAuthority;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
  private static final int MAX_HEARING_EVIDENCE_ITEMS = 100;
  private final JdbcProductionApiAuthority authority;
  private final TargetIntakeRuntimePins pins;
  private final AgentRunLedger ledger;
  private final AgentRunCommandBindingFactory bindings;
  private final com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec envelopes;
  private final MinioProductionRoomCommandPayloadPublisher payloads;
  private final ProductionHearingInvocationPublisher hearingPublisher;
  private final TargetHearingCommandMaterialStore materialStore;
  private final JdbcTargetHearingAgentStageInputFactory inputs;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final TargetHearingAgentRunStartedPublisher runStartedPublisher;

  public TargetHearingInternalStageMaterializer(
      JdbcProductionApiAuthority authority, TargetIntakeRuntimePins pins, AgentRunLedger ledger,
      AgentRunCommandBindingFactory bindings,
      com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec envelopes,
      MinioProductionRoomCommandPayloadPublisher payloads,
      ProductionHearingInvocationPublisher hearingPublisher,
      TargetHearingCommandMaterialStore materialStore,
      JdbcTargetHearingAgentStageInputFactory inputs, ObjectMapper mapper, Clock clock) {
    this(
        authority,
        pins,
        ledger,
        bindings,
        envelopes,
        payloads,
        hearingPublisher,
        materialStore,
        inputs,
        mapper,
        clock,
        TargetHearingAgentRunStartedPublisher.NOOP);
  }

  public TargetHearingInternalStageMaterializer(
      JdbcProductionApiAuthority authority, TargetIntakeRuntimePins pins, AgentRunLedger ledger,
      AgentRunCommandBindingFactory bindings,
      com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec envelopes,
      MinioProductionRoomCommandPayloadPublisher payloads,
      ProductionHearingInvocationPublisher hearingPublisher,
      TargetHearingCommandMaterialStore materialStore,
      JdbcTargetHearingAgentStageInputFactory inputs, ObjectMapper mapper, Clock clock,
      TargetHearingAgentRunStartedPublisher runStartedPublisher) {
    this.authority = Objects.requireNonNull(authority); this.pins = Objects.requireNonNull(pins);
    this.ledger = Objects.requireNonNull(ledger); this.bindings = Objects.requireNonNull(bindings);
    this.envelopes = Objects.requireNonNull(envelopes); this.payloads = Objects.requireNonNull(payloads);
    this.hearingPublisher = Objects.requireNonNull(hearingPublisher); this.materialStore = Objects.requireNonNull(materialStore);
    this.inputs = Objects.requireNonNull(inputs); this.mapper = Objects.requireNonNull(mapper).copy(); this.clock = Objects.requireNonNull(clock);
    this.runStartedPublisher = Objects.requireNonNull(runStartedPublisher);
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
    StageIdentity stageIdentity = materializeIdentity(start, stage);
    String commandId = stageIdentity.commandId();
    String logicalRunId = stageIdentity.logicalRunId();
    String attemptId = stageIdentity.attemptId();
    JdbcTargetHearingAgentStageInputFactory.StageInput input = inputs.load(start, stage);
    require(stage.agentOperation().equals(input.operation()), "stage operation");
    int providerAttemptBudget = providerAttemptBudget(input.operation(), input.request());
    ObjectNode event = mapper.createObjectNode(); event.put("schema_version", "production-runtime-hearing-stage-event.v1");
    event.put("case_id", start.caseId()); event.put("stage_sequence", stage.sequence()); event.put("operation", input.operation());
    var published = hearingPublisher.publish(commandId, input.operation(), input.sharedBarrierReceiptHash(),
        input.request(), event);
    AuthorityTimes authorityTimes = authorityTimes(clock.instant(), start.hearingDeadlineAt());
    Instant now = authorityTimes.startedAt();
    RoomGraphCommand command = graph(commandId, logicalRunId, attemptId, stageIdentity.threadId(), start, transition.expectedProcessRevision(),
        stage, published.domainSnapshotRef(), published.eventRef(), providerAttemptBudget,
        authorityTimes.deadlineAt());
    var envelope = envelopes.wrapCommand(grant.activationId(), start.fencingToken(), command);
    Authority exchange = new Authority("production-runtime-room-exchange-authority.v1", grant.activationId(), start.fencingToken(),
        envelope.commandHash(), envelope.commandEnvelopeHash(), start.tenantSurrogate(), start.caseId(), "HEARING", start.roomEpoch(),
        command.threadId(), command.commandId(), command.logicalRunId(), command.attemptId(), command.requestHash(), command.graphKey(),
        command.graphVersion(), command.checkpointSchemaVersion(), command.processRevision(), command.stageCode(), command.stageSequence());
    hearingPublisher.bind(exchange, command, input.operation(), published);
    String roomEpochId = authoritativeRoomEpochId(start);
    AgentRunCommandBindingFactory.Binding binding = bindings.bind(new AgentRunCommandBindingFactory.Context(
        start.roomId(), roomEpochId, "HEARING_" + input.operation().toUpperCase(), commandId), command);
    LogicalRun logical = ledger.createOrLoad(new CreateLogicalRun(logicalRunId, start.tenantSurrogate(), start.caseId(), start.roomId(),
        "HEARING_" + input.operation().toUpperCase(), commandId, AgentRunProtocol.V3, AgentRunExecutorKind.TEMPORAL_ACTIVITY,
        roomEpochId, RoomType.HEARING, start.roomEpoch(), transition.expectedProcessRevision(),
        start.fencingToken(), command.requestHash(), binding.logicalInputHash(), ATTEMPT_LIMIT, command.deadlineAt(), now,
        AgentRunStreamProjection.CASE_PARTICIPANTS));
    require(logical.agentRunId().equals(logicalRunId), "logical run replay");
    Attempt attempt = ledger.startNextAttempt(logicalRunId, new AttemptAllocation(1, command, binding), now);
    require(attempt.attemptId().equals(attemptId) && attempt.attemptNo() == 1, "attempt replay");
    ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(ExecuteAgentRunRequest.SCHEMA_VERSION, logicalRunId, 1, ATTEMPT_LIMIT,
        "agent-stream.v3", binding.logicalInputHash(), null, false, 0, command);
    CommandAdmission admission = new CommandAdmission(grant.activationId(), grant.activationManifestHash(),
        grant.isolatedDomainDbBindingHash(), start.tenantSurrogate(), start.caseId(), commandId,
        envelope.commandHash(), envelope.commandEnvelopeHash(), start.roomEpoch(), start.fencingToken());
    materialStore.append(commandMaterial(start, admission, request,
        envelope.commandHash(), envelope.commandEnvelopeHash()));
    runStartedPublisher.publish(
        new TargetHearingAgentRunStartedPublisher.Event(
            start.tenantSurrogate(),
            start.caseId(),
            start.roomId(),
            start.roomEpoch(),
            start.fencingToken(),
            start.flowInstanceId(),
            stage.name(),
            stage.sequence(),
            "HEARING_" + input.operation().toUpperCase(java.util.Locale.ROOT),
            commandId,
            logicalRunId,
            attemptId,
            logical.status(),
            attempt.startedAt()));
    return request;
  }

  static TargetHearingCommandMaterial commandMaterial(
      HearingRoomStart start, CommandAdmission admission, ExecuteAgentRunRequest request,
      String commandHash, String commandEnvelopeHash) {
    Objects.requireNonNull(start, "start");
    var partyAuthority = new TargetHearingCommandMaterial.PartyStageAuthority(
        TargetHearingCommandMaterial.PartyStageAuthority.SCHEMA_VERSION,
        start.tenantSurrogate(), start.caseId(), start.roomEpoch(), start.fencingToken(),
        start.partyStageWindowSeconds(), start.hearingDeadlineAt());
    return new TargetHearingCommandMaterial(TargetHearingCommandMaterial.SCHEMA_VERSION,
        admission, request, partyAuthority, commandHash, commandEnvelopeHash);
  }

  private RoomGraphCommand graph(String commandId, String logicalRunId, String attemptId, String threadId, HearingRoomStart start,
      long processRevision, HearingWorkflowStage stage, RoomGraphCommand.SnapshotRef domain,
      RoomGraphCommand.SnapshotRef event, int providerAttemptBudget, Instant deadlineAt) {
    RoomGraphCommand.InvocationContext invocation = new RoomGraphCommand.InvocationContext(pins.agentProfileId(),
        pins.promptVersion(), pins.modelProfileId(), "production-runtime-room-proposal-source.v2", pins.policyVersion(),
        pins.guardrailVersion(), List.of(), pins.envelopeKeyId(), "target-hearing-nonce:" + stable(commandId));
    RoomGraphCommand provisional = new RoomGraphCommand("room-graph-command.v1", commandId, logicalRunId, attemptId,
        start.tenantSurrogate(), start.caseId(), RoomType.HEARING, start.roomEpoch(), TargetTypedRoomProtocol.GRAPH_KEY,
        TargetTypedRoomProtocol.GRAPH_VERSION, TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION, threadId,
        new RoomGraphCommand.ActorScope("hearing-control", ActorRole.SYSTEM, Audience.SYSTEM, List.of("hearing:" + stage.name())),
        processRevision, stage.name(), stage.sequence(), domain, event, invocation,
        new RoomGraphCommand.RetryBudget(providerAttemptBudget, ATTEMPT_LIMIT, 1),
        deadlineAt,
        "00-" + stable(commandId).substring(0, 32) + "-0000000000000001-01", "0".repeat(64));
    ObjectNode body = mapper.valueToTree(provisional); body.remove("request_hash"); String requestHash = ContractJson.sha256Hex(body);
    return new RoomGraphCommand(provisional.schemaVersion(), provisional.commandId(), provisional.logicalRunId(), provisional.attemptId(),
        provisional.tenantSurrogate(), provisional.caseId(), provisional.roomType(), provisional.roomEpoch(), provisional.graphKey(),
        provisional.graphVersion(), provisional.checkpointSchemaVersion(), provisional.threadId(), provisional.actorScope(), provisional.processRevision(),
        provisional.stageCode(), provisional.stageSequence(), provisional.domainSnapshotRef(), provisional.eventRef(), provisional.invocationContext(),
        provisional.retryBudget(), provisional.deadlineAt(), provisional.traceparent(), requestHash);
  }
  static int providerAttemptBudget(String operation, ObjectNode request) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(request, "request");
    if (!"evidence_synthesis".equals(operation)) {
      return RoomGraphCommand.MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT;
    }
    JsonNode partyBatches = request.get("party_batches");
    if (partyBatches == null || !partyBatches.isArray() || partyBatches.size() != 2) {
      throw new IllegalStateException("target Hearing evidence synthesis party batches are invalid");
    }
    int evidenceItemCount = 0;
    for (JsonNode partyBatch : partyBatches) {
      JsonNode evidence = partyBatch.get("evidence");
      if (!partyBatch.isObject() || evidence == null || !evidence.isArray()) {
        throw new IllegalStateException("target Hearing evidence synthesis batch evidence is invalid");
      }
      evidenceItemCount = Math.addExact(evidenceItemCount, evidence.size());
      if (evidenceItemCount > MAX_HEARING_EVIDENCE_ITEMS) {
        throw new IllegalStateException("target Hearing evidence synthesis evidence count is invalid");
      }
    }
    return Math.multiplyExact(
        RoomGraphCommand.MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT,
        Math.addExact(evidenceItemCount, 1));
  }
  static AuthorityTimes authorityTimes(Instant startedAt, Instant hearingDeadlineAt) {
    Objects.requireNonNull(startedAt, "startedAt"); Objects.requireNonNull(hearingDeadlineAt, "hearingDeadlineAt");
    Instant canonicalStartedAt = startedAt.truncatedTo(ChronoUnit.MICROS);
    Instant canonicalHearingDeadline = hearingDeadlineAt.truncatedTo(ChronoUnit.MICROS);
    Instant generatedDeadline = canonicalStartedAt.plusSeconds(300);
    return new AuthorityTimes(canonicalStartedAt,
        canonicalHearingDeadline.isBefore(generatedDeadline) ? canonicalHearingDeadline : generatedDeadline);
  }
  record AuthorityTimes(Instant startedAt, Instant deadlineAt) {
    AuthorityTimes { Objects.requireNonNull(startedAt); Objects.requireNonNull(deadlineAt); }
  }
  static String authoritativeRoomEpochId(HearingRoomStart start) {
    Objects.requireNonNull(start, "start");
    return start.epochId();
  }
  static StageIdentity materializeIdentity(HearingRoomStart start, HearingWorkflowStage stage) {
    Objects.requireNonNull(start, "start"); Objects.requireNonNull(stage, "stage");
    if (!stage.requiresAgentRun()) throw new IllegalArgumentException("only an internal Hearing agent stage can materialize");
    String identity = stable(start.tenantSurrogate() + '\n' + start.caseId() + '\n' + start.roomEpoch() + '\n' + stage.name());
    String logicalRunId = "target-hearing-run:" + identity;
    return new StageIdentity(
        "hearing-stage:" + stage.sequence() + ':' + identity,
        logicalRunId,
        logicalRunId + ":1",
        "grt.v1." + identity);
  }
  record StageIdentity(String commandId, String logicalRunId, String attemptId, String threadId) {
    StageIdentity {
      Objects.requireNonNull(commandId); Objects.requireNonNull(logicalRunId);
      Objects.requireNonNull(attemptId); Objects.requireNonNull(threadId);
    }
  }
  private static String stable(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""); }
  private static void require(boolean value, String label) { if (!value) throw new IllegalStateException("target Hearing " + label + " drifted"); }
}

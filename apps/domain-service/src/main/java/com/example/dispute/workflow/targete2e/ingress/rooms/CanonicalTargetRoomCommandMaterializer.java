package com.example.dispute.workflow.targete2e.ingress.rooms;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.common.trace.W3cTraceContext;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandRequestHasher;
import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphCommandEnvelope;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCompletionCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Materializes only target Evidence, Hearing and Review graph commands. The normal CaseCommand
 * admission remains the business authority; this class only produces the immutable worker input.
 */
public final class CanonicalTargetRoomCommandMaterializer implements TargetRoomCommandIngress {
    private static final int ATTEMPT_LIMIT = 3;
    private static final String OUTPUT_SCHEMA = "target-e2e-room-proposal-source.v2";

    private final CaseRoomEpochRepository epochs;
    private final JdbcTargetE2eApiAuthority authority;
    private final TargetIntakeRuntimePins pins;
    private final AgentRunLedger ledger;
    private final AgentRunCommandBindingFactory bindings;
    private final TargetE2EGraphEnvelopeCodec envelopes;
    private final MinioTargetE2eRoomCommandPayloadPublisher payloads;
    private final TargetE2eRoomObjectIndex objectIndex;
    private final TargetE2eEvidenceManifestPublisher evidenceManifestPublisher;
    private final TargetE2eEvidenceTurnInvocationPublisher evidenceTurnInvocationPublisher;
    private final TargetE2eReviewInvocationPublisher reviewInvocationPublisher;
    private final JdbcTargetReviewInvocationFactsLoader reviewFacts;
    private final TargetEvidenceCommandMaterialStore evidence;
    private final TargetEvidenceCompletionCommandMaterialStore evidenceCompletion;
    private final TargetHearingCommandMaterialStore hearing;
    private final TargetReviewCommandMaterialStore review;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CanonicalTargetRoomCommandMaterializer(
            CaseRoomEpochRepository epochs,
            JdbcTargetE2eApiAuthority authority,
            TargetIntakeRuntimePins pins,
            AgentRunLedger ledger,
            AgentRunCommandBindingFactory bindings,
            TargetE2EGraphEnvelopeCodec envelopes,
            MinioTargetE2eRoomCommandPayloadPublisher payloads,
            TargetE2eRoomObjectIndex objectIndex,
            TargetE2eEvidenceManifestPublisher evidenceManifestPublisher,
            TargetE2eEvidenceTurnInvocationPublisher evidenceTurnInvocationPublisher,
            TargetE2eReviewInvocationPublisher reviewInvocationPublisher,
            JdbcTargetReviewInvocationFactsLoader reviewFacts,
            TargetEvidenceCommandMaterialStore evidence,
            TargetEvidenceCompletionCommandMaterialStore evidenceCompletion,
            TargetHearingCommandMaterialStore hearing,
            TargetReviewCommandMaterialStore review,
            ObjectMapper mapper,
            Clock clock) {
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.objectIndex = Objects.requireNonNull(objectIndex, "objectIndex");
        this.evidenceManifestPublisher = Objects.requireNonNull(evidenceManifestPublisher, "evidenceManifestPublisher");
        this.evidenceTurnInvocationPublisher = Objects.requireNonNull(
                evidenceTurnInvocationPublisher, "evidenceTurnInvocationPublisher");
        this.reviewInvocationPublisher = Objects.requireNonNull(reviewInvocationPublisher, "reviewInvocationPublisher");
        this.reviewFacts = Objects.requireNonNull(reviewFacts, "reviewFacts");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.evidenceCompletion = Objects.requireNonNull(evidenceCompletion, "evidenceCompletion");
        this.hearing = Objects.requireNonNull(hearing, "hearing");
        this.review = Objects.requireNonNull(review, "review");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void materialize(
            String caseId, String commandId, AcceptCaseCommand command, AuthenticatedActor actor, String traceId) {
        materialize(caseId, commandId, command, actor, traceId, null);
    }

    @Override
    public EvidenceSubmissionRunReceipt materializeEvidenceSubmission(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId,
            EvidenceAgentTurnCommand evidenceAgentTurnCommand) {
        String logicalRunId = materialize(
                caseId,
                commandId,
                command,
                actor,
                traceId,
                Objects.requireNonNull(evidenceAgentTurnCommand, "evidenceAgentTurnCommand"));
        if (logicalRunId == null || logicalRunId.isBlank()) {
            throw new IllegalStateException(
                    "target Evidence submission did not materialize a logical run");
        }
        return new EvidenceSubmissionRunReceipt(logicalRunId);
    }

    @Override
    public EvidenceOpeningRunReceipt materializeEvidenceOpening(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId,
            EvidenceAgentTurnCommand evidenceAgentTurnCommand) {
        if (command.commandType() != CommandType.EVIDENCE_OPENING
                || command.roomType() != RoomType.EVIDENCE) {
            throw new IllegalArgumentException(
                    "target Evidence opening requires the explicit opening discriminator");
        }
        String logicalRunId = materialize(
                caseId,
                commandId,
                command,
                actor,
                traceId,
                Objects.requireNonNull(evidenceAgentTurnCommand, "evidenceAgentTurnCommand"));
        if (logicalRunId == null || logicalRunId.isBlank()) {
            throw new IllegalStateException(
                    "target Evidence opening did not materialize a logical run");
        }
        return new EvidenceOpeningRunReceipt(logicalRunId, logicalRunId + ":1");
    }

    private String materialize(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId,
            EvidenceAgentTurnCommand evidenceAgentTurnCommand) {
        if (!isMaterializedCommand(command.commandType()) || command.roomType() == RoomType.HEARING) return null;
        if ((command.commandType() == CommandType.EVIDENCE_SUBMIT
                        || command.commandType() == CommandType.EVIDENCE_OPENING)
                && evidenceAgentTurnCommand == null) {
            throw new IllegalArgumentException(
                    "target Evidence graph command requires formal Evidence turn authority");
        }
        if (evidenceAgentTurnCommand != null
                && (command.commandType() != CommandType.EVIDENCE_SUBMIT
                        && command.commandType() != CommandType.EVIDENCE_OPENING
                        || command.roomType() != RoomType.EVIDENCE)) {
            throw new IllegalArgumentException(
                    "formal Evidence turn authority is only valid for an Evidence graph command");
        }
        CaseRoomEpochEntity epoch = epochs.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                caseId, command.roomType(), command.roomEpoch()).orElse(null);
        if (epoch == null || !isTargetEpoch(epoch)) return null;
        if (epoch.getProcessRevision() != command.expectedProcessRevision()) {
            throw new IllegalStateException("target room command process revision is stale");
        }
        TargetRoomEpochSelectionAuthority.Grant grant = authority.authorize(
                new TargetRoomEpochSelectionAuthority.Request(TargetRoomEpochSelectionAuthority.PROFILE,
                        TargetRoomEpochSelectionAuthority.EXECUTION_LANE, epoch.getTenantSurrogate(), caseId,
                        command.roomType(), TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC))
                .orElseThrow(() -> new IllegalStateException("target room activation authority rejected command"));
        requireGrant(epoch, grant);
        if (command.commandType() == CommandType.PARTY_EVIDENCE_COMPLETE) {
            materializeEvidenceCompletion(caseId, commandId, command, actor, traceId, epoch, grant);
            return null;
        }
        Instant now = clock.instant();
        String identity = stableToken(epoch.getTenantSurrogate() + "\n" + caseId + "\n" + commandId);
        String logicalRunId = "target-" + command.roomType().name().toLowerCase() + "-run:" + identity;
        String attemptId = logicalRunId + ":1";
        RoomGraphCommand.SnapshotRef eventRef = new RoomGraphCommand.SnapshotRef(
                "case-command:" + commandId, command.payloadRef().schemaVersion(), command.payloadRef().uri(),
                command.payloadRef().sha256(), command.payloadRef().sizeBytes());
        MinioTargetE2eRoomCommandPayloadPublisher.PublishedObject domain;
        JdbcTargetReviewInvocationFactsLoader.Facts loadedReview = null;
        TargetE2eEvidenceTurnInvocationPublisher.Published evidencePublished = null;
        if (command.roomType() == RoomType.EVIDENCE) {
            RoomGraphCommand provisional = graph(
                    commandId,
                    logicalRunId,
                    attemptId,
                    epoch,
                    command,
                    actor,
                    traceId,
                    new RoomGraphCommand.SnapshotRef(
                            "target-evidence-turn-invocation:" + commandId,
                            TargetE2eEvidenceTurnInvocationPublisher.SCHEMA_VERSION,
                            "urn:target-e2e:object:target-evidence-turn-invocation:" + commandId,
                            "0".repeat(64),
                            1),
                    eventRef,
                    now);
            evidencePublished = evidenceTurnInvocationPublisher.publish(
                    provisional,
                    epoch.getFencingToken(),
                    command.commandType(),
                    evidenceAgentTurnCommand);
            domain = evidencePublished.invocation();
        } else if (command.roomType() == RoomType.REVIEW) {
            loadedReview = reviewFacts.load(graph(commandId, logicalRunId, attemptId, epoch, command, actor, traceId,
                    new RoomGraphCommand.SnapshotRef("review-invocation:" + commandId, "target-e2e-review-invocation.v1",
                        "urn:target-e2e:object:review-invocation:" + commandId, "0".repeat(64), 1), null, now), epoch.getFencingToken());
            domain = null;
        } else {
            domain = payloads.publish("target-room-domain:" + identity, command.roomType().name(), commandId, "DOMAIN_SNAPSHOT",
                    source(command, actor, epoch, "DOMAIN_SNAPSHOT"));
        }
        // The command payload is the immutable event authority. Do not wrap or republish it.
        RoomGraphCommand skeleton = graph(commandId, logicalRunId, attemptId, epoch, command, actor, traceId,
                new RoomGraphCommand.SnapshotRef("review-invocation:" + commandId, "target-e2e-review-invocation.v1",
                    "urn:target-e2e:object:review-invocation:" + commandId, "0".repeat(64), 1), eventRef, now);
        if (loadedReview != null) domain = reviewInvocationPublisher.publish(skeleton, loadedReview);
        RoomGraphCommand graph = graph(commandId, logicalRunId, attemptId, epoch, command, actor, traceId,
                domain.reference(), eventRef, now);
        TargetE2EGraphCommandEnvelope envelope = envelopes.wrapCommand(grant.activationId(), epoch.getFencingToken(), graph);
        Authority exchangeAuthority = authority(grant.activationId(), epoch, graph, envelope.commandHash(), envelope.commandEnvelopeHash());
        if (command.roomType() == RoomType.REVIEW) reviewInvocationPublisher.bind(exchangeAuthority, graph, domain);
        else if (evidencePublished == null) payloads.bind(exchangeAuthority, graph, domain, TargetE2eRoomObjectIndex.Kind.COMMAND_INPUT);
        else evidenceTurnInvocationPublisher.bind(exchangeAuthority, graph, evidencePublished);
        AgentRunCommandBindingFactory.Binding binding = bindings.bind(new AgentRunCommandBindingFactory.Context(
                epoch.getRoomId(), caseId + ":" + epoch.getRoomEpoch(), command.commandType().name(), commandId), graph);
        LogicalRun logical = ledger.createOrLoad(new CreateLogicalRun(logicalRunId, epoch.getTenantSurrogate(), caseId,
                epoch.getRoomId(), command.commandType().name(), commandId, AgentRunProtocol.V3,
                AgentRunExecutorKind.TEMPORAL_ACTIVITY, caseId + ":" + epoch.getRoomEpoch(), command.roomType(),
                epoch.getRoomEpoch(), epoch.getProcessRevision(), epoch.getFencingToken(), graph.requestHash(),
                binding.logicalInputHash(), ATTEMPT_LIMIT, command.deadlineAt(), now));
        if (!logical.agentRunId().equals(logicalRunId)) throw new IllegalStateException("target AgentRun replay drifted");
        Attempt attempt = ledger.startNextAttempt(logicalRunId, new AttemptAllocation(1, graph, binding), now);
        if (!attempt.attemptId().equals(attemptId) || attempt.attemptNo() != 1) {
            throw new IllegalStateException("target AgentRun attempt replay drifted");
        }
        ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(ExecuteAgentRunRequest.SCHEMA_VERSION,
                logicalRunId, 1, ATTEMPT_LIMIT, "agent-stream.v3", binding.logicalInputHash(), null, false, 0, graph);
        ActorRef caseCommandActor = new ActorRef(
                graph.actorScope().actorId(), graph.actorScope().actorRole(), graph.actorScope().capabilities());
        String caseCommandRequestHash = CaseCommandRequestHasher.hash(
                epoch.getTenantSurrogate(), caseId, commandId, command, caseCommandActor);
        CommandAdmission admission = new CommandAdmission(grant.activationId(), grant.activationManifestHash(),
                grant.isolatedDomainDbBindingHash(), epoch.getTenantSurrogate(), caseId, commandId,
                envelope.commandHash(), envelope.commandEnvelopeHash(), epoch.getRoomEpoch(), epoch.getFencingToken());
        append(
                command,
                admission,
                request,
                envelope,
                epoch,
                caseCommandRequestHash,
                evidenceAgentTurnCommand);
        return logical.agentRunId();
    }

    private void materializeEvidenceCompletion(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId,
            CaseRoomEpochEntity epoch,
            TargetRoomEpochSelectionAuthority.Grant grant) {
        if (command.roomType() != RoomType.EVIDENCE) {
            throw new IllegalArgumentException("party completion must target Evidence");
        }
        ActorRef actorRef = new ActorRef(
                actor.actorId(), mapRole(actor.role()),
                List.of("case:" + caseId + ":command:" + command.commandType().name()));
        String requestHash = CaseCommandRequestHasher.hash(
                epoch.getTenantSurrogate(), caseId, commandId, command, actorRef);
        TargetEvidenceCompletionCommandMaterial material =
                TargetEvidenceCompletionCommandMaterial.create(
                        grant.activationId(), grant.activationManifestHash(),
                        grant.isolatedDomainDbBindingHash(), epoch.getTenantSurrogate(), caseId,
                        commandId, epoch.getRoomEpoch(), epoch.getFencingToken(),
                        epoch.getProcessRevision(), epoch.getRoomRevision(), actorRef,
                        command.payloadRef(), command.deadlineAt(), expectedTraceId(traceId), requestHash);
        CommandAdmission admission = new CommandAdmission(
                grant.activationId(), grant.activationManifestHash(),
                grant.isolatedDomainDbBindingHash(), epoch.getTenantSurrogate(), caseId, commandId,
                material.commandHash(), material.commandEnvelopeHash(), epoch.getRoomEpoch(),
                epoch.getFencingToken());
        evidenceCompletion.append(admission, material);
    }

    private void append(AcceptCaseCommand command, CommandAdmission admission, ExecuteAgentRunRequest request,
            TargetE2EGraphCommandEnvelope envelope, CaseRoomEpochEntity epoch,
            String caseCommandRequestHash,
            EvidenceAgentTurnCommand evidenceAgentTurnCommand) {
        switch (command.roomType()) {
            case EVIDENCE -> evidence.append(admission, new TargetEvidenceCommandMaterial(
                    TargetEvidenceCommandMaterial.SCHEMA_VERSION, TargetEvidenceCommandMaterial.TARGET_LANE,
                    admission.activationId(), admission.manifestHash(), epoch.getFencingToken(),
                    epoch.getProcessRevision(), epoch.getRoomRevision(), envelope.commandHash(),
                    envelope.commandEnvelopeHash(), caseCommandRequestHash, request,
                    evidenceAgentTurnCommand));
            case HEARING -> hearing.append(new TargetHearingCommandMaterial(
                    TargetHearingCommandMaterial.SCHEMA_VERSION, admission, request,
                    envelope.commandHash(), envelope.commandEnvelopeHash()));
            case REVIEW -> review.append(admission, new TargetReviewCommandMaterial(
                    TargetReviewCommandMaterial.SCHEMA_VERSION, TargetReviewCommandMaterial.TARGET_LANE,
                    admission.activationId(), admission.manifestHash(), epoch.getFencingToken(),
                    epoch.getProcessRevision(), epoch.getRoomRevision(), envelope.commandHash(),
                    envelope.commandEnvelopeHash(), request));
            default -> throw new IllegalStateException("target room materialization is not supported for Intake");
        }
    }

    private RoomGraphCommand graph(String commandId, String logicalRunId, String attemptId, CaseRoomEpochEntity epoch,
            AcceptCaseCommand command, AuthenticatedActor actor, String traceId, RoomGraphCommand.SnapshotRef domain,
            RoomGraphCommand.SnapshotRef event, Instant now) {
        RoomGraphCommand.InvocationContext invocation = new RoomGraphCommand.InvocationContext(
                pins.agentProfileId(), pins.promptVersion(), pins.modelProfileId(), OUTPUT_SCHEMA,
                pins.policyVersion(), pins.guardrailVersion(), List.of(), pins.envelopeKeyId(),
                "target-room-nonce:" + stableToken(commandId));
        RoomGraphCommand provisional = new RoomGraphCommand("room-graph-command.v1", commandId, logicalRunId, attemptId,
                epoch.getTenantSurrogate(), epoch.getCaseId(), command.roomType(), epoch.getRoomEpoch(),
                epoch.getGraphKey(), epoch.getGraphVersion(), epoch.getCheckpointSchemaVersion(),
                graphThreadId(epoch, actor, command.roomType(), commandId),
                new RoomGraphCommand.ActorScope(actor.actorId(), mapRole(actor.role()), audience(actor.role()),
                        command.roomType() == RoomType.EVIDENCE
                                ? List.of(
                                        "case:" + epoch.getCaseId() + ":command:EVIDENCE_OPENING",
                                        "case:" + epoch.getCaseId() + ":command:EVIDENCE_SUBMIT")
                                : List.of("case:" + epoch.getCaseId() + ":command:" + command.commandType().name())),
                epoch.getProcessRevision(), graphStageCode(command.roomType()), epoch.getProcessRevision(), domain, event,
                invocation, new RoomGraphCommand.RetryBudget(2, 3, 1), command.deadlineAt(),
                traceparent(expectedTraceId(traceId)),
                "0".repeat(64));
        String requestHash = envelopes.commandRequestHash(provisional);
        return new RoomGraphCommand(provisional.schemaVersion(), provisional.commandId(), provisional.logicalRunId(),
                provisional.attemptId(), provisional.tenantSurrogate(), provisional.caseId(), provisional.roomType(),
                provisional.roomEpoch(), provisional.graphKey(), provisional.graphVersion(), provisional.checkpointSchemaVersion(),
                provisional.threadId(), provisional.actorScope(), provisional.processRevision(), provisional.stageCode(),
                provisional.stageSequence(), provisional.domainSnapshotRef(), provisional.eventRef(), provisional.invocationContext(),
                provisional.retryBudget(), provisional.deadlineAt(), provisional.traceparent(), requestHash);
    }

    private JsonNode source(AcceptCaseCommand command, AuthenticatedActor actor, CaseRoomEpochEntity epoch, String objectRole) {
        ObjectNode value = mapper.createObjectNode();
        value.put("schema_version", "target-e2e-room-command-source.v1");
        value.put("actor_id", actor.actorId());
        value.put("actor_role", actor.role().name());
        value.put("room_epoch", epoch.getRoomEpoch());
        value.put("command_type", command.commandType().name());
        value.put("object_role", objectRole);
        ObjectNode payload = value.putObject("payload_ref");
        payload.put("schema_version", command.payloadRef().schemaVersion());
        payload.put("uri", command.payloadRef().uri());
        payload.put("sha256", command.payloadRef().sha256());
        payload.put("size_bytes", command.payloadRef().sizeBytes());
        return value;
    }

    private static boolean isGraphCommand(CommandType type) {
        return type == CommandType.EVIDENCE_OPENING
                || type == CommandType.EVIDENCE_SUBMIT
                || type == CommandType.HEARING_ANSWER_BUNDLE
                || type == CommandType.HEARING_EVIDENCE_BATCH || type == CommandType.REVIEW_DECISION;
    }
    static boolean isMaterializedCommand(CommandType type) {
        return type == CommandType.PARTY_EVIDENCE_COMPLETE || isGraphCommand(type);
    }
    private static String graphStageCode(RoomType roomType) {
        return switch (roomType) {
            case EVIDENCE -> "EVIDENCE_SEAL";
            case REVIEW -> "REVIEW_OUTCOME";
            default -> throw new IllegalArgumentException(
                    "target browser Graph stage is unsupported for " + roomType);
        };
    }
    private static Authority authority(String activationId, CaseRoomEpochEntity epoch, RoomGraphCommand graph,
            String commandHash, String commandEnvelopeHash) {
        return new Authority("target-e2e-room-exchange-authority.v1", activationId, epoch.getFencingToken(),
                commandHash, commandEnvelopeHash, graph.tenantSurrogate(), graph.caseId(), graph.roomType().name(),
                graph.roomEpoch(), graph.threadId(), graph.commandId(), graph.logicalRunId(), graph.attemptId(),
                graph.requestHash(), graph.graphKey(), graph.graphVersion(), graph.checkpointSchemaVersion(),
                graph.processRevision(), graph.stageCode(), graph.stageSequence());
    }
    private static String evidenceThreadId(
            CaseRoomEpochEntity epoch, AuthenticatedActor actor, String commandId) {
        return "grt.v1."
                + stableToken(
                        "target-evidence-command-thread.v1\n"
                                + epoch.getTenantSurrogate()
                                + "\n"
                                + epoch.getCaseId()
                                + "\n"
                                + epoch.getRoomEpoch()
                                + "\n"
                                + actor.actorId()
                                + "\n"
                                + actor.role().name()
                                + "\n"
                                + commandId);
    }
    static String graphThreadId(
            CaseRoomEpochEntity epoch,
            AuthenticatedActor actor,
            RoomType roomType,
            String commandId) {
        return switch (roomType) {
            case EVIDENCE -> evidenceThreadId(epoch, actor, commandId);
            case REVIEW -> "grt.v1."
                    + stableToken(epoch.getCaseId() + "\n" + actor.actorId() + "\n" + roomType);
            default -> throw new IllegalArgumentException(
                    "target browser Graph thread is unsupported for " + roomType);
        };
    }
    private static boolean isTargetEpoch(CaseRoomEpochEntity epoch) {
        return epoch.getWriterMode() == com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.TEMPORAL
                && epoch.getLifecycleStatus() == EpochLifecycleStatus.ACTIVE
                && epoch.getProvisioningStatus() == EpochProvisioningStatus.READY
                && TargetTypedRoomProtocol.GRAPH_KEY.equals(epoch.getGraphKey());
    }
    private static void requireGrant(CaseRoomEpochEntity epoch, TargetRoomEpochSelectionAuthority.Grant grant) {
        if (!epoch.getTenantSurrogate().equals(grant.request().tenantSurrogate()) || !epoch.getCaseId().equals(grant.request().caseId())
                || epoch.getRoomType() != grant.request().roomType() || !epoch.getTemporalBuildId().equals(grant.roomWorkflowBuildId())
                || !epoch.getGraphKey().equals(grant.graphKey()) || !epoch.getGraphVersion().equals(grant.graphVersion())
                || !epoch.getCheckpointSchemaVersion().equals(grant.checkpointSchemaVersion())) {
            throw new IllegalStateException("target room activation grant differs from locked epoch");
        }
    }
    private static com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole mapRole(ActorRole role) {
        return switch (role) { case USER -> com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.USER;
            case MERCHANT -> com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.MERCHANT;
            case PLATFORM_REVIEWER -> com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.PLATFORM_REVIEWER;
            case ADMIN -> com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.ADMIN;
            case SYSTEM, CUSTOMER_SERVICE -> com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.SYSTEM; };
    }
    private static Audience audience(ActorRole role) {
        return switch (role) { case USER -> Audience.USER; case MERCHANT -> Audience.MERCHANT;
            case PLATFORM_REVIEWER, ADMIN, CUSTOMER_SERVICE -> Audience.PLATFORM_REVIEWER; case SYSTEM -> Audience.SYSTEM; };
    }
    private static String stableToken(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }
    private static String expectedTraceId(String traceId) {
        var current = W3cTraceContext.currentTraceparent();
        if (current.isPresent()) return current.orElseThrow().substring(3, 35);
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("target room traceId must not be blank");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("trace:" + traceId).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
    private static String traceparent(String traceId) {
        if (traceId != null && traceId.matches("[0-9a-f]{32}") && !"0".repeat(32).equals(traceId)) {
            return "00-" + traceId + "-0000000000000001-01";
        }
        throw new IllegalArgumentException("target room traceId must be a non-zero lowercase trace id");
    }
}

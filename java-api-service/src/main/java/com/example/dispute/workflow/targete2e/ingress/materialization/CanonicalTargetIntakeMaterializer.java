package com.example.dispute.workflow.targete2e.ingress.materialization;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrar;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphCommandEnvelope;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationGrant;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeMessageRequest;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.Objects;

/**
 * Caller-transaction target Intake materialization. No workflow is launched here: the persisted
 * v2 context is the sole hand-off to the control worker.
 */
public final class CanonicalTargetIntakeMaterializer implements TargetIntakeMaterializer {
    private static final Duration DEADLINE = Duration.ofHours(1);
    private static final int ATTEMPT_LIMIT = 3;
    private static final String OPERATION = "INTAKE_MESSAGE";

    private final AccessSessionResolver accessSessions;
    private final AgentSessionResolver agentSessions;
    private final IntakePrivateThreadRegistrar threadRegistrar;
    private final IntakeDomainSnapshotPublisher snapshots;
    private final IntakeTurnEventPublisher events;
    private final IntakeGraphCommandFactory commands;
    private final AgentRunCommandBindingFactory bindings;
    private final AgentRunLedger ledger;
    private final TargetE2EGraphEnvelopeCodec envelopes;
    private final TargetIntakeCommandMaterialStore materialStore;
    private final JdbcTargetE2eApiAuthority activationAuthority;
    private final TargetIntakeRuntimePins pins;
    private final Clock clock;

    public CanonicalTargetIntakeMaterializer(
            AccessSessionResolver accessSessions,
            AgentSessionResolver agentSessions,
            IntakePrivateThreadRegistrar threadRegistrar,
            IntakeDomainSnapshotPublisher snapshots,
            IntakeTurnEventPublisher events,
            IntakeGraphCommandFactory commands,
            AgentRunCommandBindingFactory bindings,
            AgentRunLedger ledger,
            TargetE2EGraphEnvelopeCodec envelopes,
            TargetIntakeCommandMaterialStore materialStore,
            JdbcTargetE2eApiAuthority activationAuthority,
            TargetIntakeRuntimePins pins,
            Clock clock) {
        this.accessSessions = Objects.requireNonNull(accessSessions, "accessSessions");
        this.agentSessions = Objects.requireNonNull(agentSessions, "agentSessions");
        this.threadRegistrar = Objects.requireNonNull(threadRegistrar, "threadRegistrar");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.events = Objects.requireNonNull(events, "events");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
        this.activationAuthority = Objects.requireNonNull(activationAuthority, "activationAuthority");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MaterializedIntake materialize(TargetIntakeMessageRequest request) {
        Objects.requireNonNull(request, "request");
        TargetIntakeActivationGrant activation = request.activation();
        Instant now = clock.instant();
        if (!now.isBefore(activation.expiresAt())) {
            throw new IllegalStateException("target Intake activation has expired");
        }
        TargetIntakeRuntimePins activePins = activationAuthority.resolveIntakeRuntimePins(activation, pins);
        CaseAccessSessionEntity access = accessSessions.resolve(request.caseId(), request.actor());
        requireActor(access, request, activation);
        AgentConversationSessionEntity session = agentSessions.resolve(
                access, RoomType.INTAKE, IntakeAgentTurnService.AGENT_ROLE,
                activePins.promptVersion(), activePins.memoryPolicyVersion());

        String identity = token(activation.activationId() + "\n" + request.messageId());
        String commandId = "intake-message:" + identity;
        String registrationId = "target-intake-registration:" + identity;
        IntakePrivateThreadRegistration.ActorScope actorScope = new IntakePrivateThreadRegistration.ActorScope(
                request.actor().actorId(),
                com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.valueOf(request.actor().role().name()),
                audience(request), List.of(caseCapability(request.caseId())));
        IntakeGraphThreadBinding thread = threadRegistrar.register(
                new IntakePrivateThreadRegistrationFactory.IssueRequest(
                        registrationId, activation.tenantSurrogate(), request.caseId(), activation.roomEpoch(),
                        activation.roomFencingToken(), actorScope, session.getId(), activePins.registrationPins(),
                        WriterMode.TEMPORAL, request.createdAt())).value();

        IntakeSnapshotReference snapshot = snapshots.publish(new IntakeDomainSnapshotPublisher.SnapshotRequest(
                "target-intake-snapshot:" + identity, thread,
                activation.processRevision(), activation.processRevision(), activation.processRevision(),
                List.of(request.messageId()), JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(), List.of(), JsonNodeFactory.instance.objectNode(),
                request.createdAt())).value();
        var event = events.publish(new IntakeTurnEventPublisher.EventRequest(
                "target-intake-event:" + identity, request.messageId(), thread, 1,
                activation.processRevision(), audience(request), IntakeTurnEventPublisher.SourceType.ROOM_MESSAGE,
                request.text(), List.of(request.messageId()), request.createdAt(), now)).value();

        String logicalRunId = "target-intake-run:" + identity;
        String attemptId = "target-intake-attempt:" + identity + ":1";
        Instant deadline = request.createdAt().plus(DEADLINE);
        RoomGraphCommand graph = commands.create(new IntakeGraphCommandFactory.CommandRequest(
                commandId, logicalRunId, attemptId, thread, snapshot, event, activation.processRevision(),
                "INTAKE", activation.processRevision(), session.getPromptProfileId(), 2, 3, 1, deadline,
                traceparent(request.traceId()), activePins.envelopeKeyId(), nonce(request)));
        TargetE2EGraphCommandEnvelope envelope = envelopes.wrapCommand(
                activation.activationId(), activation.roomFencingToken(), graph);
        AgentRunCommandBindingFactory.Binding binding = bindings.bind(
                new AgentRunCommandBindingFactory.Context(request.roomId(),
                        request.caseId() + ":" + activation.roomEpoch(), OPERATION, request.idempotencyKey()), graph);
        LogicalRun logical = ledger.createOrLoad(new CreateLogicalRun(
                logicalRunId, activation.tenantSurrogate(), request.caseId(), request.roomId(), OPERATION,
                request.idempotencyKey(), AgentRunProtocol.V2, AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                request.caseId() + ":" + activation.roomEpoch(), graph.roomType(), graph.roomEpoch(),
                graph.processRevision(), activation.roomFencingToken(), graph.requestHash(),
                binding.logicalInputHash(), ATTEMPT_LIMIT, deadline, now));
        if (!logical.agentRunId().equals(logicalRunId)) {
            throw new IllegalStateException("target Intake logical run replay drifted");
        }
        Attempt attempt = ledger.startNextAttempt(logical.agentRunId(), new AttemptAllocation(1, graph, binding), now);
        if (!attempt.agentRunId().equals(logical.agentRunId())
                || !attempt.attemptId().equals(graph.attemptId())
                || attempt.attemptNo() != 1
                || !attempt.logicalInputHash().equals(binding.logicalInputHash())) {
            throw new IllegalStateException("target Intake AgentRun attempt allocation drifted");
        }
        ExecuteAgentRunRequest run = new ExecuteAgentRunRequest(ExecuteAgentRunRequest.SCHEMA_VERSION,
                logical.agentRunId(), attempt.attemptNo(), logical.attemptLimit(), "agent-stream.v2",
                attempt.logicalInputHash(), attempt.previousAttemptId(), attempt.resetRequired(),
                attempt.publicSequenceOffset(), graph);
        IntakeTargetAgentRunContext target = new IntakeTargetAgentRunContext(
                "intake-target-agent-run-context.v1", IntakeTargetAgentRunContext.TARGET_LANE,
                activation.activationId(), activation.manifestHash(), activation.roomFencingToken(),
                activation.processRevision(), activation.roomRevision(), activePins.caseBuildId(),
                activation.temporalBuildId(), activePins.agentBuildId(), activePins.graphBindingHash(), activePins.graphCodeBuildId(),
                envelope.commandHash(), envelope.commandEnvelopeHash(), run);
        IntakeCommandExecutionContext context = new IntakeCommandExecutionContext(
                "intake-command-execution-context.v2", thread.registration().threadId(), session.getId(),
                deadline.toEpochMilli(), new RetryBudget("intake-retry-budget.v1", 2, 3, 1), null, target);
        CommandAdmission admission = new CommandAdmission(activation.activationId(), activation.manifestHash(),
                activePins.isolatedDomainDbBindingHash(), activation.tenantSurrogate(), request.caseId(), commandId,
                envelope.commandHash(), envelope.commandEnvelopeHash(), activation.roomEpoch(),
                activation.roomFencingToken());
        var appended = materialStore.append(admission, context);
        return new MaterializedIntake(commandId, event.payloadRef(), appended.admittedAt());
    }

    private static void requireActor(CaseAccessSessionEntity access, TargetIntakeMessageRequest request,
            TargetIntakeActivationGrant activation) {
        if (!activation.tenantSurrogate().equals(access.getTenantId())
                || !request.caseId().equals(access.getCaseId())
                || !request.actor().actorId().equals(access.getActorId())
                || request.actor().role() != access.getActorRole()) {
            throw new IllegalStateException("target Intake access session does not match the active authority");
        }
    }

    private static Audience audience(TargetIntakeMessageRequest request) {
        return request.actor().role() == com.example.dispute.config.ActorRole.USER ? Audience.USER : Audience.MERCHANT;
    }

    private static String nonce(TargetIntakeMessageRequest request) {
        return "target-intake-nonce:" + token(request.messageId());
    }

    private static String caseCapability(String caseId) {
        return "case:" + caseId + ":command:INTAKE_MESSAGE";
    }

    private static String token(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private static String traceparent(String traceId) {
        if (traceId != null && traceId.matches("[0-9a-f]{32}")) {
            return "00-" + traceId + "-0000000000000001-01";
        }
        throw new IllegalArgumentException("target Intake traceId must be a 32-character lowercase trace id");
    }
}

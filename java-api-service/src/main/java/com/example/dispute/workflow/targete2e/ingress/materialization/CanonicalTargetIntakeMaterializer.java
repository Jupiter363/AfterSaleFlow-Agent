package com.example.dispute.workflow.targete2e.ingress.materialization;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.casecore.domain.CasePartyPosition;
import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeCaseSeedMetadata;
import com.example.dispute.room.application.IntakeInitialCaseFacts;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
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
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphCommandEnvelope;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationGrant;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandIdentity;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeMessageRequest;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.CommandLookup;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.MaterialSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caller-transaction target Intake materialization. No workflow is launched here: the persisted
 * v2 context is the sole hand-off to the control worker.
 */
public final class CanonicalTargetIntakeMaterializer implements TargetIntakeMaterializer {
    private static final int ATTEMPT_LIMIT = 3;
    private static final String OPERATION = "INTAKE_MESSAGE";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(CanonicalTargetIntakeMaterializer.class);

    private final AccessSessionResolver accessSessions;
    private final AgentSessionResolver agentSessions;
    private final ParticipantService participants;
    private final IntakePrivateThreadRegistrar threadRegistrar;
    private final IntakeDomainSnapshotPublisher snapshots;
    private final IntakeTurnEventPublisher events;
    private final IntakeGraphCommandFactory commands;
    private final AgentRunCommandBindingFactory bindings;
    private final AgentRunLedger ledger;
    private final TargetE2EGraphEnvelopeCodec envelopes;
    private final TargetIntakeCommandMaterialStore materialStore;
    private final JdbcTargetE2eApiAuthority activationAuthority;
    private final FulfillmentCaseRepository cases;
    private final CaseIntakeDossierRepository dossiers;
    private final CaseRoomEpochRepository epochs;
    private final CaseProcessProjectionRepository projections;
    private final TargetIntakeRuntimePins pins;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CanonicalTargetIntakeMaterializer(
            AccessSessionResolver accessSessions,
            AgentSessionResolver agentSessions,
            ParticipantService participants,
            IntakePrivateThreadRegistrar threadRegistrar,
            IntakeDomainSnapshotPublisher snapshots,
            IntakeTurnEventPublisher events,
            IntakeGraphCommandFactory commands,
            AgentRunCommandBindingFactory bindings,
            AgentRunLedger ledger,
            TargetE2EGraphEnvelopeCodec envelopes,
            TargetIntakeCommandMaterialStore materialStore,
            JdbcTargetE2eApiAuthority activationAuthority,
            FulfillmentCaseRepository cases,
            CaseIntakeDossierRepository dossiers,
            CaseRoomEpochRepository epochs,
            CaseProcessProjectionRepository projections,
            TargetIntakeRuntimePins pins,
            ObjectMapper objectMapper,
            Clock clock) {
        this.accessSessions = Objects.requireNonNull(accessSessions, "accessSessions");
        this.agentSessions = Objects.requireNonNull(agentSessions, "agentSessions");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.threadRegistrar = Objects.requireNonNull(threadRegistrar, "threadRegistrar");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.events = Objects.requireNonNull(events, "events");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
        this.activationAuthority = Objects.requireNonNull(activationAuthority, "activationAuthority");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.dossiers = Objects.requireNonNull(dossiers, "dossiers");
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MaterializedIntake materialize(TargetIntakeMessageRequest request) {
        Objects.requireNonNull(request, "request");
        TargetIntakeActivationGrant activation = request.activation();
        long startedAt = System.nanoTime();
        Instant now = clock.instant();
        if (!now.isBefore(activation.expiresAt())) {
            throw new IllegalStateException("target Intake activation has expired");
        }
        TargetIntakeRuntimePins activePins = activationAuthority.resolveIntakeRuntimePins(activation, pins);
        var actorRegistrationPins = activePins.registrationPins(request.actor().role());
        CaseRoomEpochEntity epoch = epochs
                .findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        request.caseId(),
                        com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE,
                        activation.roomEpoch())
                .orElseThrow(() -> new IllegalStateException(
                        "target Intake activation has no persisted room epoch authority"));
        String roomEpochId = requireEpochAuthority(epoch, request, activation, activePins);
        CaseProcessProjectionEntity projection = projections.findByIdForUpdate(request.caseId())
                .orElseThrow(() -> new IllegalStateException(
                        "target Intake activation has no persisted process projection authority"));
        ProjectionStage stage = requireProjectionAuthority(projection, request, activation);
        FulfillmentCaseEntity dispute = cases.findByIdForUpdate(request.caseId())
                .orElseThrow(() -> new IllegalStateException("target Intake case is missing"));
        requireRespondentOpeningActor(dispute, request);
        CaseAccessSessionEntity access = accessSessions.resolve(
                activation.tenantSurrogate(), request.caseId(), request.actor());
        requireActor(
                access,
                activation.tenantSurrogate(),
                request.caseId(),
                request.actor().actorId(),
                request.actor().role());
        String messageIdentity = TargetIntakeCommandIdentity.messageIdentity(activation, request);
        String commandId = TargetIntakeCommandIdentity.messageCommandId(activation, request);
        String logicalRunId = "target-intake-run:" + messageIdentity;
        MaterializedIntake replay =
                replayOpening(request, activation, commandId, logicalRunId);
        if (replay != null) {
            return replay;
        }
        requireRespondentOpeningPhase(stage, request);
        long authorityLoadedAt = System.nanoTime();
        participants.activateExistingParty(
                request.caseId(), request.actor(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        AgentConversationSessionEntity session = agentSessions.resolve(
                access, RoomType.INTAKE, IntakeAgentTurnService.AGENT_ROLE,
                actorRegistrationPins.promptVersion(), activePins.memoryPolicyVersion());
        long sessionResolvedAt = System.nanoTime();

        IntakePrivateThreadRegistration.ActorScope actorScope = new IntakePrivateThreadRegistration.ActorScope(
                request.actor().actorId(),
                com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.valueOf(request.actor().role().name()),
                audience(request), List.of(caseCapability(request.caseId())));
        String registrationId = "target-intake-registration:" + token(threadIdentity(
                activation, request, actorScope, session.getId(), actorRegistrationPins));
        IntakeGraphThreadBinding thread = threadRegistrar.register(
                new IntakePrivateThreadRegistrationFactory.IssueRequest(
                        registrationId, activation.tenantSurrogate(), request.caseId(), activation.roomEpoch(),
                        activation.roomFencingToken(), actorScope, session.getId(), actorRegistrationPins,
                        WriterMode.TEMPORAL, request.createdAt())).value();
        long threadRegisteredAt = System.nanoTime();

        IntakeSnapshotReference snapshot = snapshots.publishOrLoad(new IntakeDomainSnapshotPublisher.SnapshotRequest(
                "target-intake-snapshot:" + token(registrationId), thread,
                activation.processRevision(), activation.processRevision(), activation.processRevision(),
                List.of(request.messageId()), initialCaseFacts(dispute),
                shareableProjection(dispute), List.of(), currentDossier(request.caseId()),
                request.createdAt())).value();
        long snapshotPublishedAt = System.nanoTime();
        String eventId = "target-intake-event:" + messageIdentity;
        var allocation = events.allocate(thread, eventId, request.messageId());
        if (isRespondentOpening(request) && allocation.sequenceNo() != 1) {
            throw new IllegalStateException(
                    "target Intake respondent opening must be the first private-thread event");
        }
        var event = allocation.existing().orElseGet(() -> events.publish(
                new IntakeTurnEventPublisher.EventRequest(
                        eventId, request.messageId(), thread, allocation.sequenceNo(),
                        activation.processRevision(), audience(request),
                        eventSourceType(request), request.text(),
                        List.of(request.messageId()), request.createdAt(), now)).value());
        long eventPublishedAt = System.nanoTime();

        String attemptId = "target-intake-attempt:" + messageIdentity + ":1";
        Instant deadline = request.commandDeadlineAt();
        RoomGraphCommand graph = commands.create(new IntakeGraphCommandFactory.CommandRequest(
                commandId, logicalRunId, attemptId, thread, snapshot, event, activation.processRevision(),
                stage.code(), stage.sequence(), activePins.agentProfileId(), 2, 3, 1, deadline,
                traceparent(request.traceId()), activePins.envelopeKeyId(), nonce(request)));
        TargetE2EGraphCommandEnvelope envelope = envelopes.wrapCommand(
                activation.activationId(), activation.roomFencingToken(), graph);
        AgentRunCommandBindingFactory.Binding binding = bindings.bind(
                new AgentRunCommandBindingFactory.Context(
                        request.roomId(), roomEpochId, OPERATION, request.idempotencyKey()), graph);
        LogicalRun logical = ledger.createOrLoad(new CreateLogicalRun(
                logicalRunId, activation.tenantSurrogate(), request.caseId(), request.roomId(), OPERATION,
                request.idempotencyKey(), AgentRunProtocol.V2, AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                roomEpochId, graph.roomType(), graph.roomEpoch(),
                graph.processRevision(), activation.roomFencingToken(), graph.requestHash(),
                binding.logicalInputHash(), ATTEMPT_LIMIT, deadline, now));
        if (!logical.agentRunId().equals(logicalRunId)) {
            throw new IllegalStateException("target Intake logical run replay drifted");
        }
        Attempt attempt = ledger.startNextAttempt(logical.agentRunId(), new AttemptAllocation(1, graph, binding), now);
        long attemptAllocatedAt = System.nanoTime();
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
        long contextBuiltAt = System.nanoTime();
        var appended = materialStore.append(admission, context);
        long materialAppendedAt = System.nanoTime();
        LOGGER.info(
                "target_intake_materialize_timing run_id={} authority_ms={} session_ms={} thread_ms={} snapshot_ms={} event_ms={} ledger_ms={} context_ms={} append_ms={} total_ms={}",
                logicalRunId,
                elapsedMillis(startedAt, authorityLoadedAt),
                elapsedMillis(authorityLoadedAt, sessionResolvedAt),
                elapsedMillis(sessionResolvedAt, threadRegisteredAt),
                elapsedMillis(threadRegisteredAt, snapshotPublishedAt),
                elapsedMillis(snapshotPublishedAt, eventPublishedAt),
                elapsedMillis(eventPublishedAt, attemptAllocatedAt),
                elapsedMillis(attemptAllocatedAt, contextBuiltAt),
                elapsedMillis(contextBuiltAt, materialAppendedAt),
                elapsedMillis(startedAt, materialAppendedAt));
        return new MaterializedIntake(
                commandId, logicalRunId, event.payloadRef(), appended.admittedAt(), deadline);
    }

    private static double elapsedMillis(long startedAt, long completedAt) {
        return (completedAt - startedAt) / 1_000_000.0d;
    }

    private MaterializedIntake replayOpening(
            TargetIntakeMessageRequest request,
            TargetIntakeActivationGrant activation,
            String commandId,
            String logicalRunId) {
        if (request.sourceType() != TargetIntakeMessageRequest.SourceType.INITIAL_FORM
                && !isRespondentOpening(request)) {
            return null;
        }
        MaterialSnapshot material =
                materialStore
                        .readByRoute(
                                new CommandLookup(
                                        activation.tenantSurrogate(),
                                        request.caseId(),
                                        commandId,
                                        activation.roomEpoch(),
                                        activation.roomFencingToken()))
                        .orElse(null);
        if (material == null) {
            return null;
        }
        RoomGraphCommand graph = material.context().targetAgentRun().request().command();
        if (!commandId.equals(graph.commandId())
                || !logicalRunId.equals(graph.logicalRunId())
                || graph.eventRef() == null
                || graph.deadlineAt() == null) {
            throw new IllegalStateException(
                    "persisted target Intake opening identity does not match the active authority");
        }
        return new MaterializedIntake(
                commandId,
                logicalRunId,
                graph.eventRef(),
                material.storedAt(),
                graph.deadlineAt());
    }

    static void requireActor(CaseAccessSessionEntity access, String tenantId, String caseId, String actorId,
            com.example.dispute.config.ActorRole actorRole) {
        if (!tenantId.equals(access.getTenantId())
                || !caseId.equals(access.getCaseId())
                || !actorId.equals(access.getActorId())
                || actorRole != access.getActorRole()) {
            throw new IllegalStateException("target Intake access session does not match the active authority");
        }
    }

    private static void requireRespondentOpeningActor(
            FulfillmentCaseEntity dispute, TargetIntakeMessageRequest request) {
        if (!isRespondentOpening(request)) {
            return;
        }
        CasePartyPosition position =
                Objects.requireNonNull(
                                dispute.partyAssignment(),
                                "target Intake case party assignment must not be null")
                        .resolve(request.actor().actorId(), request.actor().role())
                        .orElse(null);
        if (position != CasePartyPosition.RESPONDENT) {
            throw new IllegalStateException(
                    "target Intake respondent opening actor does not match case authority");
        }
    }

    private static void requireRespondentOpeningPhase(
            ProjectionStage stage, TargetIntakeMessageRequest request) {
        if (isRespondentOpening(request) && !"WAITING_PARTY".equals(stage.code())) {
            throw new IllegalStateException(
                    "target Intake respondent opening requires WAITING_PARTY phase");
        }
    }

    private static boolean isRespondentOpening(TargetIntakeMessageRequest request) {
        return request.sourceType() == TargetIntakeMessageRequest.SourceType.RESPONDENT_OPENING;
    }

    static String requireEpochAuthority(
            CaseRoomEpochEntity epoch,
            TargetIntakeMessageRequest request,
            TargetIntakeActivationGrant activation,
            TargetIntakeRuntimePins pins) {
        if (epoch == null
                || !activation.tenantSurrogate().equals(epoch.getTenantSurrogate())
                || !request.caseId().equals(epoch.getCaseId())
                || !request.roomId().equals(epoch.getRoomId())
                || epoch.getRoomType()
                        != com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE
                || epoch.getWriterMode() != WriterMode.TEMPORAL
                || epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE
                || epoch.getProvisioningStatus() != EpochProvisioningStatus.READY
                || epoch.getRoomEpoch() != activation.roomEpoch()
                || epoch.getFencingToken() != activation.roomFencingToken()
                || epoch.getProcessRevision() != activation.processRevision()
                || epoch.getRoomRevision() != activation.roomRevision()
                || !activation.temporalWorkflowId().equals(epoch.getTemporalWorkflowId())
                || !activation.temporalBuildId().equals(epoch.getTemporalBuildId())
                || !pins.registrationPins().graphKey().equals(epoch.getGraphKey())
                || !pins.registrationPins().graphVersion().equals(epoch.getGraphVersion())
                || !pins.registrationPins().checkpointSchemaVersion()
                        .equals(epoch.getCheckpointSchemaVersion())
                || epoch.getId() == null
                || epoch.getId().isBlank()) {
            throw new IllegalStateException(
                    "target Intake activation conflicts with persisted room epoch authority");
        }
        return epoch.getId();
    }

    static ProjectionStage requireProjectionAuthority(
            CaseProcessProjectionEntity projection,
            TargetIntakeMessageRequest request,
            TargetIntakeActivationGrant activation) {
        if (projection == null
                || !activation.tenantSurrogate().equals(projection.getTenantSurrogate())
                || !request.caseId().equals(projection.getCaseId())
                || !com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE.name()
                        .equals(projection.getCurrentRoom())
                || projection.getWriterMode() != WriterMode.TEMPORAL
                || projection.getWriterActivationStatus() != WriterActivationStatus.READY
                || projection.getProcessRevision() != activation.processRevision()
                || projection.getRoomEpoch() != activation.roomEpoch()
                || projection.getFencingToken() != activation.roomFencingToken()
                || !activation.temporalWorkflowId().equals(projection.getTemporalWorkflowId())
                || !activation.temporalBuildId().equals(projection.getTemporalBuildId())
                || projection.getRoomPhase() == null
                || projection.getRoomPhase().isBlank()
                || projection.getLastCommandSequence() < 0) {
            throw new IllegalStateException(
                    "target Intake activation conflicts with persisted process projection authority");
        }
        return new ProjectionStage(projection.getRoomPhase(), projection.getLastCommandSequence());
    }

    private ObjectNode initialCaseFacts(FulfillmentCaseEntity dispute) {
        ObjectNode facts = JsonNodeFactory.instance.objectNode();
        IntakeInitialCaseFacts persisted =
                IntakeCaseSeedMetadata.decode(dispute.getMetadataJson()).orElse(null);
        putIfPresent(
                facts,
                "form_source",
                persisted == null
                        ? (dispute.getSourceType() == CaseSourceType.EXTERNAL_IMPORT
                                ? "EXTERNAL_IMPORT"
                                : "FORM_SUBMISSION")
                        : persisted.formSource());
        putIfPresent(facts, "form_description", dispute.getDescription());
        putIfPresent(facts, "order_reference", dispute.getOrderId());
        putIfPresent(facts, "after_sales_reference", dispute.getAfterSaleId());
        putIfPresent(facts, "logistics_reference", dispute.getLogisticsId());
        putIfPresent(facts, "initiator_role", dispute.getInitiatorRole().name());
        if (persisted != null) {
            putIfPresent(facts, "requested_outcome_hint", persisted.requestedOutcomeHint());
            if (persisted.claimResolutionSeed() != null) {
                facts.set(
                        "claim_resolution_seed",
                        objectMapper.valueToTree(persisted.claimResolutionSeed()));
            }
            if (persisted.respondentAttitudeSeed() != null) {
                facts.set(
                        "respondent_attitude_seed",
                        objectMapper.valueToTree(persisted.respondentAttitudeSeed()));
            }
        }
        putIfPresent(facts, "case_type", dispute.getCaseType());
        putIfPresent(facts, "case_title", dispute.getTitle());
        return facts;
    }

    private ObjectNode shareableProjection(FulfillmentCaseEntity dispute) {
        ObjectNode projection = JsonNodeFactory.instance.objectNode();
        putIfPresent(projection, "case_id", dispute.getId());
        putIfPresent(projection, "title", dispute.getTitle());
        putIfPresent(projection, "description", dispute.getDescription());
        putIfPresent(projection, "order_reference", dispute.getOrderId());
        putIfPresent(projection, "after_sales_reference", dispute.getAfterSaleId());
        putIfPresent(projection, "logistics_reference", dispute.getLogisticsId());
        putIfPresent(projection, "initiator_role", dispute.getInitiatorRole().name());
        putIfPresent(projection, "respondent_role", dispute.getRespondentRole().name());
        return projection;
    }

    private JsonNode currentDossier(String caseId) {
        return dossiers.findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                .map(dossier -> parseDossier(dossier.getDossierJson()))
                .orElseGet(() -> JsonNodeFactory.instance.objectNode());
    }

    private JsonNode parseDossier(String serialized) {
        try {
            JsonNode parsed = objectMapper.readTree(serialized);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalStateException("target Intake dossier must be a JSON object");
            }
            return parsed;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("target Intake dossier is not valid JSON", error);
        }
    }

    private static void putIfPresent(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    record ProjectionStage(String code, long sequence) {}

    private static Audience audience(TargetIntakeMessageRequest request) {
        return request.actor().role() == com.example.dispute.config.ActorRole.USER ? Audience.USER : Audience.MERCHANT;
    }

    private static IntakeTurnEventPublisher.SourceType eventSourceType(
            TargetIntakeMessageRequest request) {
        return switch (request.sourceType()) {
            case INITIAL_FORM -> IntakeTurnEventPublisher.SourceType.INITIAL_FORM;
            case ROOM_MESSAGE -> IntakeTurnEventPublisher.SourceType.ROOM_MESSAGE;
            case RESPONDENT_OPENING ->
                    IntakeTurnEventPublisher.SourceType.RESPONDENT_OPENING;
        };
    }

    private static String nonce(TargetIntakeMessageRequest request) {
        return "target-intake-nonce:" + token(request.messageId());
    }

    private static String threadIdentity(
            TargetIntakeActivationGrant activation,
            TargetIntakeMessageRequest request,
            IntakePrivateThreadRegistration.ActorScope actorScope,
            String agentSessionId,
            IntakePrivateThreadRegistrationFactory.VersionPins graphPins) {
        return String.join("\n",
                activation.tenantSurrogate(), request.caseId(),
                Long.toString(activation.roomEpoch()), Long.toString(activation.roomFencingToken()),
                actorScope.actorId(), actorScope.actorRole().name(), actorScope.audience().name(), agentSessionId,
                graphPins.graphKey(), graphPins.graphVersion(), graphPins.checkpointSchemaVersion(),
                graphPins.stateSchemaVersion(), graphPins.promptVersion(), graphPins.modelProfileId(),
                graphPins.outputSchemaVersion(), graphPins.policyVersion(), graphPins.guardrailVersion(),
                graphPins.toolPolicyVersion(), WriterMode.TEMPORAL.name());
    }

    static String durableMessageIdentity(
            TargetIntakeActivationGrant activation, TargetIntakeMessageRequest request) {
        return TargetIntakeCommandIdentity.messageIdentity(activation, request);
    }

    private static String caseCapability(String caseId) {
        return "case:" + caseId + ":command:INTAKE_MESSAGE";
    }

    private static String token(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    static String traceparent(String traceId) {
        String normalized = traceId != null && traceId.startsWith("TRACE_")
                ? traceId.substring("TRACE_".length())
                : traceId;
        if (normalized != null && normalized.matches("[0-9a-f]{32}")) {
            return "00-" + normalized + "-0000000000000001-01";
        }
        throw new IllegalArgumentException("target Intake traceId must be a 32-character lowercase trace id");
    }
}

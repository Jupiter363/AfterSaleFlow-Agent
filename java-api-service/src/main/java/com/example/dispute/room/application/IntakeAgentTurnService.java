package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntakeAgentTurnService {

    public static final String AGENT_ROLE = "DISPUTE_INTAKE_OFFICER";
    private static final String AGENT_SENDER_ROLE = "CUSTOMER_SERVICE";
    private static final String AGENT_SENDER_ID = "dispute-intake-officer";
    private static final String DEGRADED_REASON_AGENT_CALL_FAILED = "AGENT_CALL_FAILED";
    private static final String DEGRADED_REASON_AGENT_OUTPUT_EMPTY = "AGENT_OUTPUT_EMPTY";
    private static final Logger log = LoggerFactory.getLogger(IntakeAgentTurnService.class);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final AccessSessionResolver accessSessionResolver;
    private final AgentSessionResolver agentSessionResolver;
    private final SessionPermissionService permissionService;
    private final IntakeAgentTurnClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IntakeAgentTurnService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            RoomTurnMemoryRepository memoryRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            AccessSessionResolver accessSessionResolver,
            AgentSessionResolver agentSessionResolver,
            SessionPermissionService permissionService,
            IntakeAgentTurnClient client,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.memoryRepository = memoryRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.accessSessionResolver = accessSessionResolver;
        this.agentSessionResolver = agentSessionResolver;
        this.permissionService = permissionService;
        this.client = client;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void startInitialTurn(
            String caseId,
            AuthenticatedActor actor,
            IntakeLobbySeed lobbySeed,
            String traceId,
            String requestId) {
        TurnContext context = prepare(caseId, RoomType.INTAKE);
        SessionContext session = resolveSession(caseId, actor, RoomType.INTAKE);
        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        JsonNode previousScrollSnapshot = latestScrollSnapshot(session.agentSession().getId());
        IntakeAgentTurnCommand command =
                new IntakeAgentTurnCommand(
                        caseId,
                        RoomType.INTAKE,
                        "LOBBY_SEED",
                        sanitizeLobbySeed(lobbySeed),
                        null,
                        previousScrollSnapshot,
                        recentTurns(session.agentSession()),
                        session.agentContext());
        IntakeAgentTurnResult result =
                safeRun(command, previousScrollSnapshot, traceId, requestId);
        persistAgentTurn(context, session, turnNo, result, traceId);
    }

    @Transactional
    public void continueFromParticipantMessage(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            RoomMessageCommand message,
            String traceId,
            String requestId) {
        if (roomType != RoomType.INTAKE
                || message.messageType() != MessageType.PARTY_TEXT
                || !isParty(actor.role())) {
            return;
        }
        TurnContext context = prepare(caseId, RoomType.INTAKE);
        SessionContext session = resolveSession(caseId, actor, RoomType.INTAKE);
        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        memoryRepository.save(
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_" + compactUuid(),
                        caseId,
                        RoomType.INTAKE,
                        turnNo,
                        actor.actorId(),
                        actor.role().name(),
                        message.text(),
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));
        JsonNode previousScrollSnapshot = latestScrollSnapshot(session.agentSession().getId());
        IntakeAgentTurnCommand command =
                new IntakeAgentTurnCommand(
                        caseId,
                        RoomType.INTAKE,
                        "USER_MESSAGE",
                        seedFromDispute(context.dispute(), actor),
                        new IntakeParticipantMessage(
                                "INTAKE_TURN_" + turnNo,
                                actor.role().name(),
                                message.text()),
                        previousScrollSnapshot,
                        recentTurns(session.agentSession()),
                        session.agentContext());
        IntakeAgentTurnResult result =
                safeRun(command, previousScrollSnapshot, traceId, requestId);
        persistAgentTurn(context, session, turnNo, result, traceId);
    }

    private SessionContext resolveSession(
            String caseId, AuthenticatedActor actor, RoomType roomType) {
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
        AgentConversationSessionEntity agentSession =
                agentSessionResolver.resolve(
                        accessSession,
                        roomType,
                        AGENT_ROLE,
                        promptProfileId(actor.role()),
                        "MEMEO_DEFAULT");
        return new SessionContext(
                accessSession,
                agentSession,
                AgentInvocationContext.partyPrivate(
                        accessSession,
                        agentSession,
                        roomType,
                        "INTAKE_INITIATOR_PRIVATE"));
    }

    private TurnContext prepare(String caseId, RoomType roomType) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"));
        if (room.getRoomStatus() != RoomStatus.OPEN) {
            throw new IllegalStateException("intake room is not open");
        }
        return new TurnContext(dispute, room);
    }

    private IntakeAgentTurnResult safeRun(
            IntakeAgentTurnCommand command,
            JsonNode previousScrollSnapshot,
            String traceId,
            String requestId) {
        try {
            IntakeAgentTurnResult result = client.run(command, traceId, requestId);
            if (result == null || blank(result.roomUtterance())) {
                log.warn(
                        "Intake agent turn degraded because output was empty: case_id={}, room_type={}, turn_source={}, trace_id={}, request_id={}",
                        command.caseId(),
                        command.roomType(),
                        command.turnSource(),
                        traceId,
                        requestId);
                return degraded(
                        previousScrollSnapshot,
                        DEGRADED_REASON_AGENT_OUTPUT_EMPTY,
                        traceId);
            }
            return result;
        } catch (RuntimeException failure) {
            log.warn(
                    "Intake agent turn degraded after agent call failure: case_id={}, room_type={}, turn_source={}, trace_id={}, request_id={}, failure_type={}, failure_message={}",
                    command.caseId(),
                    command.roomType(),
                    command.turnSource(),
                    traceId,
                    requestId,
                    failure.getClass().getName(),
                    failure.getMessage(),
                    failure);
            return degraded(
                    previousScrollSnapshot,
                    DEGRADED_REASON_AGENT_CALL_FAILED,
                    traceId);
        }
    }

    private IntakeLobbySeed seedFromDispute(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        return sanitizeLobbySeed(
                new IntakeLobbySeed(
                        dispute.getOrderId(),
                        dispute.getAfterSaleId(),
                        dispute.getLogisticsId(),
                        actor.role().name(),
                        dispute.getDescription(),
                        null));
    }

    private IntakeLobbySeed sanitizeLobbySeed(IntakeLobbySeed seed) {
        return new IntakeLobbySeed(
                validIdentifierOrNull(seed.orderReference()),
                validIdentifierOrNull(seed.afterSalesReference()),
                validIdentifierOrNull(seed.logisticsReference()),
                seed.initiatorRole(),
                seed.rawText(),
                validIdentifierOrNull(seed.requestedOutcomeHint()),
                sanitizeClaimResolutionSeed(seed.claimResolutionSeed()),
                sanitizeRespondentAttitudeSeed(seed.respondentAttitudeSeed()));
    }

    private static IntakeLobbySeed.ClaimResolutionSeed sanitizeClaimResolutionSeed(
            IntakeLobbySeed.ClaimResolutionSeed seed) {
        if (seed == null) return null;
        return new IntakeLobbySeed.ClaimResolutionSeed(
                validIdentifierOrNull(seed.initiatorRole()),
                validIdentifierOrNull(seed.requestedResolution()),
                seed.requestedAmount(),
                blankToNull(seed.requestedItems()),
                blankToNull(seed.requestReason()),
                blankToNull(seed.originalStatement()));
    }

    private static IntakeLobbySeed.RespondentAttitudeSeed sanitizeRespondentAttitudeSeed(
            IntakeLobbySeed.RespondentAttitudeSeed seed) {
        if (seed == null) return null;
        return new IntakeLobbySeed.RespondentAttitudeSeed(
                validIdentifierOrNull(seed.respondentRole()),
                validIdentifierOrNull(seed.attitude()),
                blankToNull(seed.position()),
                blankToNull(seed.source()),
                seed.confidence());
    }

    private void persistAgentTurn(
            TurnContext context,
            SessionContext session,
            int turnNo,
            IntakeAgentTurnResult result,
            String traceId) {
        String runId = "INTAKE_RUN_" + compactUuid();
        memoryRepository.save(
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_" + compactUuid(),
                        context.dispute().getId(),
                        RoomType.INTAKE,
                        turnNo,
                        AGENT_SENDER_ID,
                        AGENT_ROLE,
                        result.roomUtterance(),
                        json(dossierPatchWithMemoryFrame(result)),
                        json(defaultObject(result.scrollSnapshot())),
                        json(defaultArray(result.canvasOperations())),
                        runId,
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));
        upsertCurrentDossier(context.dispute().getId(), turnNo, result);
        appendAgentMessage(
                context.dispute(),
                context.room(),
                session.agentSession(),
                result.roomUtterance(),
                turnNo,
                traceId);
        eventService.recordLifecycleEvent(
                context.dispute().getId(),
                context.room().getId(),
                "INTAKE_DOSSIER_UPDATED",
                Map.of("turn_no", turnNo, "agent_role", AGENT_ROLE),
                "intake-dossier-updated:"
                        + context.dispute().getId()
                        + ":"
                        + session.agentSession().getId()
                        + ":"
                        + turnNo,
                AGENT_SENDER_ID);
    }

    private void upsertCurrentDossier(
            String caseId,
            int turnNo,
            IntakeAgentTurnResult result) {
        JsonNode dossier = defaultObject(result.scrollSnapshot());
        int qualityScore = dossier.path("intake_quality").path("score").asInt(0);
        boolean readyForNextStep =
                dossier.path("intake_quality").path("ready_for_next_step").asBoolean(false);
        String recommendation =
                textOrDefault(
                        dossier.path("admission").path("recommendation"),
                        result.admissionRecommendation());
        String dossierJson = json(dossier);
        CaseIntakeDossierEntity entity;
        var existing = intakeDossierRepository.findByCaseIdAndRoomType(caseId, RoomType.INTAKE);
        if (existing.isPresent()) {
            entity = existing.orElseThrow();
            entity.replaceWith(
                    dossierJson,
                    qualityScore,
                    readyForNextStep,
                    recommendation,
                    turnNo,
                    AGENT_SENDER_ID);
        } else {
            entity =
                    CaseIntakeDossierEntity.create(
                            "INTAKE_DOSSIER_" + compactUuid(),
                            caseId,
                            RoomType.INTAKE,
                            dossierJson,
                            qualityScore,
                            readyForNextStep,
                            recommendation,
                            turnNo,
                            AGENT_SENDER_ID);
        }
        intakeDossierRepository.save(entity);
    }

    private void appendAgentMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            AgentConversationSessionEntity agentSession,
            String utterance,
            int turnNo,
            String traceId) {
        String idempotencyKey = "agent-intake-turn:" + agentSession.getId() + ":" + turnNo;
        if (messageRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), idempotencyKey)
                .isPresent()) {
            return;
        }
        String audienceActorIdsJson = json(List.of(agentSession.getActorId()));
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity saved =
                messageRepository.save(
                        RoomMessageEntity.create(
                                "MESSAGE_" + compactUuid(),
                                dispute.getId(),
                                room.getId(),
                                sequence,
                                MessageSenderType.AGENT,
                                AGENT_SENDER_ROLE,
                                AGENT_SENDER_ID,
                                audienceJson(),
                                audienceActorIdsJson,
                                MessageType.AGENT_MESSAGE,
                                utterance,
                                "[]",
                                idempotencyKey,
                                Instant.now(clock),
                                traceId));
        eventService.recordRoomMessage(
                dispute.getId(),
                room.getId(),
                saved.getId(),
                saved.getMessageText(),
                saved.getAudienceJson(),
                audienceActorIdsJson,
                AGENT_SENDER_ID);
    }

    private JsonNode latestScrollSnapshot(String agentSessionId) {
        return memoryRepository
                .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(agentSessionId)
                .map(RoomTurnMemoryEntity::getScrollSnapshotJson)
                .map(this::readJson)
                .orElseGet(objectMapper::createObjectNode);
    }

    private List<IntakeRecentTurn> recentTurns(AgentConversationSessionEntity agentSession) {
        return memoryRepository
                .findTop10ByAgentSessionIdOrderByTurnNoDesc(agentSession.getId())
                .stream()
                .filter(memory -> agentSession.getId().equals(memory.getAgentSessionId()))
                .sorted(Comparator.comparingInt(RoomTurnMemoryEntity::getTurnNo))
                .map(
                        memory ->
                                new IntakeRecentTurn(
                                        memory.getTurnNo(),
                                        memory.getActorId(),
                                        memory.getAnswerRole(),
                                        memory.getAnswerContent(),
                                        memory.getAgentRole(),
                                        memory.getAgentResponse(),
                                        readJson(memory.getScrollSnapshotJson()),
                                        textOrDefault(memory.getAgentSessionId(), agentSession.getId()),
                                        textOrDefault(
                                                memory.getConversationScope(),
                                                agentSession.getConversationScope())))
                .toList();
    }

    private IntakeAgentTurnResult degraded(
            JsonNode previousScrollSnapshot, String reason, String traceId) {
        JsonNode scroll =
                previousScrollSnapshot == null || previousScrollSnapshot.isNull()
                        ? objectMapper.createObjectNode()
                        : previousScrollSnapshot;
        return new IntakeAgentTurnResult(
                "接待官暂时没有完成智能整理，但你的发言已经安全保存。你可以继续补充订单、售后、物流和诉求信息。",
                objectMapper.valueToTree(
                        Map.of(
                                "agent_degraded",
                                true,
                                "agent_degraded_reason",
                                reason,
                                "trace_id",
                                traceId,
                                "next_best_action",
                                "CONTINUE_INTAKE_DIALOG")),
                scroll,
                objectMapper.valueToTree(List.of()),
                objectMapper.createObjectNode(),
                "CONTINUE",
                List.of(),
                false,
                "STUB",
                0.0);
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode defaultObject(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
    }

    private JsonNode dossierPatchWithMemoryFrame(IntakeAgentTurnResult result) {
        JsonNode source = defaultObject(result.dossierPatch());
        ObjectNode patch =
                source.isObject()
                        ? ((ObjectNode) source).deepCopy()
                        : objectMapper.createObjectNode();
        JsonNode memoryFrame = defaultObject(result.memoryFrame());
        if (!memoryFrame.isEmpty()) {
            patch.set("memory_frame", memoryFrame);
        }
        return patch;
    }

    private JsonNode defaultArray(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.createArrayNode() : node;
    }

    private static String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return fallback;
        }
        return node.asText();
    }

    private static String textOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private String audienceJson() {
        return json(
                List.of(
                        ActorRole.USER.name(),
                        ActorRole.MERCHANT.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize intake agent turn", exception);
        }
    }

    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String validIdentifierOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() >= 3 ? normalized : null;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String promptProfileId(ActorRole role) {
        return AGENT_ROLE + ":" + role.name() + ":v1";
    }

    private record TurnContext(FulfillmentCaseEntity dispute, CaseRoomEntity room) {}

    private record SessionContext(
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession,
            AgentInvocationContext agentContext) {}
}

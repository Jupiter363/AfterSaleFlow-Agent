package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceAgentTurnService {

    public static final String AGENT_ROLE = "EVIDENCE_CLERK";
    private static final String AGENT_SENDER_ROLE = "CUSTOMER_SERVICE";
    private static final String AGENT_SENDER_ID = "evidence-clerk";
    private static final String DEGRADED_REASON_AGENT_CALL_FAILED = "AGENT_CALL_FAILED";
    private static final String DEGRADED_REASON_AGENT_OUTPUT_EMPTY = "AGENT_OUTPUT_EMPTY";
    private static final Logger log = LoggerFactory.getLogger(EvidenceAgentTurnService.class);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final AccessSessionResolver accessSessionResolver;
    private final AgentSessionResolver agentSessionResolver;
    private final SessionPermissionService permissionService;
    private final EvidenceAgentTurnClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvidenceAgentTurnService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            RoomTurnMemoryRepository memoryRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            EvidenceItemRepository evidenceItemRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            AccessSessionResolver accessSessionResolver,
            AgentSessionResolver agentSessionResolver,
            SessionPermissionService permissionService,
            EvidenceAgentTurnClient client,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.memoryRepository = memoryRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.evidenceItemRepository = evidenceItemRepository;
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
    public void continueFromParticipantMessage(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            RoomMessageCommand message,
            String traceId,
            String requestId) {
        if (roomType != RoomType.EVIDENCE
                || !isEvidenceTurnMessage(message.messageType())
                || !isParty(actor.role())) {
            return;
        }

        TurnContext context = prepare(caseId, RoomType.EVIDENCE);
        SessionContext session = resolveSession(caseId, actor, RoomType.EVIDENCE);
        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        memoryRepository.save(
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_" + compactUuid(),
                        caseId,
                        RoomType.EVIDENCE,
                        turnNo,
                        actor.actorId(),
                        actor.role().name(),
                        participantAnswerContent(message),
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));

        EvidenceAgentTurnCommand command =
                new EvidenceAgentTurnCommand(
                        caseId,
                        RoomType.EVIDENCE,
                        "PARTY_MESSAGE",
                        actor.role().name(),
                        actor.actorId(),
                        new EvidenceAgentTurnCommand.Message(
                                "EVIDENCE_TURN_" + turnNo,
                                message.messageType(),
                                actor.role().name(),
                                participantAnswerContent(message),
                                message.attachmentRefs()),
                        latestCaseIntakeDossier(caseId),
                        availableEvidence(caseId, actor),
                        recentTurns(session.agentSession()),
                        session.agentContext());
        EvidenceAgentTurnResult result = safeRun(command, traceId, requestId);
        persistAgentTurn(context, session, turnNo, actor.role(), result, traceId);
    }

    private SessionContext resolveSession(
            String caseId, AuthenticatedActor actor, RoomType roomType) {
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
        permissionService.requireEvidenceSubmit(accessSession);
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
                        "EVIDENCE_PARTY_PRIVATE"));
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
            throw new IllegalStateException("evidence room is not open");
        }
        return new TurnContext(dispute, room);
    }

    private EvidenceAgentTurnResult safeRun(
            EvidenceAgentTurnCommand command, String traceId, String requestId) {
        try {
            EvidenceAgentTurnResult result = client.run(command, traceId, requestId);
            if (result == null
                    || blank(result.roomUtterance())
                    || result.liabilityDetermined()
                    || result.remedyRecommended()) {
                log.warn(
                        "Evidence agent turn degraded because output was empty or unsafe: case_id={}, room_type={}, trace_id={}, request_id={}",
                        command.caseId(),
                        command.roomType(),
                        traceId,
                        requestId);
                return degraded(DEGRADED_REASON_AGENT_OUTPUT_EMPTY, traceId);
            }
            return result;
        } catch (RuntimeException failure) {
            log.warn(
                    "Evidence agent turn degraded after agent call failure: case_id={}, room_type={}, trace_id={}, request_id={}, failure_type={}, failure_message={}",
                    command.caseId(),
                    command.roomType(),
                    traceId,
                    requestId,
                    failure.getClass().getName(),
                    failure.getMessage(),
                    failure);
            return degraded(DEGRADED_REASON_AGENT_CALL_FAILED, traceId);
        }
    }

    private void persistAgentTurn(
            TurnContext context,
            SessionContext session,
            int turnNo,
            ActorRole audienceParty,
            EvidenceAgentTurnResult result,
            String traceId) {
        String runId = "EVIDENCE_RUN_" + compactUuid();
        memoryRepository.save(
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_" + compactUuid(),
                        context.dispute().getId(),
                        RoomType.EVIDENCE,
                        turnNo,
                        AGENT_SENDER_ID,
                        AGENT_ROLE,
                        result.roomUtterance(),
                        json(defaultObject(result.memoryPatch())),
                        "{}",
                        json(defaultArray(result.canvasOperations())),
                        runId,
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));
        appendAgentMessage(
                context.dispute(),
                context.room(),
                session.agentSession(),
                audienceParty,
                result.roomUtterance(),
                turnNo,
                traceId);
    }

    private void appendAgentMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            AgentConversationSessionEntity agentSession,
            ActorRole audienceParty,
            String utterance,
            int turnNo,
            String traceId) {
        String idempotencyKey =
                "agent-evidence-turn:"
                        + dispute.getId()
                        + ":"
                        + agentSession.getId()
                        + ":"
                        + audienceParty.name()
                        + ":"
                        + turnNo;
        if (messageRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), idempotencyKey)
                .isPresent()) {
            return;
        }
        String audienceJson = audienceJson(audienceParty);
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
                                audienceJson,
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

    private JsonNode latestCaseIntakeDossier(String caseId) {
        return intakeDossierRepository
                .findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                .map(CaseIntakeDossierEntity::getDossierJson)
                .map(this::readJson)
                .orElseGet(objectMapper::createObjectNode);
    }

    private List<EvidenceAgentTurnCommand.AvailableEvidence> availableEvidence(
            String caseId, AuthenticatedActor actor) {
        return evidenceItemRepository
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(caseId)
                .stream()
                .filter(item -> visibleEvidenceTo(item, actor))
                .map(item -> availableEvidence(caseId, item))
                .toList();
    }

    private EvidenceAgentTurnCommand.AvailableEvidence availableEvidence(
            String caseId, EvidenceItemEntity item) {
        return new EvidenceAgentTurnCommand.AvailableEvidence(
                item.getId(),
                item.getEvidenceType(),
                item.getSourceType(),
                evidenceContent(item),
                item.getParsedText(),
                item.getOccurredAt() == null ? null : item.getOccurredAt().toString(),
                item.getSubmittedByRole(),
                item.getVisibility(),
                "/api/disputes/" + caseId + "/evidence/" + item.getId() + "/content",
                false,
                item.getParseStatus().name(),
                item.getOriginalFilename());
    }

    private List<IntakeRecentTurn> recentTurns(AgentConversationSessionEntity agentSession) {
        List<RoomTurnMemoryEntity> memories =
                memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(agentSession.getId());
        Set<Integer> legacyPartyTurnNos =
                memories.stream()
                        .filter(memory -> !hasText(memory.getAgentSessionId()))
                        .filter(
                                memory ->
                                        agentSession
                                                .getActorRole()
                                                .name()
                                                .equals(memory.getAnswerRole()))
                        .map(RoomTurnMemoryEntity::getTurnNo)
                        .collect(Collectors.toSet());
        List<IntakeRecentTurn> scopedTurns =
                memories.stream()
                        .filter(memory -> visibleToAgentSession(memory, agentSession, legacyPartyTurnNos))
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
        if (scopedTurns.size() <= 10) {
            return scopedTurns;
        }
        return scopedTurns.subList(scopedTurns.size() - 10, scopedTurns.size());
    }

    private static boolean visibleToAgentSession(
            RoomTurnMemoryEntity memory,
            AgentConversationSessionEntity agentSession,
            Set<Integer> legacyPartyTurnNos) {
        if (hasText(memory.getAgentSessionId())) {
            return agentSession.getId().equals(memory.getAgentSessionId());
        }
        if (agentSession.getActorRole().name().equals(memory.getAnswerRole())) {
            return true;
        }
        return memory.getAgentRole() != null && legacyPartyTurnNos.contains(memory.getTurnNo());
    }

    private EvidenceAgentTurnResult degraded(String reason, String traceId) {
        return new EvidenceAgentTurnResult(
                "证据书记官暂时没有完成智能核验，但你的发言已经安全保存。你可以继续补充证据来源、形成时间、原始文件和关联说明。",
                objectMapper.valueToTree(
                        Map.of(
                                "agent_degraded",
                                true,
                                "agent_degraded_reason",
                                reason,
                                "trace_id",
                                traceId,
                                "next_best_action",
                                "CONTINUE_EVIDENCE_DIALOG")),
                objectMapper.valueToTree(List.of()),
                List.of(),
                false,
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

    private JsonNode defaultArray(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.createArrayNode() : node;
    }

    private String audienceJson(ActorRole party) {
        return json(
                List.of(
                        party.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence agent turn", exception);
        }
    }

    private static String textOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static boolean visibleEvidenceTo(EvidenceItemEntity item, AuthenticatedActor actor) {
        ActorRole role = actor.role();
        if (role == ActorRole.PLATFORM_REVIEWER
                || role == ActorRole.ADMIN
                || role == ActorRole.SYSTEM) {
            return true;
        }
        if (role == ActorRole.CUSTOMER_SERVICE) {
            return "PARTIES".equals(item.getVisibility()) || "PLATFORM".equals(item.getVisibility());
        }
        return role.name().equals(item.getSubmittedByRole())
                        && actor.actorId().equals(item.getSubmittedById())
                || "PARTIES".equals(item.getVisibility());
    }

    private static boolean isEvidenceTurnMessage(MessageType messageType) {
        return messageType == MessageType.PARTY_TEXT
                || messageType == MessageType.PARTY_EVIDENCE_REFERENCE;
    }

    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    private static String participantAnswerContent(RoomMessageCommand message) {
        if (message.text() != null && !message.text().isBlank()) {
            return message.text();
        }
        return "Evidence references: " + message.attachmentRefs();
    }

    private static String evidenceContent(EvidenceItemEntity item) {
        if (item.getParsedText() != null && !item.getParsedText().isBlank()) {
            return item.getParsedText();
        }
        if (item.getOriginalFilename() != null && !item.getOriginalFilename().isBlank()) {
            return "Uploaded evidence file: " + item.getOriginalFilename();
        }
        return "Uploaded evidence item: " + item.getId();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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

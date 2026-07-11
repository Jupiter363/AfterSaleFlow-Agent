package com.example.dispute.room.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
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
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final String OPENING_IDEMPOTENCY_VERSION = "dossier-v3";
    private static final String DEGRADED_REASON_AGENT_CALL_FAILED = "AGENT_CALL_FAILED";
    private static final String DEGRADED_REASON_AGENT_OUTPUT_EMPTY = "AGENT_OUTPUT_EMPTY";
    private static final List<String> SUPERSEDED_GENERIC_OPENING_MARKERS =
            List.of(
                    "您好！我是您的证据书记官",
                    "请上传与本案相关的证据材料",
                    "争议焦点待确认",
                    "原始证据文件、证据形成时间、证据来源路径");
    private static final Logger log = LoggerFactory.getLogger(EvidenceAgentTurnService.class);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final EvidenceVerificationRepository verificationRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final AccessSessionResolver accessSessionResolver;
    private final AgentSessionResolver agentSessionResolver;
    private final SessionPermissionService permissionService;
    private final EvidenceContextEnvelopeFactory contextEnvelopeFactory;
    private final EvidenceAgentTurnClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvidenceAgentTurnService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            RoomTurnMemoryRepository memoryRepository,
            EvidenceItemRepository evidenceItemRepository,
            EvidenceVerificationRepository verificationRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            AccessSessionResolver accessSessionResolver,
            AgentSessionResolver agentSessionResolver,
            SessionPermissionService permissionService,
            EvidenceContextEnvelopeFactory contextEnvelopeFactory,
            EvidenceAgentTurnClient client,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.memoryRepository = memoryRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.verificationRepository = verificationRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.accessSessionResolver = accessSessionResolver;
        this.agentSessionResolver = agentSessionResolver;
        this.permissionService = permissionService;
        this.contextEnvelopeFactory = contextEnvelopeFactory;
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
            String sourceMessageId,
            Instant sourceMessageCreatedAt,
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
                        participantMemoryContent(message),
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));

        EvidenceAgentTurnCommand command =
                new EvidenceAgentTurnCommand(
                        contextEnvelopeFactory.create(
                                context.dispute(),
                                context.room(),
                                actor,
                                session.accessSession(),
                                session.agentSession(),
                                "PARTY_MESSAGE",
                                sourceMessageId,
                                message.messageType(),
                                message.text(),
                                message.attachmentRefs(),
                                turnNo,
                                sourceMessageCreatedAt),
                        session.agentContext());
        EvidenceAgentTurnResult result = safeRun(command, traceId, requestId);
        persistAgentTurn(context, session, turnNo, actor.role(), result, traceId);
    }

    @Transactional
    public RoomMessageView ensureOpening(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            String traceId,
            String requestId) {
        if (roomType != RoomType.EVIDENCE) {
            throw new IllegalArgumentException("opening is only supported for evidence room");
        }
        if (!isParty(actor.role())) {
            throw new IllegalArgumentException("evidence opening is only created for party actors");
        }

        TurnContext context = prepare(caseId, RoomType.EVIDENCE);
        SessionContext session = resolveSession(caseId, actor, RoomType.EVIDENCE);
        String idempotencyKey = openingIdempotencyKey(caseId, session.agentSession());
        var existing =
                messageRepository.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        List<RoomMessageEntity> visibleConversation =
                visibleActorScopedConversationMessages(context.room(), session.accessSession());
        if (!visibleConversation.isEmpty()
                && !isOnlySupersededOpeningMessages(visibleConversation)) {
            return view(visibleConversation.getFirst());
        }

        int turnNo = memoryRepository.findMaxTurnNoByAgentSessionId(session.agentSession().getId()) + 1;
        EvidenceAgentTurnCommand command =
                new EvidenceAgentTurnCommand(
                        contextEnvelopeFactory.create(
                                context.dispute(),
                                context.room(),
                                actor,
                                session.accessSession(),
                                session.agentSession(),
                                "ROOM_OPENING",
                                "EVIDENCE_OPENING_" + turnNo,
                                MessageType.AGENT_MESSAGE,
                                null,
                                List.of(),
                                turnNo,
                                clock.instant()),
                        session.agentContext());
        EvidenceAgentTurnResult result = safeRun(command, traceId, requestId);
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
                        "EVIDENCE_RUN_" + compactUuid(),
                        session.agentSession(),
                        session.accessSession(),
                        "{}"));
        RoomMessageEntity saved =
                appendAgentMessage(
                        context.dispute(),
                        context.room(),
                        session.agentSession(),
                        actor.role(),
                        result.roomUtterance(),
                        turnNo,
                        traceId,
                        idempotencyKey);
        return view(saved);
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
                        command.contextEnvelope().caseSnapshot().caseId(),
                        command.contextEnvelope().roomPolicy().roomType(),
                        traceId,
                        requestId);
                return degraded(DEGRADED_REASON_AGENT_OUTPUT_EMPTY, traceId);
            }
            return result;
        } catch (AgentExecutionException failure) {
            if (failure.errorCode() == ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID) {
                log.error(
                        "Evidence agent contract mismatch: case_id={}, room_type={}, trace_id={}, request_id={}, failure_message={}, validation_errors={}",
                        command.contextEnvelope().caseSnapshot().caseId(),
                        command.contextEnvelope().roomPolicy().roomType(),
                        traceId,
                        requestId,
                        failure.getMessage(),
                        failure.details().getOrDefault("validation_errors", List.of()),
                        failure);
                throw failure;
            }
            logAgentFailure(command, traceId, requestId, failure);
            return degraded(DEGRADED_REASON_AGENT_CALL_FAILED, traceId);
        } catch (RuntimeException failure) {
            logAgentFailure(command, traceId, requestId, failure);
            return degraded(DEGRADED_REASON_AGENT_CALL_FAILED, traceId);
        }
    }

    private void logAgentFailure(
            EvidenceAgentTurnCommand command,
            String traceId,
            String requestId,
            RuntimeException failure) {
        log.warn(
                "Evidence agent turn degraded after agent call failure: case_id={}, room_type={}, trace_id={}, request_id={}, failure_type={}, failure_message={}",
                command.contextEnvelope().caseSnapshot().caseId(),
                command.contextEnvelope().roomPolicy().roomType(),
                traceId,
                requestId,
                failure.getClass().getName(),
                failure.getMessage(),
                failure);
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
                traceId,
                turnIdempotencyKey(context.dispute(), session.agentSession(), audienceParty, turnNo));
        persistEvidenceVerificationSuggestions(
                context.dispute().getId(),
                session.accessSession(),
                result,
                runId,
                traceId);
    }

    private void persistEvidenceVerificationSuggestions(
            String caseId,
            CaseAccessSessionEntity accessSession,
            EvidenceAgentTurnResult result,
            String runId,
            String traceId) {
        List<EvidenceAgentTurnResult.EvidenceVerificationSuggestion> suggestions =
                normalizedVerificationSuggestions(result);
        if (suggestions.isEmpty()) {
            return;
        }
        Set<String> visibleEvidenceIds =
                evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                caseId)
                        .stream()
                        .filter(item -> evidenceVisibleToSession(item, accessSession))
                        .map(EvidenceItemEntity::getId)
                        .collect(Collectors.toSet());
        for (EvidenceAgentTurnResult.EvidenceVerificationSuggestion suggestion : suggestions) {
            if (blank(suggestion.evidenceId()) || !visibleEvidenceIds.contains(suggestion.evidenceId())) {
                continue;
            }
            int version =
                    verificationRepository
                            .findTopByEvidenceIdOrderByVerificationVersionDesc(
                                    suggestion.evidenceId())
                            .map(EvidenceVerificationEntity::getVerificationVersion)
                            .orElse(0)
                            + 1;
            double score = clamp01(suggestion.confidenceScore());
            verificationRepository.save(
                    EvidenceVerificationEntity.create(
                            "VERIFY_" + compactUuid(),
                            caseId,
                            suggestion.evidenceId(),
                            version,
                            verificationStatus(score),
                            json(
                                    Map.of(
                                            "checks",
                                            List.of(
                                                    "agent_batch_submission",
                                                    "source_time_authenticity_relevance"))),
                            json(
                                    Map.of(
                                            "agent_run_id",
                                            runId,
                                            "confidence_score",
                                            score,
                                            "confidence_level",
                                            confidenceLevel(score),
                                            "verification_feedback",
                                            safeText(suggestion.suggestion()),
                                            "room_utterance",
                                            safeText(result.roomUtterance()))),
                            json(
                                    Map.of(
                                            "summary",
                                            safeText(suggestion.suggestion()),
                                            "authenticity_flags",
                                            authenticityFlagsForEvidence(
                                                    result, suggestion.evidenceId()))),
                            score < 0.45,
                            Instant.now(clock),
                            AGENT_SENDER_ID,
                            traceId));
        }
    }

    private List<EvidenceAgentTurnResult.EvidenceVerificationSuggestion>
            normalizedVerificationSuggestions(EvidenceAgentTurnResult result) {
        if (!result.verificationSuggestions().isEmpty()) {
            return result.verificationSuggestions();
        }
        if (result.referencedEvidenceIds().isEmpty()) {
            return List.of();
        }
        return result.referencedEvidenceIds().stream()
                .map(
                        evidenceId ->
                                new EvidenceAgentTurnResult.EvidenceVerificationSuggestion(
                                        evidenceId,
                                        result.roomUtterance(),
                                        result.confidence()))
                .toList();
    }

    private boolean evidenceVisibleToSession(
            EvidenceItemEntity item, CaseAccessSessionEntity accessSession) {
        if (accessSession.privileged()) {
            return true;
        }
        return accessSession.getActorRole().name().equals(item.getSubmittedByRole())
                && accessSession.getActorId().equals(item.getSubmittedById())
                && item.getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED;
    }

    private static EvidenceVerificationStatus verificationStatus(double score) {
        if (score >= 0.75) {
            return EvidenceVerificationStatus.PLAUSIBLE;
        }
        if (score >= 0.45) {
            return EvidenceVerificationStatus.SUSPICIOUS;
        }
        return EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW;
    }

    private static String confidenceLevel(double score) {
        if (score >= 0.8) {
            return "HIGH";
        }
        if (score >= 0.5) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private List<Map<String, String>> authenticityFlagsForEvidence(
            EvidenceAgentTurnResult result, String evidenceId) {
        return result.authenticityFlags().stream()
                .filter(flag -> blank(flag.evidenceId()) || evidenceId.equals(flag.evidenceId()))
                .map(
                        flag ->
                                Map.of(
                                        "flag_type", safeText(flag.flagType()),
                                        "description", safeText(flag.description()),
                                        "severity", safeText(flag.severity())))
                .toList();
    }

    private RoomMessageEntity appendAgentMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            AgentConversationSessionEntity agentSession,
            ActorRole audienceParty,
            String utterance,
            int turnNo,
            String traceId,
            String idempotencyKey) {
        var existing =
                messageRepository.findByCaseIdAndIdempotencyKey(dispute.getId(), idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
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
        return saved;
    }

    private List<RoomMessageEntity> visibleActorScopedConversationMessages(
            CaseRoomEntity room, CaseAccessSessionEntity accessSession) {
        return messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()).stream()
                .filter(message -> visibleToAccessSession(message, accessSession))
                .toList();
    }

    private static boolean isOnlySupersededOpeningMessages(
            List<RoomMessageEntity> visibleConversation) {
        return !visibleConversation.isEmpty()
                && visibleConversation.stream()
                        .allMatch(EvidenceAgentTurnService::isSupersededOpeningMessage);
    }

    private static boolean isSupersededOpeningMessage(RoomMessageEntity message) {
        if (message.getMessageType() != MessageType.AGENT_MESSAGE
                || !AGENT_SENDER_ROLE.equals(message.getSenderRole())
                || !AGENT_SENDER_ID.equals(message.getSenderId())) {
            return false;
        }
        String text = message.getMessageText();
        return text != null
                && SUPERSEDED_GENERIC_OPENING_MARKERS.stream().anyMatch(text::contains);
    }

    private boolean visibleToAccessSession(
            RoomMessageEntity message, CaseAccessSessionEntity accessSession) {
        if (accessSession.privileged()) {
            return true;
        }
        if (isParty(accessSession.getActorRole()) && isPartySender(message)) {
            return accessSession.getActorRole().name().equals(message.getSenderRole())
                    && accessSession.getActorId().equals(message.getSenderId());
        }
        List<String> audiences = readStringList(message.getAudienceJson());
        if (!audiences.isEmpty() && !audiences.contains(accessSession.getActorRole().name())) {
            return false;
        }
        return permissionService.canReadActorAudience(
                accessSession, readStringList(message.getAudienceActorIdsJson()));
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid message audience", exception);
        }
    }

    private RoomMessageView view(RoomMessageEntity entity) {
        try {
            List<String> attachments =
                    objectMapper.readValue(
                            entity.getAttachmentRefsJson(), new TypeReference<>() {});
            return new RoomMessageView(
                    entity.getId(),
                    entity.getCaseId(),
                    entity.getRoomId(),
                    entity.getSequenceNo(),
                    entity.getSenderRole(),
                    entity.getSenderId(),
                    entity.getMessageType(),
                    entity.getMessageText(),
                    attachments,
                    entity.getHearingRound(),
                    entity.getCreatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid evidence opening message attachments", exception);
        }
    }

    private static String openingIdempotencyKey(
            String caseId, AgentConversationSessionEntity agentSession) {
        return "agent-evidence-opening:"
                + OPENING_IDEMPOTENCY_VERSION
                + ":"
                + caseId
                + ":"
                + agentSession.getId();
    }

    private static String turnIdempotencyKey(
            FulfillmentCaseEntity dispute,
            AgentConversationSessionEntity agentSession,
            ActorRole audienceParty,
            int turnNo) {
        return "agent-evidence-turn:"
                + dispute.getId()
                + ":"
                + agentSession.getId()
                + ":"
                + audienceParty.name()
                + ":"
                + turnNo;
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

    private static boolean isEvidenceTurnMessage(MessageType messageType) {
        return messageType == MessageType.PARTY_TEXT
                || messageType == MessageType.PARTY_EVIDENCE_REFERENCE;
    }

    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    private static boolean isPartySender(RoomMessageEntity message) {
        return ActorRole.USER.name().equals(message.getSenderRole())
                || ActorRole.MERCHANT.name().equals(message.getSenderRole());
    }

    private String participantMemoryContent(RoomMessageCommand message) {
        if (message.text() != null && !message.text().isBlank()) {
            return message.text();
        }
        return json(Map.of("attachment_refs", message.attachmentRefs()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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

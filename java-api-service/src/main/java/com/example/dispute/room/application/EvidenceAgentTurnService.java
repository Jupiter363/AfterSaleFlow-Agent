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
import java.util.HashSet;
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
        persistAgentTurn(
                context,
                session,
                turnNo,
                actor.role(),
                message.attachmentRefs(),
                result,
                traceId);
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
                throw new AgentExecutionException(
                        ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                        "evidence agent returned empty or unsafe output",
                        Map.of("trace_id", traceId, "request_id", requestId));
            }
            return result;
        } catch (AgentExecutionException failure) {
            logAgentFailure(command, traceId, requestId, failure);
            throw failure;
        } catch (RuntimeException failure) {
            logAgentFailure(command, traceId, requestId, failure);
            throw new AgentExecutionException(
                    ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                    "evidence agent request failed",
                    Map.of("trace_id", traceId, "request_id", requestId));
        }
    }

    private void logAgentFailure(
            EvidenceAgentTurnCommand command,
            String traceId,
            String requestId,
            RuntimeException failure) {
        log.warn(
                "Evidence agent turn failed closed after agent call failure: case_id={}, room_type={}, trace_id={}, request_id={}, failure_type={}, failure_message={}",
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
            List<String> currentAttachmentRefs,
            EvidenceAgentTurnResult result,
            String traceId) {
        Set<String> allowedAttachmentIds =
                validateEvidenceAssessmentCoverage(
                        context.dispute().getId(),
                        session.accessSession(),
                        currentAttachmentRefs,
                        result.evidenceAssessments(),
                        traceId);
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
        persistEvidenceVerifications(
                context.dispute().getId(),
                allowedAttachmentIds,
                result,
                runId,
                traceId);
    }

    private Set<String> validateEvidenceAssessmentCoverage(
            String caseId,
            CaseAccessSessionEntity accessSession,
            List<String> currentAttachmentRefs,
            List<EvidenceAgentTurnResult.EvidenceAssessment> assessments,
            String traceId) {
        Set<String> allowedAttachmentIds = new HashSet<>();
        if (currentAttachmentRefs != null && !currentAttachmentRefs.isEmpty()) {
            allowedAttachmentIds.addAll(visibleEvidenceIds(caseId, accessSession));
            allowedAttachmentIds.retainAll(Set.copyOf(currentAttachmentRefs));
        }

        Set<String> assessmentIds = new HashSet<>();
        Set<String> duplicateIds = new HashSet<>();
        boolean hasBlankId = false;
        for (EvidenceAgentTurnResult.EvidenceAssessment assessment : assessments) {
            String evidenceId = assessment.evidenceId();
            if (blank(evidenceId)) {
                hasBlankId = true;
                continue;
            }
            if (!assessmentIds.add(evidenceId)) {
                duplicateIds.add(evidenceId);
            }
        }

        Set<String> unknownIds = new HashSet<>(assessmentIds);
        unknownIds.removeAll(allowedAttachmentIds);
        Set<String> missingIds = new HashSet<>(allowedAttachmentIds);
        missingIds.removeAll(assessmentIds);
        if (hasBlankId
                || !duplicateIds.isEmpty()
                || !unknownIds.isEmpty()
                || !missingIds.isEmpty()
                || assessments.size() != allowedAttachmentIds.size()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "evidence agent assessments must cover each current attachment exactly once",
                    Map.of(
                            "trace_id", traceId,
                            "expected_evidence_ids", sorted(allowedAttachmentIds),
                            "assessment_evidence_ids", sorted(assessmentIds),
                            "duplicate_evidence_ids", sorted(duplicateIds),
                            "unknown_evidence_ids", sorted(unknownIds),
                            "missing_evidence_ids", sorted(missingIds),
                            "blank_evidence_id", hasBlankId));
        }
        return Set.copyOf(allowedAttachmentIds);
    }

    private void persistEvidenceVerifications(
            String caseId,
            Set<String> allowedAttachmentIds,
            EvidenceAgentTurnResult result,
            String runId,
            String traceId) {
        if (allowedAttachmentIds.isEmpty()) {
            return;
        }
        persistEvidenceAssessments(
                caseId,
                result.evidenceAssessments(),
                runId,
                traceId);
    }

    private void persistEvidenceAssessments(
            String caseId,
            List<EvidenceAgentTurnResult.EvidenceAssessment> assessments,
            String runId,
            String traceId) {
        for (EvidenceAgentTurnResult.EvidenceAssessment assessment : assessments) {
            int version = nextVerificationVersion(assessment.evidenceId());
            EvidenceAgentTurnResult.HumanReview humanReview = assessment.humanReview();
            boolean requiresHumanReview =
                    humanReview.required()
                            || "NEEDS_HUMAN_REVIEW".equals(assessment.recommendation());
            Map<String, Object> agentFindings = new java.util.LinkedHashMap<>();
            agentFindings.put("agent_run_id", runId);
            agentFindings.put("analysis_method", assessment.analysisMethod());
            agentFindings.put("inspected_modalities", assessment.inspectedModalities());
            agentFindings.put("fact_links", assessment.factLinks());
            agentFindings.put("authenticity_score", assessment.authenticityScore());
            agentFindings.put("relevance_score", assessment.relevanceScore());
            agentFindings.put("completeness_score", assessment.completenessScore());
            agentFindings.put("assessment_confidence", assessment.assessmentConfidence());
            agentFindings.put("confidence_score", assessment.assessmentConfidence());
            agentFindings.put(
                    "confidence_level", confidenceLevel(assessment.assessmentConfidence()));
            agentFindings.put("findings", assessment.findings());
            agentFindings.put("limitations", assessment.limitations());
            agentFindings.put("risk_flags", assessment.riskFlags());
            agentFindings.put("asset_audit", assessment.assetAudit());
            agentFindings.put("recommendation", assessment.recommendation());
            agentFindings.put("verification_feedback", safeText(assessment.summary()));
            agentFindings.put(
                    "human_review",
                    Map.of(
                            "required", requiresHumanReview,
                            "reason_codes", humanReview.reasonCodes(),
                            "instructions", humanReview.instructions()));

            Map<String, Object> reasons = new java.util.LinkedHashMap<>();
            reasons.put("summary", safeText(assessment.summary()));
            reasons.put("limitations", assessment.limitations());
            reasons.put("risk_flags", assessment.riskFlags());
            reasons.put("human_review_reason_codes", humanReview.reasonCodes());
            reasons.put("human_review_instructions", humanReview.instructions());

            verificationRepository.save(
                    EvidenceVerificationEntity.create(
                            "VERIFY_" + compactUuid(),
                            caseId,
                            assessment.evidenceId(),
                            version,
                            verificationStatus(assessment, requiresHumanReview),
                            json(
                                    Map.of(
                                            "checks",
                                            List.of(
                                                    "authorized_current_event_attachment",
                                                    "multimodal_evidence_assessment"),
                                            "inspected_modalities",
                                            assessment.inspectedModalities())),
                            json(agentFindings),
                            json(reasons),
                            requiresHumanReview,
                            Instant.now(clock),
                            AGENT_SENDER_ID,
                            traceId));
        }
    }

    private Set<String> visibleEvidenceIds(
            String caseId, CaseAccessSessionEntity accessSession) {
        return evidenceItemRepository
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(caseId)
                .stream()
                .filter(item -> evidenceVisibleToSession(item, accessSession))
                .map(EvidenceItemEntity::getId)
                .collect(Collectors.toCollection(java.util.HashSet::new));
    }

    private int nextVerificationVersion(String evidenceId) {
        return verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(evidenceId)
                        .map(EvidenceVerificationEntity::getVerificationVersion)
                        .orElse(0)
                + 1;
    }

    private static EvidenceVerificationStatus verificationStatus(
            EvidenceAgentTurnResult.EvidenceAssessment assessment,
            boolean requiresHumanReview) {
        if (requiresHumanReview) {
            return EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW;
        }
        return "SUSPICIOUS".equals(assessment.recommendation())
                ? EvidenceVerificationStatus.SUSPICIOUS
                : EvidenceVerificationStatus.PLAUSIBLE;
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

    private static String confidenceLevel(double score) {
        if (score >= 0.8) {
            return "HIGH";
        }
        if (score >= 0.5) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
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

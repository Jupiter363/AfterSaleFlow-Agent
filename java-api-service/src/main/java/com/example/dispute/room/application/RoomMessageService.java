package com.example.dispute.room.application;

import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.PermissionScope;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomMessageService {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseParticipantRepository participantRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final IntakeAgentTurnService intakeAgentTurnService;
    private final EvidenceAgentTurnService evidenceAgentTurnService;
    private final HearingRoundService hearingRoundService;
    private final AccessSessionResolver accessSessionResolver;
    private final SessionPermissionService permissionService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RoomMessageService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CaseParticipantRepository participantRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            IntakeAgentTurnService intakeAgentTurnService,
            EvidenceAgentTurnService evidenceAgentTurnService,
            HearingRoundService hearingRoundService,
            AccessSessionResolver accessSessionResolver,
            SessionPermissionService permissionService,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.intakeAgentTurnService = intakeAgentTurnService;
        this.evidenceAgentTurnService = evidenceAgentTurnService;
        this.hearingRoundService = hearingRoundService;
        this.accessSessionResolver = accessSessionResolver;
        this.permissionService = permissionService;
        this.clock = clock;
    }

    @Transactional
    public RoomMessageView post(
            String caseId,
            RoomType roomType,
            RoomMessageCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
        permissionService.require(accessSession, PermissionScope.ROOM_MESSAGE_WRITE);
        assertCanPost(dispute, roomType, actor, command.messageType());
        CaseRoomEntity requestedRoom =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"));
        return messageRepository
                .findByCaseIdAndIdempotencyKey(caseId, idempotencyKey)
                .map(
                        existing -> {
                            assertSameImmutableRequest(
                                    existing, requestedRoom, command, actor);
                            return view(existing);
                        })
                .orElseGet(
                        () ->
                                create(
                                        dispute,
                                        requestedRoom,
                                        command,
                                        actor,
                                        idempotencyKey,
                                        traceId));
    }

    @Transactional(readOnly = true)
    public List<RoomMessageView> list(
            String caseId, RoomType roomType, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
        permissionService.require(accessSession, PermissionScope.ROOM_MESSAGE_READ);
        assertCanRead(dispute, actor);
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"));
        if (roomType == RoomType.INTAKE
                && isParty(actor.role())
                && !isIntakeInitiator(dispute, actor)) {
            return List.of();
        }
        return messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId())
                .stream()
                .filter(message -> visibleTo(message, accessSession))
                .map(this::view)
                .toList();
    }

    @Transactional
    public RoomMessageView ensureOpening(
            String caseId,
            RoomType roomType,
            AuthenticatedActor actor,
            String traceId,
            String requestId) {
        if (roomType == RoomType.EVIDENCE) {
            return evidenceAgentTurnService.ensureOpening(
                    caseId, roomType, actor, traceId, requestId);
        }
        throw new IllegalArgumentException("room opening is not supported for " + roomType);
    }

    private RoomMessageView create(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            RoomMessageCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        if (room.getRoomStatus() != RoomStatus.OPEN) {
            throw new IllegalStateException("room is not open");
        }
        String audienceJson = audience(room.getRoomType(), actor.role(), command.messageType());
        String audienceActorIdsJson =
                audienceActorIds(room.getRoomType(), actor, command.messageType());
        Integer hearingRound = hearingRoundForPartyMessage(dispute, room, command, actor);
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity saved =
                messageRepository.save(
                        RoomMessageEntity.create(
                                "MESSAGE_" + compactUuid(),
                                dispute.getId(),
                                room.getId(),
                                sequence,
                                senderType(actor),
                                actor.role().name(),
                                actor.actorId(),
                                audienceJson,
                                audienceActorIdsJson,
                                command.messageType(),
                                command.text(),
                                json(command.attachmentRefs()),
                                idempotencyKey,
                                hearingRound,
                                clock.instant(),
                                traceId));
        if (hearingRound != null && command.messageType() == MessageType.PARTY_TEXT) {
            hearingRoundService.recordPartyMessageSubmission(
                    dispute.getId(),
                    hearingRound,
                    saved.getId(),
                    saved.getMessageText(),
                    actor);
        }
        eventService.recordRoomMessage(
                dispute.getId(),
                room.getId(),
                saved.getId(),
                saved.getMessageText(),
                audienceJson,
                audienceActorIdsJson,
                actor.actorId());
        intakeAgentTurnService.continueFromParticipantMessage(
                dispute.getId(),
                room.getRoomType(),
                actor,
                saved,
                traceId,
                traceId);
        evidenceAgentTurnService.continueFromParticipantMessage(
                dispute.getId(),
                room.getRoomType(),
                actor,
                command,
                saved.getId(),
                saved.getCreatedAt(),
                traceId,
                traceId);
        return view(saved);
    }

    private Integer hearingRoundForPartyMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            RoomMessageCommand command,
            AuthenticatedActor actor) {
        if (room.getRoomType() != RoomType.HEARING
                || !isHearingPartyRoundMessage(command.messageType())
                || !isParty(actor.role())) {
            return null;
        }
        return hearingRoundService.currentOpenRoundNoForPartyMessage(dispute.getId(), actor);
    }

    private void assertSameImmutableRequest(
            RoomMessageEntity existing,
            CaseRoomEntity requestedRoom,
            RoomMessageCommand command,
            AuthenticatedActor actor) {
        RoomMessageView existingView = view(existing);
        boolean sameRequest =
                existing.getRoomId().equals(requestedRoom.getId())
                        && existing.getSenderRole().equals(actor.role().name())
                        && existing.getSenderId().equals(actor.actorId())
                        && existing.getMessageType() == command.messageType()
                        && Objects.equals(existing.getMessageText(), command.text())
                        && existingView.attachmentRefs().equals(command.attachmentRefs());
        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used for a different room message");
        }
    }

    private void assertCanPost(
            FulfillmentCaseEntity dispute,
            RoomType roomType,
            AuthenticatedActor actor,
            MessageType messageType) {
        assertCanRead(dispute, actor);
        if (roomType == RoomType.INTAKE
                && isParty(actor.role())
                && !isIntakeInitiator(dispute, actor)) {
            throw new ForbiddenException(
                    "only the intake initiator can post in the intake room");
        }
        boolean allowed =
                switch (actor.role()) {
                    case USER, MERCHANT ->
                            messageType == MessageType.PARTY_TEXT
                                    || messageType == MessageType.PARTY_EVIDENCE_REFERENCE
                                    || messageType == MessageType.PARTY_CONFIRMATION;
                    case PLATFORM_REVIEWER -> messageType == MessageType.REVIEWER_NOTE;
                    case CUSTOMER_SERVICE, ADMIN, SYSTEM ->
                            messageType == MessageType.SYSTEM_EVENT
                                    || messageType == MessageType.AGENT_MESSAGE;
                };
        if (!allowed) {
            throw new ForbiddenException("message type is not allowed for actor");
        }
    }

    private void assertCanRead(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean privileged =
                switch (actor.role()) {
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                    default -> false;
                };
        boolean owner =
                actor.role() == ActorRole.USER && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        boolean participant =
                participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), actor.actorId(), actor.role());
        if (!privileged && !owner && !participant) {
            throw new ForbiddenException("actor cannot access room");
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
            throw new IllegalStateException("invalid message attachments", exception);
        }
    }

    private boolean visibleTo(RoomMessageEntity message, CaseAccessSessionEntity accessSession) {
        if (accessSession.getActorRole() == ActorRole.ADMIN
                || accessSession.getActorRole() == ActorRole.SYSTEM) {
            return true;
        }
        try {
            List<String> audiences =
                    objectMapper.readValue(
                            message.getAudienceJson(), new TypeReference<>() {});
            if (!audiences.isEmpty() && !audiences.contains(accessSession.getActorRole().name())) {
                return false;
            }
            List<String> audienceActorIds =
                    objectMapper.readValue(
                            message.getAudienceActorIdsJson(), new TypeReference<>() {});
            return permissionService.canReadActorAudience(accessSession, audienceActorIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid message audience", exception);
        }
    }

    private String audience(RoomType roomType, ActorRole senderRole, MessageType messageType) {
        if (messageType == MessageType.REVIEWER_NOTE) {
            return json(
                    List.of(
                            ActorRole.PLATFORM_REVIEWER.name(),
                            ActorRole.ADMIN.name()));
        }
        if (roomType == RoomType.EVIDENCE
                && isParty(senderRole)
                && isEvidencePrivatePartyMessage(messageType)) {
            return json(
                    List.of(
                            senderRole.name(),
                            ActorRole.CUSTOMER_SERVICE.name(),
                            ActorRole.PLATFORM_REVIEWER.name(),
                            ActorRole.ADMIN.name(),
                            ActorRole.SYSTEM.name()));
        }
        return json(
                List.of(
                        ActorRole.USER.name(),
                        ActorRole.MERCHANT.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    private String audienceActorIds(
            RoomType roomType, AuthenticatedActor sender, MessageType messageType) {
        if (roomType == RoomType.INTAKE && isParty(sender.role())) {
            return json(List.of(sender.actorId()));
        }
        if (roomType == RoomType.EVIDENCE
                && isParty(sender.role())
                && isEvidencePrivatePartyMessage(messageType)) {
            return json(List.of(sender.actorId()));
        }
        return "[]";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize room message", exception);
        }
    }

    private static MessageSenderType senderType(AuthenticatedActor actor) {
        return switch (actor.role()) {
            case USER, MERCHANT -> MessageSenderType.PARTY;
            case PLATFORM_REVIEWER -> MessageSenderType.REVIEWER;
            case SYSTEM -> MessageSenderType.SYSTEM;
            case CUSTOMER_SERVICE, ADMIN -> MessageSenderType.AGENT;
        };
    }

    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    private static boolean isEvidencePrivatePartyMessage(MessageType messageType) {
        return messageType == MessageType.PARTY_TEXT
                || messageType == MessageType.PARTY_EVIDENCE_REFERENCE;
    }

    private static boolean isHearingPartyRoundMessage(MessageType messageType) {
        return messageType == MessageType.PARTY_TEXT
                || messageType == MessageType.PARTY_EVIDENCE_REFERENCE;
    }

    private static boolean isIntakeInitiator(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if (actor.role() != dispute.getInitiatorRole()) {
            return false;
        }
        return switch (actor.role()) {
            case USER -> actor.actorId().equals(dispute.getUserId());
            case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
            default -> false;
        };
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

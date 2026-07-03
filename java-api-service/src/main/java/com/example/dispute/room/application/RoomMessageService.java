package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
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
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
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
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RoomMessageService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CaseParticipantRepository participantRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
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
        assertCanPost(dispute, actor, command.messageType());
        return messageRepository
                .findByCaseIdAndIdempotencyKey(caseId, idempotencyKey)
                .map(this::view)
                .orElseGet(
                        () ->
                                create(
                                        dispute,
                                        roomType,
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
        assertCanRead(dispute, actor);
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"));
        return messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId())
                .stream()
                .map(this::view)
                .toList();
    }

    private RoomMessageView create(
            FulfillmentCaseEntity dispute,
            RoomType roomType,
            RoomMessageCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId) {
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), roomType)
                        .orElseThrow(() -> new IllegalArgumentException("room not found"));
        if (room.getRoomStatus() != RoomStatus.OPEN) {
            throw new IllegalStateException("room is not open");
        }
        String audienceJson = audience(command.messageType());
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
                                command.messageType(),
                                command.text(),
                                json(command.attachmentRefs()),
                                idempotencyKey,
                                clock.instant(),
                                traceId));
        eventService.recordRoomMessage(
                dispute.getId(),
                room.getId(),
                saved.getId(),
                saved.getMessageText(),
                audienceJson,
                actor.actorId());
        return view(saved);
    }

    private void assertCanPost(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor, MessageType messageType) {
        assertCanRead(dispute, actor);
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
                    entity.getCreatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid message attachments", exception);
        }
    }

    private String audience(MessageType messageType) {
        return messageType == MessageType.REVIEWER_NOTE
                ? json(List.of(ActorRole.PLATFORM_REVIEWER.name()))
                : json(
                        List.of(
                                ActorRole.USER.name(),
                                ActorRole.MERCHANT.name(),
                                ActorRole.PLATFORM_REVIEWER.name()));
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

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

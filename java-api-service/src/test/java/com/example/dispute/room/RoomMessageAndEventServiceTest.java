package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.annotations.Immutable;

@ExtendWith(MockitoExtension.class)
class RoomMessageAndEventServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseTimelineEventRepository eventRepository;

    private CaseEventService eventService;
    private RoomMessageService messageService;

    @BeforeEach
    void setUp() {
        eventService =
                new CaseEventService(
                        eventRepository,
                        caseRepository,
                        participantRepository,
                        new ObjectMapper(),
                        CLOCK);
        messageService =
                new RoomMessageService(
                        caseRepository,
                        roomRepository,
                        participantRepository,
                        messageRepository,
                        eventService,
                        CLOCK);
    }

    @Test
    void persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords() {
        assertThat(RoomMessageEntity.class.isAnnotationPresent(Immutable.class)).isTrue();
        assertThat(CaseTimelineEventEntity.class.isAnnotationPresent(Immutable.class)).isTrue();
    }

    @Test
    void writesAnImmutableIdempotentMessageAndMonotonicCaseEventTogether() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "msg-1"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var message =
                messageService.post(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new RoomMessageCommand(
                                MessageType.PARTY_TEXT,
                                "物流显示签收，但我没有收到。",
                                List.of()),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "msg-1",
                        "TRACE_1");

        assertThat(message.sequenceNo()).isEqualTo(1);
        assertThat(message.messageText()).contains("没有收到");
        ArgumentCaptor<CaseTimelineEventEntity> event =
                ArgumentCaptor.forClass(CaseTimelineEventEntity.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getSequenceNo()).isEqualTo(1);
        assertThat(event.getValue().getEventType()).isEqualTo("ROOM_MESSAGE_CREATED");

        ArgumentCaptor<RoomMessageEntity> persistedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(persistedMessage.capture());
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "msg-1"))
                .thenReturn(Optional.of(persistedMessage.getValue()));

        var replayed =
                messageService.post(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new RoomMessageCommand(
                                MessageType.PARTY_TEXT,
                                "重试请求不应生成第二条消息。",
                                List.of()),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "msg-1",
                        "TRACE_2");

        assertThat(replayed.id()).isEqualTo(message.id());
        verify(messageRepository, org.mockito.Mockito.times(1)).save(any());
        verify(eventRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser() {
        FulfillmentCaseEntity dispute = evidenceCase();
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                dispute.getId(), 4L))
                .thenReturn(
                        List.of(
                                event(5, "[\"USER\",\"MERCHANT\"]", "shared"),
                                event(6, "[\"MERCHANT\"]", "merchant-private")));

        var replay =
                eventService.replay(
                        dispute.getId(),
                        4,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(replay)
                .extracting(item -> item.sequenceNo())
                .containsExactly(5L);
        assertThat(replay.getFirst().payloadJson()).contains("shared");
    }

    private static CaseTimelineEventEntity event(
            long sequenceNo, String audienceJson, String payload) {
        return CaseTimelineEventEntity.create(
                "EVENT_" + sequenceNo,
                "CASE_ROOM_TEST",
                null,
                sequenceNo,
                "ROOM_MESSAGE_CREATED",
                Instant.parse("2026-07-03T00:00:00Z"),
                "[]",
                "{\"text\":\"" + payload + "\"}",
                audienceJson,
                "event-" + sequenceNo,
                "system");
    }

    private static FulfillmentCaseEntity evidenceCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_ROOM_TEST",
                "ORDER-ROOM",
                null,
                "LOG-ROOM",
                "user-local",
                "merchant-local",
                "idem-room",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "用户表示没有收到已签收包裹",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "OMS",
                "EXT-ROOM",
                "external-adapter");
    }
}

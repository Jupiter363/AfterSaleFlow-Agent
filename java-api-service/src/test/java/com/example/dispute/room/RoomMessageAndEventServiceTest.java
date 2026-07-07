package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.CaseEventView;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class RoomMessageAndEventServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseTimelineEventRepository eventRepository;
    @Mock private IntakeAgentTurnService intakeAgentTurnService;
    @Mock private EvidenceAgentTurnService evidenceAgentTurnService;
    @Mock private AccessSessionResolver accessSessionResolver;

    private CaseEventService eventService;
    private RoomMessageService messageService;
    private final SessionPermissionService permissionService = new SessionPermissionService();

    @BeforeEach
    void setUp() {
        eventService =
                new CaseEventService(
                        eventRepository,
                        caseRepository,
                        participantRepository,
                        accessSessionResolver,
                        permissionService,
                        new ObjectMapper(),
                        CLOCK);
        messageService =
                new RoomMessageService(
                        caseRepository,
                        roomRepository,
                        participantRepository,
                        messageRepository,
                        eventService,
                        intakeAgentTurnService,
                        evidenceAgentTurnService,
                        accessSessionResolver,
                        permissionService,
                        CLOCK);
        lenient()
                .when(accessSessionResolver.resolve(any(), any()))
                .thenAnswer(
                        invocation -> {
                            String caseId = invocation.getArgument(0);
                            AuthenticatedActor actor = invocation.getArgument(1);
                            return accessSession(caseId, actor);
                        });
    }

    @Test
    void persistedMessagesAndEventsAreMappedAsImmutableAppendOnlyRecords() {
        assertThat(RoomMessageEntity.class.isAnnotationPresent(Immutable.class)).isTrue();
        assertThat(CaseTimelineEventEntity.class.isAnnotationPresent(Immutable.class)).isTrue();
    }

    @Test
    void roomMessageViewCarriesTheHearingRoundForCourtTimelineGrouping() {
        RoomMessageEntity judgeMessage =
                RoomMessageEntity.create(
                        "MESSAGE_JUDGE_ROUND_1",
                        "CASE_ROOM_TEST",
                        "ROOM_HEARING",
                        7,
                        MessageSenderType.AGENT,
                        "JUDGE",
                        "presiding-judge",
                        "[\"USER\",\"MERCHANT\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        "第一轮陈述已封存，法官将提出下一轮定向问题。",
                        "[]",
                        "judge-round-turn:CASE_ROOM_TEST:1",
                        1,
                        Instant.parse("2026-07-03T00:00:00Z"),
                        "TRACE_JUDGE_ROUND_1");
        when(caseRepository.findById(evidenceCase().getId()))
                .thenReturn(Optional.of(evidenceCase()));
        when(roomRepository.findByCaseIdAndRoomType(
                        evidenceCase().getId(), RoomType.HEARING))
                .thenReturn(
                        Optional.of(
                                CaseRoomEntity.open(
                                        "ROOM_HEARING",
                                        evidenceCase().getId(),
                                        RoomType.HEARING,
                                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                                        "system")));
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc("ROOM_HEARING"))
                .thenReturn(List.of(judgeMessage));

        List<RoomMessageView> messages =
                messageService.list(
                        evidenceCase().getId(),
                        RoomType.HEARING,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().senderRole()).isEqualTo("JUDGE");
        assertThat(messages.getFirst().hearingRound()).isEqualTo(1);
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

        RoomMessageCommand command =
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "The parcel is marked delivered, but I did not receive it.",
                        List.of());
        var message =
                messageService.post(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        command,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "msg-1",
                        "TRACE_1");

        assertThat(message.sequenceNo()).isEqualTo(1);
        assertThat(message.messageText()).contains("did not receive");
        ArgumentCaptor<CaseTimelineEventEntity> event =
                ArgumentCaptor.forClass(CaseTimelineEventEntity.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getSequenceNo()).isEqualTo(1);
        assertThat(event.getValue().getEventType()).isEqualTo("ROOM_MESSAGE_CREATED");
        verify(caseRepository, org.mockito.Mockito.times(2))
                .findByIdForUpdate(dispute.getId());

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
                        command,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "msg-1",
                        "TRACE_2");

        assertThat(replayed.id()).isEqualTo(message.id());
        verify(messageRepository, org.mockito.Mockito.times(1)).save(any());
        verify(eventRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void rejectsReusingAnIdempotencyKeyForDifferentImmutableMessageContent() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        RoomMessageEntity existing =
                roomMessage(
                        "MESSAGE_EXISTING",
                        "ROOM_EVIDENCE",
                        1,
                        ActorRole.USER,
                        MessageType.PARTY_TEXT,
                        "original statement",
                        "[\"USER\",\"MERCHANT\",\"PLATFORM_REVIEWER\"]");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "same-key"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(
                        () ->
                                messageService.post(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "attempted replacement",
                                                List.of()),
                                        new AuthenticatedActor(
                                                "user-local", ActorRole.USER),
                                        "same-key",
                                        "TRACE_CONFLICT"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different room message");
    }

    @Test
    void platformReviewerCannotPostAPartyStatement() {
        FulfillmentCaseEntity dispute = evidenceCase();
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));

        assertThatThrownBy(
                        () ->
                                messageService.post(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "Reviewer must not impersonate a party.",
                                                List.of()),
                                        new AuthenticatedActor(
                                                "reviewer-local",
                                                ActorRole.PLATFORM_REVIEWER),
                                        "reviewer-party-message",
                                        "TRACE_REVIEWER"))
                .isInstanceOf(
                        com.example.dispute.common.exception.ForbiddenException.class);
    }

    @Test
    void intakePartyTextTriggersTheIntakeAgentTurnAfterTheMessageIsPersisted() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_INTAKE",
                        dispute.getId(),
                        RoomType.INTAKE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "intake-msg-1"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RoomMessageCommand command =
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "我没有收到包裹，希望退款。",
                        List.of("PHOTO_1"));
        messageService.post(
                dispute.getId(),
                RoomType.INTAKE,
                command,
                new AuthenticatedActor("user-local", ActorRole.USER),
                "intake-msg-1",
                "TRACE_INTAKE");

        verify(intakeAgentTurnService)
                .continueFromParticipantMessage(
                        eq(dispute.getId()),
                        eq(RoomType.INTAKE),
                        eq(new AuthenticatedActor("user-local", ActorRole.USER)),
                        eq(command),
                        eq("TRACE_INTAKE"),
                        eq("TRACE_INTAKE"));
    }

    @Test
    void evidencePartyEvidenceReferenceTriggersTheEvidenceAgentTurnAfterTheMessageIsPersisted() {
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
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "merchant-local", ActorRole.MERCHANT))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "evidence-reference-msg"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RoomMessageCommand command =
                new RoomMessageCommand(
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        null,
                        List.of("EVIDENCE_UPLOAD_1"));
        messageService.post(
                dispute.getId(),
                RoomType.EVIDENCE,
                command,
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "evidence-reference-msg",
                "TRACE_EVIDENCE_REFERENCE");

        verify(evidenceAgentTurnService)
                .continueFromParticipantMessage(
                        eq(dispute.getId()),
                        eq(RoomType.EVIDENCE),
                        eq(new AuthenticatedActor("merchant-local", ActorRole.MERCHANT)),
                        eq(command),
                        eq("TRACE_EVIDENCE_REFERENCE"),
                        eq("TRACE_EVIDENCE_REFERENCE"));
    }

    @Test
    void evidencePartyTextAndReferenceMessagesAreVisibleOnlyToSamePartyAndTrustedRoles()
            throws Exception {
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
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "merchant-local", ActorRole.MERCHANT))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "user-evidence-text"))
                .thenReturn(Optional.empty());
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-evidence-reference"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId()))
                .thenReturn(0L, 1L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId()))
                .thenReturn(0L, 1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        messageService.post(
                dispute.getId(),
                RoomType.EVIDENCE,
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "User private evidence-room note for the clerk.",
                        List.of()),
                new AuthenticatedActor("user-local", ActorRole.USER),
                "user-evidence-text",
                "TRACE_USER_EVIDENCE_TEXT");
        messageService.post(
                dispute.getId(),
                RoomType.EVIDENCE,
                new RoomMessageCommand(
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        null,
                        List.of("EVIDENCE_MERCHANT_PRIVATE")),
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "merchant-evidence-reference",
                "TRACE_MERCHANT_EVIDENCE_REFERENCE");

        ArgumentCaptor<RoomMessageEntity> messages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(messages.capture());

        List<String> userAudience =
                List.of(
                        new ObjectMapper()
                                .readValue(
                                        messages.getAllValues().get(0).getAudienceJson(),
                                        String[].class));
        assertThat(userAudience)
                .containsExactly(
                        "USER",
                        "CUSTOMER_SERVICE",
                        "PLATFORM_REVIEWER",
                        "ADMIN",
                        "SYSTEM");
        assertThat(userAudience).doesNotContain("MERCHANT");

        List<String> merchantAudience =
                List.of(
                        new ObjectMapper()
                                .readValue(
                                        messages.getAllValues().get(1).getAudienceJson(),
                                        String[].class));
        assertThat(merchantAudience)
                .containsExactly(
                        "MERCHANT",
                        "CUSTOMER_SERVICE",
                        "PLATFORM_REVIEWER",
                        "ADMIN",
                        "SYSTEM");
        assertThat(merchantAudience).doesNotContain("USER");
    }

    @Test
    void intakeRoomRejectsTheNonInitiatingPartyBeforeEvidenceRoomOpens() {
        FulfillmentCaseEntity dispute = intakeCase();
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));

        assertThatThrownBy(
                        () ->
                                messageService.post(
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "I am the other side and should respond in the evidence room.",
                                                List.of()),
                                        new AuthenticatedActor(
                                                "merchant-local", ActorRole.MERCHANT),
                                        "intake-merchant-rejected",
                                        "TRACE_REJECT_NON_INITIATOR"))
                .isInstanceOf(
                        com.example.dispute.common.exception.ForbiddenException.class)
                .hasMessageContaining("only the intake initiator");

        verify(roomRepository, org.mockito.Mockito.never())
                .findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE);
        verify(messageRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void evidenceRoomStillAcceptsBothDisputePartiesAfterAdmission() {
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
                        dispute.getId(), "merchant-local", ActorRole.MERCHANT))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-evidence-msg"))
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
                                "Merchant uploads the inspection explanation in evidence room.",
                                List.of()),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                        "merchant-evidence-msg",
                        "TRACE_EVIDENCE_MERCHANT");

        assertThat(message.senderRole()).isEqualTo("MERCHANT");
        assertThat(message.messageText()).contains("inspection explanation");
    }

    @Test
    void evidenceRoomPartyMessagesAreScopedToTheSpeakingPartyAndTrustedPlatformRoles() {
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
                        dispute.getId(), "merchant-local", ActorRole.MERCHANT))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-private-evidence-chat"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        messageService.post(
                dispute.getId(),
                RoomType.EVIDENCE,
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "This is a merchant-only explanation to the evidence clerk.",
                        List.of()),
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "merchant-private-evidence-chat",
                "TRACE_EVIDENCE_PRIVATE");

        ArgumentCaptor<RoomMessageEntity> savedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(savedMessage.capture());
        assertThat(savedMessage.getValue().getAudienceJson())
                .contains("MERCHANT")
                .contains("CUSTOMER_SERVICE")
                .contains("PLATFORM_REVIEWER")
                .doesNotContain("USER");

        ArgumentCaptor<CaseTimelineEventEntity> savedEvent =
                ArgumentCaptor.forClass(CaseTimelineEventEntity.class);
        verify(eventRepository).save(savedEvent.capture());
        assertThat(savedEvent.getValue().getAudienceJson())
                .contains("MERCHANT")
                .contains("CUSTOMER_SERVICE")
                .contains("PLATFORM_REVIEWER")
                .doesNotContain("USER");
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
                .extracting(CaseEventView::sequenceNo)
                .containsExactly(5L);
        assertThat(replay.getFirst().payloadJson()).contains("shared");
    }

    @Test
    void roomHistoryFiltersPrivateMessagesByExactActorWithinTheSameRole() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(
                        List.of(
                                roomMessage(
                                        "MESSAGE_USER_OTHER_PRIVATE",
                                        room.getId(),
                                        1,
                                        ActorRole.USER,
                                        "user-other",
                                        MessageType.PARTY_TEXT,
                                        "same role but different user must not see this",
                                        "[\"USER\",\"PLATFORM_REVIEWER\"]",
                                        "[\"user-other\"]"),
                                roomMessage(
                                        "MESSAGE_USER_LOCAL_PRIVATE",
                                        room.getId(),
                                        2,
                                        ActorRole.USER,
                                        "user-local",
                                        MessageType.PARTY_TEXT,
                                        "current user's private evidence chat",
                                        "[\"USER\",\"PLATFORM_REVIEWER\"]",
                                        "[\"user-local\"]")));

        var history =
                messageService.list(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(history)
                .extracting(RoomMessageView::messageText)
                .containsExactly("current user's private evidence chat");
    }

    @Test
    void replayFiltersPrivateEventsByExactActorWithinTheSameRole() {
        FulfillmentCaseEntity dispute = evidenceCase();
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "user-local", ActorRole.USER))
                .thenReturn(true);
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                dispute.getId(), 4L))
                .thenReturn(
                        List.of(
                                event(
                                        5,
                                        "[\"USER\",\"PLATFORM_REVIEWER\"]",
                                        "[\"user-other\"]",
                                        "same-role-other-private"),
                                event(
                                        6,
                                        "[\"USER\",\"PLATFORM_REVIEWER\"]",
                                        "[\"user-local\"]",
                                        "current-user-private")));

        var replay =
                eventService.replay(
                        dispute.getId(),
                        4,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(replay)
                .extracting(CaseEventView::sequenceNo)
                .containsExactly(6L);
        assertThat(replay.getFirst().payloadJson()).contains("current-user-private");
    }

    @Test
    void subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent() {
        FulfillmentCaseEntity dispute = evidenceCase();
        AtomicInteger replayQueries = new AtomicInteger();
        CaseTimelineEventEntity historical =
                event(5, "[\"USER\",\"MERCHANT\"]", "historical");
        CaseTimelineEventEntity live =
                event(6, "[\"USER\",\"MERCHANT\"]", "live");
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(eventRepository.findByCaseIdAndEventKey(
                        dispute.getId(), "event-6"))
                .thenReturn(Optional.empty());
        when(eventRepository.findMaxSequenceByCaseId(dispute.getId())).thenReturn(5L);
        when(eventRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                dispute.getId(), 4L))
                .thenAnswer(
                        invocation -> {
                            if (replayQueries.getAndIncrement() == 0) {
                                eventService.recordLifecycleEvent(
                                        dispute.getId(),
                                        null,
                                        live.getEventType(),
                                        Map.of("text", "live"),
                                        "event-6",
                                        "system");
                                return List.of(historical);
                            }
                            return List.of(historical, live);
                        });

        SseEmitter emitter =
                eventService.subscribe(
                        dispute.getId(),
                        4,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        @SuppressWarnings("unchecked")
        Set<DataWithMediaType> earlyEvents =
                (Set<DataWithMediaType>)
                        ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        assertThat(earlyEvents)
                .isNotNull()
                .extracting(DataWithMediaType::getData)
                .filteredOn(CaseEventView.class::isInstance)
                .map(CaseEventView.class::cast)
                .extracting(CaseEventView::sequenceNo)
                .containsExactly(5L, 6L);
    }

    @Test
    void heartbeatRemovesDisconnectedSseSubscriptionWhenSendThrowsRuntimeException()
            throws ReflectiveOperationException {
        FulfillmentCaseEntity dispute = evidenceCase();
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        CaseAccessSessionEntity accessSession = accessSession(dispute.getId(), actor);
        Object subscription =
                subscription(
                        accessSession,
                        new ThrowingSseEmitter(
                                new IllegalStateException("client disconnected")),
                        0L);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, java.util.concurrent.CopyOnWriteArrayList> subscriptions =
                (Map<String, java.util.concurrent.CopyOnWriteArrayList>)
                        ReflectionTestUtils.getField(eventService, "subscriptions");
        subscriptions.put(
                dispute.getId(),
                new java.util.concurrent.CopyOnWriteArrayList<>(List.of(subscription)));
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                dispute.getId(), 0L))
                .thenReturn(List.of());

        assertThatCode(() -> eventService.heartbeat()).doesNotThrowAnyException();

        assertThat(subscriptions).doesNotContainKey(dispute.getId());
    }

    @Test
    void roomHistoryFiltersReviewerOnlyMessagesForAParty() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(
                        List.of(
                                roomMessage(
                                        "MESSAGE_SHARED",
                                        room.getId(),
                                        1,
                                        ActorRole.USER,
                                        MessageType.PARTY_TEXT,
                                        "shared",
                                        "[\"USER\",\"MERCHANT\",\"PLATFORM_REVIEWER\"]"),
                                roomMessage(
                                        "MESSAGE_REVIEWER",
                                        room.getId(),
                                        2,
                                        ActorRole.PLATFORM_REVIEWER,
                                        MessageType.REVIEWER_NOTE,
                                        "reviewer only",
                                        "[\"PLATFORM_REVIEWER\"]")));

        var history =
                messageService.list(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(history)
                .extracting(item -> item.messageText())
                .containsExactly("shared");

        var adminHistory =
                messageService.list(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("admin-local", ActorRole.ADMIN));

        assertThat(adminHistory)
                .extracting(item -> item.messageText())
                .containsExactly("shared", "reviewer only");
    }

    @Test
    void intakeHistoryIsHiddenFromTheNonInitiatingParty() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_INTAKE",
                        dispute.getId(),
                        RoomType.INTAKE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(
                        dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));

        var history =
                messageService.list(
                        dispute.getId(),
                        RoomType.INTAKE,
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT));

        assertThat(history).isEmpty();
    }

    private static CaseTimelineEventEntity event(
            long sequenceNo, String audienceJson, String payload) {
        return event(sequenceNo, audienceJson, "[]", payload);
    }

    private static CaseTimelineEventEntity event(
            long sequenceNo, String audienceJson, String audienceActorIdsJson, String payload) {
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
                audienceActorIdsJson,
                "event-" + sequenceNo,
                "system");
    }

    private static RoomMessageEntity roomMessage(
            String id,
            String roomId,
            long sequenceNo,
            ActorRole senderRole,
            MessageType messageType,
            String text,
            String audienceJson) {
        return roomMessage(
                id,
                roomId,
                sequenceNo,
                senderRole,
                senderRole == ActorRole.USER ? "user-local" : "reviewer-local",
                messageType,
                text,
                audienceJson,
                "[]");
    }

    private static RoomMessageEntity roomMessage(
            String id,
            String roomId,
            long sequenceNo,
            ActorRole senderRole,
            String senderId,
            MessageType messageType,
            String text,
            String audienceJson,
            String audienceActorIdsJson) {
        return RoomMessageEntity.create(
                id,
                "CASE_ROOM_TEST",
                roomId,
                sequenceNo,
                senderRole == ActorRole.PLATFORM_REVIEWER
                        ? MessageSenderType.REVIEWER
                        : MessageSenderType.PARTY,
                senderRole.name(),
                senderId,
                audienceJson,
                audienceActorIdsJson,
                messageType,
                text,
                "[]",
                "idem-" + id,
                Instant.parse("2026-07-03T00:00:00Z"),
                "TRACE_" + id);
    }

    private static CaseAccessSessionEntity accessSession(String caseId, AuthenticatedActor actor) {
        return CaseAccessSessionEntity.create(
                "ACCESS_" + caseId + "_" + actor.role().name() + "_" + actor.actorId(),
                "default",
                caseId,
                actor.actorId(),
                actor.role(),
                permissionLevel(actor.role()),
                "test");
    }

    private static Object subscription(
            CaseAccessSessionEntity accessSession, SseEmitter emitter, long lastSequence)
            throws ReflectiveOperationException {
        Class<?> subscriptionType = null;
        for (Class<?> nestedType : CaseEventService.class.getDeclaredClasses()) {
            if ("Subscription".equals(nestedType.getSimpleName())) {
                subscriptionType = nestedType;
                break;
            }
        }
        if (subscriptionType == null) {
            throw new IllegalStateException("CaseEventService.Subscription not found");
        }
        var constructor =
                subscriptionType.getDeclaredConstructor(
                        CaseAccessSessionEntity.class, SseEmitter.class, AtomicLong.class);
        constructor.setAccessible(true);
        return constructor.newInstance(accessSession, emitter, new AtomicLong(lastSequence));
    }

    private static final class ThrowingSseEmitter extends SseEmitter {
        private final RuntimeException failure;

        private ThrowingSseEmitter(RuntimeException failure) {
            super(60_000L);
            this.failure = failure;
        }

        @Override
        public void send(SseEventBuilder builder) throws java.io.IOException {
            throw failure;
        }
    }

    private static PermissionLevel permissionLevel(ActorRole role) {
        return switch (role) {
            case USER -> PermissionLevel.PARTY_USER;
            case MERCHANT -> PermissionLevel.PARTY_MERCHANT;
            case CUSTOMER_SERVICE -> PermissionLevel.SERVICE_ASSIST;
            case PLATFORM_REVIEWER -> PermissionLevel.REVIEWER_ALL;
            case ADMIN -> PermissionLevel.ADMIN_ALL;
            case SYSTEM -> PermissionLevel.SYSTEM_ALL;
        };
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
                "Marked delivered but not received",
                "The user states that the signed parcel was never received.",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "OMS",
                "EXT-ROOM",
                "external-adapter");
    }

    private static FulfillmentCaseEntity intakeCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_INTAKE_ROOM_TEST",
                "ORDER-ROOM",
                null,
                "LOG-ROOM",
                "user-local",
                "merchant-local",
                "idem-room-intake",
                "SIGNED_NOT_RECEIVED",
                "Marked delivered but not received",
                "The user states that the signed parcel was never received.",
                RiskLevel.HIGH,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                "OMS",
                "EXT-ROOM-INTAKE",
                "external-adapter");
    }
}

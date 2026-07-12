package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.IntakeAgentTurnClient;
import com.example.dispute.room.application.IntakeAgentTurnCommand;
import com.example.dispute.room.application.IntakeAgentTurnResult;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class IntakeAgentTurnServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private RoomTurnMemoryRepository memoryRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseEventService eventService;
    @Mock private AccessSessionResolver accessSessionResolver;
    @Mock private AgentSessionResolver agentSessionResolver;
    @Mock private SessionPermissionService permissionService;
    @Mock private IntakeAgentTurnClient client;

    private ObjectMapper objectMapper;
    private IntakeAgentTurnService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service =
                new IntakeAgentTurnService(
                        caseRepository,
                        roomRepository,
                        memoryRepository,
                        intakeDossierRepository,
                        messageRepository,
                        eventService,
                        accessSessionResolver,
                        agentSessionResolver,
                        permissionService,
                        client,
                        objectMapper,
                        CLOCK);
        lenient()
                .when(accessSessionResolver.resolve(any(), any()))
                .thenAnswer(
                        invocation ->
                                accessSession(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1)));
        lenient()
                .when(agentSessionResolver.resolve(any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation ->
                                agentSession(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        invocation.getArgument(2),
                                        invocation.getArgument(3),
                                        invocation.getArgument(4)));
    }

    @Test
    void initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.empty());
        when(client.run(any(), eq("TRACE_1"), eq("REQ_1")))
                .thenReturn(
                        result(
                                "我先确认一下：你是说订单显示已签收但没有收到，对吗？",
                                Map.of("current_outcome", "ASK_FOR_CLARIFICATION")));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String originalStatement = "  我没有收到这个包裹，请核验签收记录。\n";
        service.startInitialTurn(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeLobbySeed(
                        "ORDER-1",
                        "AFTER-1",
                        "LOG-1",
                        "USER",
                        "物流轨迹显示订单已签收，但发起方表示未收到包裹。",
                        null,
                        new IntakeLobbySeed.ClaimResolutionSeed(
                                "USER",
                                "REFUND",
                                null,
                                "蓝牙耳机 1 件",
                                "未收到商品，希望退款。",
                                originalStatement),
                        null),
                "TRACE_1",
                "REQ_1");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_1"), eq("REQ_1"));
        assertThat(command.getValue().caseId()).isEqualTo(dispute.getId());
        assertThat(command.getValue().turnSource()).isEqualTo("EXTERNAL_IMPORT");
        assertThat(command.getValue().currentUserMessage().source())
                .isEqualTo("EXTERNAL_IMPORT");
        assertThat(command.getValue().currentUserMessage().text())
                .isEqualTo("物流轨迹显示订单已签收，但发起方表示未收到包裹。");
        assertThat(command.getValue().recentDialogueMessages()).isEmpty();
        assertThat(command.getValue().initiatorStatementTranscript())
                .singleElement()
                .satisfies(
                        statement -> {
                            assertThat(statement.messageId()).isEqualTo("INTAKE_TURN_1");
                            assertThat(statement.role()).isEqualTo("USER");
                            assertThat(statement.text())
                                    .isEqualTo("物流轨迹显示订单已签收，但发起方表示未收到包裹。");
                        });
        assertThat(command.getValue().previousCaseDetail().isObject()).isTrue();
        assertThat(command.getValue().agentContext().actorId()).isEqualTo("user-local");
        assertThat(command.getValue().agentContext().actorRole()).isEqualTo("USER");
        assertThat(command.getValue().agentContext().agentKey())
                .isEqualTo("DISPUTE_INTAKE_OFFICER");
        assertThat(command.getValue().agentContext().agentSessionId()).isNotBlank();
        assertThat(command.getValue().agentContext().scopeType())
                .isEqualTo("INTAKE_INITIATOR_PRIVATE");

        ArgumentCaptor<RoomTurnMemoryEntity> memory =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(memory.capture());
        assertThat(memory.getAllValues().get(0).getAnswerRole()).isEqualTo("USER");
        assertThat(memory.getAllValues().get(0).getAnswerContent())
                .isEqualTo("物流轨迹显示订单已签收，但发起方表示未收到包裹。");
        assertThat(memory.getAllValues().get(1).getAgentRole())
                .isEqualTo("DISPUTE_INTAKE_OFFICER");
        assertThat(memory.getAllValues().get(1).getTurnNo()).isEqualTo(1);
        assertThat(memory.getAllValues().get(1).getScrollSnapshotJson())
                .contains("ASK_FOR_CLARIFICATION");
        assertThat(memory.getAllValues().get(1).getDossierPatchJson())
                .contains("\"memory_frame\"")
                .contains("\"prompt_memory\":\"short memory\"");

        ArgumentCaptor<CaseIntakeDossierEntity> currentDossier =
                ArgumentCaptor.forClass(CaseIntakeDossierEntity.class);
        verify(intakeDossierRepository).save(currentDossier.capture());
        assertThat(currentDossier.getValue().getCaseId()).isEqualTo(dispute.getId());
        assertThat(currentDossier.getValue().getDossierVersion()).isEqualTo(1);
        assertThat(currentDossier.getValue().getQualityScore()).isEqualTo(86);
        assertThat(currentDossier.getValue().isReadyForNextStep()).isTrue();
        assertThat(currentDossier.getValue().getDossierJson())
                .contains("intake_case_detail.v1")
                .contains("ASK_FOR_CLARIFICATION");

        ArgumentCaptor<RoomMessageEntity> roomMessages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(roomMessages.capture());
        assertThat(roomMessages.getAllValues().get(0).getMessageType())
                .isEqualTo(MessageType.PARTY_TEXT);
        assertThat(roomMessages.getAllValues().get(0).getMessageText())
                .isEqualTo("物流轨迹显示订单已签收，但发起方表示未收到包裹。");
        assertThat(roomMessages.getAllValues().get(1).getMessageType())
                .isEqualTo(MessageType.AGENT_MESSAGE);
    }

    @Test
    void initialTurnParticipatesInTheCallerTransactionSoFreshCasesCanBeSeeded()
            throws NoSuchMethodException {
        Transactional transactional =
                IntakeAgentTurnService.class
                        .getMethod(
                                "startInitialTurn",
                                String.class,
                                AuthenticatedActor.class,
                                IntakeLobbySeed.class,
                                String.class,
                                String.class)
                        .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    @Test
    void initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.empty());
        when(client.run(any(), eq("TRACE_SHORT_INITIAL"), eq("REQ_SHORT_INITIAL")))
                .thenReturn(
                        result(
                                "我会先补齐缺失的订单、售后和物流引用。",
                                Map.of("admission_recommendation", "NEED_MORE_INFO")));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.startInitialTurn(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeLobbySeed(
                        "2",
                        "2",
                        "2",
                        "USER",
                        "物流显示签收，但我没有收到包裹。",
                        null),
                "TRACE_SHORT_INITIAL",
                "REQ_SHORT_INITIAL");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_SHORT_INITIAL"), eq("REQ_SHORT_INITIAL"));
        assertThat(command.getValue().initialCaseFacts().orderReference()).isNull();
        assertThat(command.getValue().initialCaseFacts().afterSalesReference()).isNull();
        assertThat(command.getValue().initialCaseFacts().logisticsReference()).isNull();
        assertThat(command.getValue().initiatorStatementTranscript())
                .singleElement()
                .satisfies(
                        statement ->
                                assertThat(statement.text())
                                        .isEqualTo("物流显示签收，但我没有收到包裹。"));
    }

    @Test
    void participantTurnPersistsUserAnswerAndSendsPreviousScrollSnapshotToAgent() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        RoomTurnMemoryEntity previous =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_PREVIOUS",
                        dispute.getId(),
                        RoomType.INTAKE,
                        1,
                        "dispute-intake-officer",
                        "DISPUTE_INTAKE_OFFICER",
                        "我先确认一下签收争议。",
                        "{}",
                        "{\"current_outcome\":\"ASK_FOR_CLARIFICATION\"}",
                        "[]",
                        "RUN_1");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(1);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.of(previous));
        when(client.run(any(), eq("TRACE_2"), eq("REQ_2")))
                .thenReturn(
                        result(
                                "收到，我已经把你的诉求更新为退款，并准备继续核验商家侧信息。",
                                Map.of("requested_outcome", "REFUND")));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_EXISTING",
                        dispute.getId(),
                        RoomType.INTAKE,
                        "{\"schema_version\":\"intake_case_detail.v1\"}",
                        60,
                        false,
                        "NEED_MORE_INFO",
                        1,
                        "dispute-intake-officer")));
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.INTAKE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                participantMessage(
                        dispute,
                        room,
                        2,
                        "MESSAGE_CURRENT_2",
                        "我确认没收到，诉求是退款。"),
                "TRACE_2",
                "REQ_2");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_2"), eq("REQ_2"));
        assertThat(command.getValue().turnSource()).isEqualTo("ROOM_MESSAGE");
        assertThat(command.getValue().currentUserMessage().messageId()).isEqualTo("MESSAGE_CURRENT_2");
        assertThat(command.getValue().currentUserMessage().text()).contains("退款");
        assertThat(command.getValue().previousCaseDetail().path("current_outcome").asText())
                .isEqualTo("ASK_FOR_CLARIFICATION");
        assertThat(command.getValue().recentDialogueMessages()).hasSizeLessThanOrEqualTo(6);

        ArgumentCaptor<RoomTurnMemoryEntity> memories =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(memories.capture());
        assertThat(memories.getAllValues().get(0).getAnswerRole()).isEqualTo("USER");
        assertThat(memories.getAllValues().get(0).getAnswerContent()).contains("退款");
        assertThat(memories.getAllValues().get(1).getAgentRole())
                .isEqualTo("DISPUTE_INTAKE_OFFICER");
        assertThat(memories.getAllValues().get(1).getScrollSnapshotJson())
                .contains("REFUND");

        ArgumentCaptor<CaseIntakeDossierEntity> currentDossier =
                ArgumentCaptor.forClass(CaseIntakeDossierEntity.class);
        verify(intakeDossierRepository).save(currentDossier.capture());
        assertThat(currentDossier.getValue().getDossierVersion()).isEqualTo(2);
        assertThat(currentDossier.getValue().getSourceTurnNo()).isEqualTo(2);
    }

    @Test
    void participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        CaseAccessSessionEntity accessSession = accessSession(dispute.getId(), actor);
        AgentConversationSessionEntity agentSession =
                agentSession(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "intake-user-v1",
                        "MEMEO_DEFAULT");
        List<RoomTurnMemoryEntity> participantMemories = new ArrayList<>();
        List<String> expectedStatements = new ArrayList<>();
        for (int turnNo = 1; turnNo <= 11; turnNo++) {
            String statement = "第 " + turnNo + " 条发起方原始输入";
            expectedStatements.add(statement);
            participantMemories.add(
                    RoomTurnMemoryEntity.participantTurn(
                            "MEMORY_HISTORY_" + turnNo,
                            dispute.getId(),
                            RoomType.INTAKE,
                            turnNo,
                            actor.actorId(),
                            actor.role().name(),
                            statement,
                            agentSession,
                            accessSession,
                            "{}"));
        }
        List<RoomMessageEntity> visibleHistory = new ArrayList<>();
        for (int round = 1; round <= 4; round++) {
            long userSequence = round * 2L - 1L;
            visibleHistory.add(
                    participantMessage(
                            dispute,
                            room,
                            userSequence,
                            "MESSAGE_HISTORY_USER_" + round,
                            "历史用户陈述 " + round));
            visibleHistory.add(
                    agentMessage(
                            dispute,
                            room,
                            userSequence + 1,
                            "MESSAGE_HISTORY_AGENT_" + round,
                            "历史接待官回复 " + round));
        }
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(11);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.empty());
        when(memoryRepository
                        .findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(any()))
                .thenAnswer(invocation -> List.copyOf(participantMemories));
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(visibleHistory);
        when(client.run(any(), eq("TRACE_TRANSCRIPT"), eq("REQ_TRANSCRIPT")))
                .thenReturn(
                        result(
                                "已记录全部原始陈述。",
                                Map.of("requested_outcome", "REFUND")));
        when(memoryRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            RoomTurnMemoryEntity saved = invocation.getArgument(0);
                            if (saved.getAnswerContent() != null) {
                                participantMemories.add(saved);
                            }
                            return saved;
                        });
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String latestStatement = "\n我的第 12 条原始补充，保留首尾空白。  ";
        expectedStatements.add(latestStatement);
        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.INTAKE,
                actor,
                participantMessage(
                        dispute,
                        room,
                        12,
                        "MESSAGE_CURRENT_12",
                        latestStatement),
                "TRACE_TRANSCRIPT",
                "REQ_TRANSCRIPT");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_TRANSCRIPT"), eq("REQ_TRANSCRIPT"));
        assertThat(command.getValue().recentDialogueMessages())
                .hasSize(6)
                .first()
                .satisfies(
                        message -> {
                            assertThat(message.sequenceNo()).isEqualTo(3);
                            assertThat(message.role()).isEqualTo("USER");
                        });
        assertThat(command.getValue().recentDialogueMessages()).last()
                .satisfies(
                        message -> {
                            assertThat(message.sequenceNo()).isEqualTo(8);
                            assertThat(message.role()).isEqualTo("AGENT");
                        });
        assertThat(command.getValue().initiatorStatementTranscript())
                .hasSize(12)
                .extracting(message -> message.text())
                .containsExactlyElementsOf(expectedStatements);
        assertThat(command.getValue().initiatorStatementTranscript())
                .extracting(message -> message.messageId())
                .containsExactly(
                        "INTAKE_TURN_1",
                        "INTAKE_TURN_2",
                        "INTAKE_TURN_3",
                        "INTAKE_TURN_4",
                        "INTAKE_TURN_5",
                        "INTAKE_TURN_6",
                        "INTAKE_TURN_7",
                        "INTAKE_TURN_8",
                        "INTAKE_TURN_9",
                        "INTAKE_TURN_10",
                        "INTAKE_TURN_11",
                        "INTAKE_TURN_12");
        assertThat(command.getValue().initiatorStatementTranscript())
                .allSatisfy(message -> assertThat(message.role()).isEqualTo("USER"));
    }

    @Test
    void participantTurnOmitsShortExternalReferencesBeforeCallingAgent() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_INTAKE_SHORT_REFS",
                        "2",
                        "2",
                        "2",
                        "user-local",
                        "merchant-local",
                        "idem-short-refs",
                        "SIGNED_NOT_RECEIVED",
                        "履约问题待处理",
                        "物流显示签收，但我没有收到包裹。",
                        RiskLevel.MEDIUM,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-short-refs",
                        "system");
        CaseRoomEntity room = intakeRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.empty());
        when(client.run(any(), eq("TRACE_SHORT"), eq("REQ_SHORT")))
                .thenReturn(
                        result(
                                "我会继续补齐缺失的订单、售后和物流引用。",
                                Map.of("admission_recommendation", "NEED_MORE_INFO")));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.INTAKE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                participantMessage(
                        dispute,
                        room,
                        2,
                        "MESSAGE_SHORT",
                        "我补充：希望平台核实物流并退款。"),
                "TRACE_SHORT",
                "REQ_SHORT");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_SHORT"), eq("REQ_SHORT"));
        assertThat(command.getValue().initialCaseFacts().orderReference()).isNull();
        assertThat(command.getValue().initialCaseFacts().afterSalesReference()).isNull();
        assertThat(command.getValue().initialCaseFacts().logisticsReference()).isNull();
    }

    @Test
    void participantTurnPersistsTraceableDegradedMemoryWhenAgentCallFails() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        RoomTurnMemoryEntity previous =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_PREVIOUS",
                        dispute.getId(),
                        RoomType.INTAKE,
                        1,
                        "dispute-intake-officer",
                        "DISPUTE_INTAKE_OFFICER",
                        "我先确认一下签收争议。",
                        "{}",
                        "{\"current_outcome\":\"ASK_FOR_CLARIFICATION\"}",
                        "[]",
                        "RUN_1");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(1);
        when(memoryRepository
                        .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(any()))
                .thenReturn(Optional.of(previous));
        when(client.run(any(), eq("TRACE_FAIL"), eq("REQ_FAIL")))
                .thenThrow(new RuntimeException("python returned 422"));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.INTAKE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                participantMessage(
                        dispute,
                        room,
                        2,
                        "MESSAGE_FAIL",
                        "我继续补充：商家回复让我找快递，但快递说只能让商家发起核查。"),
                "TRACE_FAIL",
                "REQ_FAIL");

        ArgumentCaptor<RoomTurnMemoryEntity> memories =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(memories.capture());
        RoomTurnMemoryEntity degradedMemory = memories.getAllValues().get(1);
        assertThat(degradedMemory.getAgentResponse())
                .contains("接待官暂时没有完成智能整理");
        assertThat(degradedMemory.getDossierPatchJson())
                .contains("\"agent_degraded\":true")
                .contains("\"agent_degraded_reason\":\"AGENT_CALL_FAILED\"")
                .contains("\"trace_id\":\"TRACE_FAIL\"");
        assertThat(degradedMemory.getScrollSnapshotJson())
                .contains("ASK_FOR_CLARIFICATION");
    }

    private IntakeAgentTurnResult result(String utterance, Map<String, Object> scrollSnapshot) {
        Map<String, Object> caseDetail =
                Map.of(
                        "schema_version", "intake_case_detail.v1",
                        "case_story", Map.of("title", "签收未收到"),
                        "intake_quality",
                                Map.of(
                                        "score", 86,
                                        "threshold", 80,
                                        "ready_for_next_step", true),
                        "admission", Map.of("recommendation", "ACCEPTED"),
                        "legacy", scrollSnapshot);
        return new IntakeAgentTurnResult(
                utterance,
                objectMapper.valueToTree(Map.of("summary", utterance)),
                objectMapper.valueToTree(caseDetail),
                objectMapper.valueToTree(List.of(Map.of("op", "UPSERT_CARD"))),
                objectMapper.valueToTree(Map.of("prompt_memory", "short memory")),
                "CONTINUE",
                List.of(),
                false,
                "STUB",
                0.86);
    }

    private static RoomMessageEntity participantMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            long sequence,
            String messageId,
            String text) {
        return RoomMessageEntity.create(
                messageId,
                dispute.getId(),
                room.getId(),
                sequence,
                MessageSenderType.PARTY,
                ActorRole.USER.name(),
                "user-local",
                "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                "[\"user-local\"]",
                MessageType.PARTY_TEXT,
                text,
                "[]",
                "test-intake-message:" + messageId,
                CLOCK.instant(),
                "TRACE_TEST");
    }

    private static RoomMessageEntity agentMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            long sequence,
            String messageId,
            String text) {
        return RoomMessageEntity.create(
                messageId,
                dispute.getId(),
                room.getId(),
                sequence,
                MessageSenderType.AGENT,
                "CUSTOMER_SERVICE",
                "dispute-intake-officer",
                "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                "[\"user-local\"]",
                MessageType.AGENT_MESSAGE,
                text,
                "[]",
                "agent-intake-turn:test:" + sequence,
                CLOCK.instant(),
                "TRACE_TEST");
    }

    private static CaseAccessSessionEntity accessSession(String caseId, AuthenticatedActor actor) {
        PermissionLevel level =
                actor.role() == ActorRole.MERCHANT
                        ? PermissionLevel.PARTY_MERCHANT
                        : PermissionLevel.PARTY_USER;
        return CaseAccessSessionEntity.create(
                "ACCESS_" + actor.actorId(),
                "default",
                caseId,
                actor.actorId(),
                actor.role(),
                level,
                actor.actorId());
    }

    private static AgentConversationSessionEntity agentSession(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return AgentConversationSessionEntity.create(
                "AGENT_SESSION_" + accessSession.getActorId() + "_" + roomType.name(),
                accessSession,
                roomType,
                agentKey,
                promptProfileId,
                memoryPolicyId,
                accessSession.getActorId());
    }

    private static FulfillmentCaseEntity intakeCase() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_INTAKE_AGENT",
                        "ORDER-1",
                        "AFTER-1",
                        "LOG-1",
                        "user-local",
                        "merchant-local",
                        "idem-intake-agent",
                        "SIGNED_NOT_RECEIVED",
                        "签收未收到",
                        "用户表示订单显示签收但没有收到包裹。",
                        RiskLevel.HIGH,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-1",
                        "system");
        return dispute;
    }

    private static CaseRoomEntity intakeRoom(FulfillmentCaseEntity dispute) {
        return CaseRoomEntity.open(
                "ROOM_INTAKE_AGENT",
                dispute.getId(),
                RoomType.INTAKE,
                OffsetDateTime.parse("2026-07-05T00:00:00Z"),
                "system");
    }
}

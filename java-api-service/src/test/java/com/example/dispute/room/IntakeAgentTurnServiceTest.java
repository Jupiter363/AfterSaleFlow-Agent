package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.IntakeAgentTurnClient;
import com.example.dispute.room.application.IntakeAgentTurnCommand;
import com.example.dispute.room.application.IntakeAgentTurnResult;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
                        client,
                        objectMapper,
                        CLOCK);
    }

    @Test
    void initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot() {
        FulfillmentCaseEntity dispute = intakeCase();
        CaseRoomEntity room = intakeRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.INTAKE))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                                dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(memoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.INTAKE))
                .thenReturn(List.of());
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

        service.startInitialTurn(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeLobbySeed(
                        "ORDER-1",
                        "AFTER-1",
                        "LOG-1",
                        "USER",
                        "显示签收但我没有收到包裹",
                        null),
                "TRACE_1",
                "REQ_1");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_1"), eq("REQ_1"));
        assertThat(command.getValue().caseId()).isEqualTo(dispute.getId());
        assertThat(command.getValue().turnSource()).isEqualTo("LOBBY_SEED");
        assertThat(command.getValue().lobbySeed().rawText()).contains("签收");
        assertThat(command.getValue().latestScrollSnapshot().isObject()).isTrue();

        ArgumentCaptor<RoomTurnMemoryEntity> memory =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository).save(memory.capture());
        assertThat(memory.getValue().getAgentRole()).isEqualTo("DISPUTE_INTAKE_OFFICER");
        assertThat(memory.getValue().getTurnNo()).isEqualTo(1);
        assertThat(memory.getValue().getScrollSnapshotJson())
                .contains("ASK_FOR_CLARIFICATION");
        assertThat(memory.getValue().getDossierPatchJson())
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
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.INTAKE))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                                dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(memoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.INTAKE))
                .thenReturn(List.of());
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
        assertThat(command.getValue().lobbySeed().orderReference()).isNull();
        assertThat(command.getValue().lobbySeed().afterSalesReference()).isNull();
        assertThat(command.getValue().lobbySeed().logisticsReference()).isNull();
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
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.INTAKE))
                .thenReturn(1);
        when(memoryRepository
                        .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                                dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(previous));
        when(memoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.INTAKE))
                .thenReturn(List.of(previous));
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
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "我确认没收到，诉求是退款。",
                        List.of("PHOTO_1")),
                "TRACE_2",
                "REQ_2");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_2"), eq("REQ_2"));
        assertThat(command.getValue().turnSource()).isEqualTo("USER_MESSAGE");
        assertThat(command.getValue().currentUserMessage().messageId()).isEqualTo("INTAKE_TURN_2");
        assertThat(command.getValue().currentUserMessage().text()).contains("退款");
        assertThat(command.getValue().latestScrollSnapshot().path("current_outcome").asText())
                .isEqualTo("ASK_FOR_CLARIFICATION");

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
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.INTAKE))
                .thenReturn(0);
        when(memoryRepository
                        .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                                dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(memoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.INTAKE))
                .thenReturn(List.of());
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
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "我补充：希望平台核实物流并退款。",
                        List.of()),
                "TRACE_SHORT",
                "REQ_SHORT");

        ArgumentCaptor<IntakeAgentTurnCommand> command =
                ArgumentCaptor.forClass(IntakeAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_SHORT"), eq("REQ_SHORT"));
        assertThat(command.getValue().lobbySeed().orderReference()).isNull();
        assertThat(command.getValue().lobbySeed().afterSalesReference()).isNull();
        assertThat(command.getValue().lobbySeed().logisticsReference()).isNull();
        assertThat(command.getValue().lobbySeed().rawText()).contains("物流显示签收");
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
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.INTAKE))
                .thenReturn(1);
        when(memoryRepository
                        .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                                dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(previous));
        when(memoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.INTAKE))
                .thenReturn(List.of(previous));
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
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "我继续补充：商家回复让我找快递，但快递说只能让商家发起核查。",
                        List.of()),
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

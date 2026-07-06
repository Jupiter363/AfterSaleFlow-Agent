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
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.EvidenceAgentTurnClient;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceAgentTurnResult;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.room.application.IntakeRecentTurn;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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

@ExtendWith(MockitoExtension.class)
class EvidenceAgentTurnServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private RoomTurnMemoryRepository memoryRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;
    @Mock private EvidenceItemRepository evidenceItemRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseEventService eventService;
    @Mock private EvidenceAgentTurnClient client;

    private ObjectMapper objectMapper;
    private EvidenceAgentTurnService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service =
                new EvidenceAgentTurnService(
                        caseRepository,
                        roomRepository,
                        memoryRepository,
                        intakeDossierRepository,
                        evidenceItemRepository,
                        messageRepository,
                        eventService,
                        client,
                        objectMapper,
                        CLOCK);
    }

    @Test
    void partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        RoomTurnMemoryEntity previousParticipantTurn =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_PREVIOUS_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "user-local",
                        "USER",
                        "I previously described the missing parcel.");
        RoomTurnMemoryEntity previousClerkTurn =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_PREVIOUS_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "Please upload the delivery photo.",
                        "{}",
                        "{}",
                        "[]",
                        "EVIDENCE_RUN_1");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(2);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(
                        Optional.of(
                                CaseIntakeDossierEntity.create(
                                        "INTAKE_DOSSIER_EVIDENCE_AGENT",
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        "{\"schema_version\":\"intake_case_detail.v1\",\"case_story\":{\"title\":\"Signed not received\"}}",
                                        90,
                                        true,
                                        "ACCEPTED",
                                        1,
                                        "dispute-intake-officer")));
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(
                        List.of(
                                evidenceItem(
                                        "EVIDENCE_USER_PRIVATE",
                                        "USER",
                                        "user-local",
                                        "PRIVATE"),
                                evidenceItem(
                                        "EVIDENCE_MERCHANT_PRIVATE",
                                        "MERCHANT",
                                        "merchant-local",
                                        "PRIVATE"),
                                evidenceItem(
                                        "EVIDENCE_SHARED",
                                        "MERCHANT",
                                        "merchant-local",
                                        "PARTIES")));
        when(memoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(List.of(previousClerkTurn, previousParticipantTurn));
        when(client.run(any(), eq("TRACE_EVIDENCE"), eq("REQ_EVIDENCE")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "I can help organize this evidence. Please add any carrier response if available.",
                                objectMapper.readTree("{\"next_best_action\":\"ADD_CARRIER_RESPONSE\"}"),
                                objectMapper.readTree("[]"),
                                List.of("EVIDENCE_SHARED"),
                                false,
                                false,
                                "STUB",
                                0.78));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(7L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "The parcel status says signed, but my front door camera shows no delivery.",
                        List.of("PHOTO_DOOR_CAMERA")),
                "TRACE_EVIDENCE",
                "REQ_EVIDENCE");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_EVIDENCE"), eq("REQ_EVIDENCE"));
        assertThat(command.getValue().caseId()).isEqualTo(dispute.getId());
        assertThat(command.getValue().roomType()).isEqualTo(RoomType.EVIDENCE);
        assertThat(command.getValue().turnSource()).isEqualTo("PARTY_MESSAGE");
        assertThat(command.getValue().actorRole()).isEqualTo("USER");
        assertThat(command.getValue().actorId()).isEqualTo("user-local");
        assertThat(command.getValue().currentMessage().messageType())
                .isEqualTo(MessageType.PARTY_TEXT);
        assertThat(command.getValue().currentMessage().text()).contains("front door camera");
        assertThat(command.getValue().latestCaseIntakeDossier().path("schema_version").asText())
                .isEqualTo("intake_case_detail.v1");
        assertThat(command.getValue().availableEvidence())
                .extracting(EvidenceAgentTurnCommand.AvailableEvidence::evidenceId)
                .containsExactly("EVIDENCE_USER_PRIVATE", "EVIDENCE_SHARED");
        assertThat(command.getValue().recentTurns())
                .extracting(turn -> turn.agentRole())
                .contains("EVIDENCE_CLERK");
        JsonNode commandJson = objectMapper.valueToTree(command.getValue());
        assertThat(commandJson.has("current_party_message")).isTrue();
        assertThat(commandJson.has("current_message")).isFalse();
        assertThat(commandJson.has("case_intake_dossier")).isTrue();
        assertThat(commandJson.has("latest_case_intake_dossier")).isFalse();
        assertThat(commandJson.path("current_party_message").path("message_type").asText())
                .isEqualTo("PARTY_TEXT");
        JsonNode serializedEvidence = commandJson.path("available_evidence").get(0);
        assertThat(serializedEvidence.path("evidence_id").asText())
                .isEqualTo("EVIDENCE_USER_PRIVATE");
        assertThat(serializedEvidence.path("content").asText())
                .contains("EVIDENCE_USER_PRIVATE.jpg");
        assertThat(serializedEvidence.has("submitted_by_role")).isTrue();
        assertThat(serializedEvidence.has("content_url")).isTrue();
        assertThat(serializedEvidence.has("parse_status")).isTrue();
        assertThat(serializedEvidence.has("redacted")).isTrue();
        assertThat(serializedEvidence.has("evidenceId")).isFalse();

        ArgumentCaptor<RoomTurnMemoryEntity> memories =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(memories.capture());
        RoomTurnMemoryEntity participantMemory = memories.getAllValues().get(0);
        assertThat(participantMemory.getRoomType()).isEqualTo(RoomType.EVIDENCE);
        assertThat(participantMemory.getTurnNo()).isEqualTo(3);
        assertThat(participantMemory.getActorId()).isEqualTo("user-local");
        assertThat(participantMemory.getAnswerRole()).isEqualTo("USER");
        assertThat(participantMemory.getAnswerContent()).contains("front door camera");
        RoomTurnMemoryEntity clerkMemory = memories.getAllValues().get(1);
        assertThat(clerkMemory.getRoomType()).isEqualTo(RoomType.EVIDENCE);
        assertThat(clerkMemory.getTurnNo()).isEqualTo(3);
        assertThat(clerkMemory.getActorId()).isEqualTo("evidence-clerk");
        assertThat(clerkMemory.getAgentRole()).isEqualTo("EVIDENCE_CLERK");
        assertThat(clerkMemory.getAgentResponse()).contains("organize this evidence");

        ArgumentCaptor<RoomMessageEntity> agentMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(agentMessage.capture());
        assertThat(agentMessage.getValue().getSequenceNo()).isEqualTo(8);
        assertThat(agentMessage.getValue().getSenderRole()).isEqualTo("CUSTOMER_SERVICE");
        assertThat(agentMessage.getValue().getSenderId()).isEqualTo("evidence-clerk");
        assertThat(agentMessage.getValue().getMessageType()).isEqualTo(MessageType.AGENT_MESSAGE);
        List<String> audience =
                objectMapper.readValue(
                        agentMessage.getValue().getAudienceJson(), new TypeReference<>() {});
        assertThat(audience)
                .containsExactly(
                        "USER",
                        "CUSTOMER_SERVICE",
                        "PLATFORM_REVIEWER",
                        "ADMIN",
                        "SYSTEM");
        assertThat(audience).doesNotContain("MERCHANT");
        verify(eventService)
                .recordRoomMessage(
                        eq(dispute.getId()),
                        eq(room.getId()),
                        eq(agentMessage.getValue().getId()),
                        eq(agentMessage.getValue().getMessageText()),
                        eq(agentMessage.getValue().getAudienceJson()),
                        eq("evidence-clerk"));
    }

    @Test
    void evidenceAgentRecentTurnsAreScopedToTheSpeakingParty() throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        RoomTurnMemoryEntity userParticipant =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_USER_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "user-local",
                        "USER",
                        "用户侧私聊：门口监控显示没有投递。");
        RoomTurnMemoryEntity userClerk =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_USER_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "用户侧书记官回复：请补充门口监控原视频。",
                        "{}",
                        "{}",
                        "[]",
                        "EVIDENCE_RUN_USER");
        RoomTurnMemoryEntity merchantParticipant =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_MERCHANT_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "merchant-local",
                        "MERCHANT",
                        "商家侧私聊：发货质检视频显示完好。");
        RoomTurnMemoryEntity merchantClerk =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_MERCHANT_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "商家侧书记官回复：请补充质检视频原文件。",
                        "{}",
                        "{}",
                        "[]",
                        "EVIDENCE_RUN_MERCHANT");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(2);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(List.of(merchantClerk, merchantParticipant, userClerk, userParticipant));
        when(client.run(any(), eq("TRACE_USER_SCOPED"), eq("REQ_USER_SCOPED")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "我会继续围绕用户侧材料核验，不判断责任。",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of(),
                                false,
                                false,
                                "STUB",
                                0.75));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(5L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "用户侧本轮：我可以补充监控原视频。",
                        List.of()),
                "TRACE_USER_SCOPED",
                "REQ_USER_SCOPED");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_USER_SCOPED"), eq("REQ_USER_SCOPED"));

        assertThat(command.getValue().recentTurns())
                .extracting(IntakeRecentTurn::answerContent)
                .doesNotContain("商家侧私聊：发货质检视频显示完好。");
        assertThat(command.getValue().recentTurns())
                .extracting(IntakeRecentTurn::agentResponse)
                .doesNotContain("商家侧书记官回复：请补充质检视频原文件。");
        assertThat(command.getValue().recentTurns())
                .extracting(IntakeRecentTurn::answerContent)
                .contains("用户侧私聊：门口监控显示没有投递。");
        assertThat(command.getValue().recentTurns())
                .extracting(IntakeRecentTurn::agentResponse)
                .contains("用户侧书记官回复：请补充门口监控原视频。");
    }

    @Test
    void partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(0);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_REFERENCE"), eq("REQ_REFERENCE")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "I noted this evidence reference for your side.",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of("EVIDENCE_UPLOAD_1"),
                                false,
                                false,
                                "STUB",
                                0.7));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                new RoomMessageCommand(
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        null,
                        List.of("EVIDENCE_UPLOAD_1")),
                "TRACE_REFERENCE",
                "REQ_REFERENCE");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_REFERENCE"), eq("REQ_REFERENCE"));
        assertThat(command.getValue().currentMessage().messageType())
                .isEqualTo(MessageType.PARTY_EVIDENCE_REFERENCE);
        assertThat(command.getValue().currentMessage().attachmentRefs())
                .containsExactly("EVIDENCE_UPLOAD_1");

        ArgumentCaptor<RoomTurnMemoryEntity> memories =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(memories.capture());
        assertThat(memories.getAllValues().get(0).getAnswerContent())
                .contains("EVIDENCE_UPLOAD_1");

        ArgumentCaptor<RoomMessageEntity> agentMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(agentMessage.capture());
        assertThat(agentMessage.getValue().getAudienceJson())
                .contains("MERCHANT")
                .doesNotContain("USER");
    }

    @Test
    void failedEvidenceAgentCallFallsBackToChineseClerkMessage() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNo(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(0);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(
                        dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_DEGRADED"), eq("REQ_DEGRADED")))
                .thenThrow(new IllegalStateException("agent endpoint missing"));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "我会上传开箱照片原图。",
                        List.of()),
                "TRACE_DEGRADED",
                "REQ_DEGRADED");

        ArgumentCaptor<RoomMessageEntity> agentMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(agentMessage.capture());
        assertThat(agentMessage.getValue().getMessageText())
                .contains("证据书记官")
                .contains("已经安全保存")
                .doesNotContain("temporarily unavailable");
    }

    private static EvidenceItemEntity evidenceItem(
            String id, String submittedByRole, String submittedById, String visibility) {
        return EvidenceItemEntity.uploaded(
                id,
                "CASE_EVIDENCE_AGENT",
                "DOSSIER_1",
                "PHOTO",
                "UPLOAD",
                submittedByRole,
                submittedById,
                "bucket",
                "object-" + id,
                "hash-" + id,
                id + ".jpg",
                "image/jpeg",
                128L,
                visibility,
                OffsetDateTime.parse("2026-07-06T00:00:00Z"));
    }

    private static FulfillmentCaseEntity evidenceCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_EVIDENCE_AGENT",
                "ORDER-EVIDENCE",
                "AFTER-EVIDENCE",
                "LOG-EVIDENCE",
                "user-local",
                "merchant-local",
                "idem-evidence-agent",
                "SIGNED_NOT_RECEIVED",
                "Signed but not received",
                "The user says the parcel was marked signed but never arrived.",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-06T02:00:00Z"),
                "OMS",
                "EXT-EVIDENCE",
                "system");
    }

    private static CaseRoomEntity evidenceRoom(FulfillmentCaseEntity dispute) {
        return CaseRoomEntity.open(
                "ROOM_EVIDENCE_AGENT",
                dispute.getId(),
                RoomType.EVIDENCE,
                OffsetDateTime.parse("2026-07-06T00:00:00Z"),
                "system");
    }
}

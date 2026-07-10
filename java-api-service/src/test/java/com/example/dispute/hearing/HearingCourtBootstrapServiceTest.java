package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.HearingCourtBootstrapService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
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
class HearingCourtBootstrapServiceTest {

    private static final String CASE_ID = "CASE_BOOTSTRAP";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-08T01:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;
    @Mock private EvidenceDossierRepository evidenceDossierRepository;
    @Mock private HearingStateRepository hearingStateRepository;
    @Mock private HearingRecordRepository hearingRecordRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseEventService eventService;
    @Mock private HearingRoundService hearingRoundService;

    private HearingCourtBootstrapService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service =
                new HearingCourtBootstrapService(
                        caseRepository,
                        roomRepository,
                        intakeDossierRepository,
                        evidenceDossierRepository,
                        hearingStateRepository,
                        hearingRecordRepository,
                        messageRepository,
                        eventService,
                        hearingRoundService,
                        objectMapper,
                        CLOCK);
    }

    @Test
    void bootstrapsTheHearingByFreezingPriorDossiersAndAppendingOpeningMessages()
            throws Exception {
        FulfillmentCaseEntity dispute = hearingCase();
        CaseRoomEntity room = hearingRoom();
        HearingStateEntity state =
                HearingStateEntity.start(
                        "HEARING_STATE_BOOTSTRAP",
                        CASE_ID,
                        "hearing-bootstrap-" + CASE_ID,
                        "hearing-bootstrap");
        CaseIntakeDossierEntity intakeDossier =
                CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_1",
                        CASE_ID,
                        RoomType.INTAKE,
                        """
                        {
                          "case_story": {
                            "title": "签收未收到争议",
                            "one_sentence_summary": "接待室确认：物流签收但用户未实际收到。"
                          },
                          "dispute_focus": {"summary": "签收真实性与实际收货状态"},
                          "claim_resolution": {
                            "initiator_role": "USER",
                            "requested_resolution": "REFUND",
                            "requested_amount": 299,
                            "requested_items": "儿童手表 1 件",
                            "request_reason": "用户称物流显示签收但本人未收到包裹，希望退款。",
                            "original_statement": "我没收到包裹，希望退款",
                            "normalized_statement": "用户称未实际收到包裹，并请求退款。"
                          },
                          "respondent_attitude": {
                            "respondent_role": "MERCHANT",
                            "attitude": "NOT_RESPONDED",
                            "position": "商家尚未在接待室表达态度。",
                            "source": "尚未回应",
                            "confidence": 0.5
                          },
                          "dispute_core_state": {
                            "core_conflict": "用户请求退款，但商家态度尚待补充。",
                            "conflict_type": "CLAIM_UNANSWERED",
                            "facts_in_dispute": ["用户是否实际收到商品"],
                            "next_verification_focus": ["签收人身份"]
                          },
                          "intake_quality": {"score": 86}
                        }
                        """,
                        86,
                        true,
                        "ADMIT",
                        5,
                        "intake-agent");
        EvidenceDossierEntity evidenceDossier =
                EvidenceDossierEntity.frozen(
                        "EVIDENCE_DOSSIER_3",
                        CASE_ID,
                        3,
                        "evidence-clerk",
                        """
                        {
                          "evidence_count": 2,
                          "overall_confidence_score": 76,
                          "handoff_notes": "证据支持物流已签收，但不足以证明用户本人实际收到。"
                        }
                        """,
                        "[{\"time\":\"2025-03-02\",\"event\":\"物流显示签收\"}]",
                        "[{\"fact\":\"用户是否实际收到商品\",\"evidence_strength\":\"MEDIUM\"}]");
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(hearingStateRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(state));
        when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(intakeDossier));
        when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
                .thenReturn(Optional.of(evidenceDossier));
        when(hearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
                        state.getWorkflowId(),
                        "C0_COURT_BOOTSTRAP",
                        1,
                        "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(false);
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(CASE_ID), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L, 1L, 2L, 3L);
        when(hearingRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.bootstrap(
                CASE_ID,
                new AuthenticatedActor("user-local", ActorRole.USER),
                "TRACE_BOOTSTRAP");

        ArgumentCaptor<HearingRecordEntity> record =
                ArgumentCaptor.forClass(HearingRecordEntity.class);
        verify(hearingRecordRepository).save(record.capture());
        assertThat(record.getValue().getRecordType()).isEqualTo("BOOTSTRAP_DOSSIER_SNAPSHOT");
        JsonNode snapshot = objectMapper.readTree(record.getValue().getOutputJson());
        assertThat(snapshot.path("case_id").asText()).isEqualTo(CASE_ID);
        assertThat(snapshot.path("round_no").asInt()).isEqualTo(1);
        assertThat(snapshot.path("round_stage").asText()).isEqualTo("FACT_STATEMENT");
        assertThat(snapshot.path("intake_dossier").path("case_story").asText())
                .contains("接待室确认");
        assertThat(snapshot.path("intake_dossier").path("claim_resolution")
                        .path("requested_resolution").asText())
                .isEqualTo("REFUND");
        assertThat(snapshot.path("intake_dossier").path("respondent_attitude")
                        .path("attitude").asText())
                .isEqualTo("NOT_RESPONDED");
        assertThat(snapshot.path("intake_dossier").path("dispute_core_state")
                        .path("core_conflict").asText())
                .contains("用户请求退款");
        assertThat(snapshot.path("evidence_dossier").path("fact_evidence_matrix").get(0)
                        .path("fact").asText())
                .contains("用户是否实际收到商品");

        ArgumentCaptor<RoomMessageEntity> messages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(4)).save(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(RoomMessageEntity::getSenderRole)
                .containsExactly("JUDGE", "INTAKE_OFFICER", "EVIDENCE_CLERK", "JUDGE");
        assertThat(messages.getAllValues())
                .extracting(RoomMessageEntity::getMessageType)
                .containsOnly(MessageType.AGENT_MESSAGE);
        assertThat(messages.getAllValues())
                .extracting(RoomMessageEntity::getHearingRound)
                .containsOnly(1);
        assertThat(messages.getAllValues().get(0).getMessageText())
                .contains("现在开庭");
        assertThat(messages.getAllValues().get(0).getMessageText())
                .doesNotContain("平台审核")
                .doesNotContain("审核员")
                .contains("后续确认");
        assertThat(messages.getAllValues().get(1).getMessageText())
                .contains("案情接待官宣读");
        assertThat(messages.getAllValues().get(2).getMessageText())
                .contains("证据书记官宣读");
        assertThat(messages.getAllValues().get(3).getMessageText())
                .contains("第 1 轮事实陈述");
        verify(hearingRoundService)
                .ensureInitialRoundOpen(CASE_ID, 3, "hearing-bootstrap");
    }

    @Test
    void repeatedBootstrapDoesNotDuplicateSnapshotOrOpeningMessages() {
        FulfillmentCaseEntity dispute = hearingCase();
        CaseRoomEntity room = hearingRoom();
        HearingStateEntity state =
                HearingStateEntity.start(
                        "HEARING_STATE_BOOTSTRAP",
                        CASE_ID,
                        "hearing-bootstrap-" + CASE_ID,
                        "hearing-bootstrap");
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(hearingStateRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(state));
        when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
                .thenReturn(Optional.empty());
        when(hearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
                        state.getWorkflowId(),
                        "C0_COURT_BOOTSTRAP",
                        1,
                        "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(true);
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(CASE_ID), any()))
                .thenReturn(Optional.of(existingMessage(room, "JUDGE")));
        when(evidenceDossierRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.bootstrap(
                CASE_ID,
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "TRACE_BOOTSTRAP_REPLAY");

        verify(hearingRecordRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
        verify(hearingRoundService)
                .ensureInitialRoundOpen(CASE_ID, 1, "hearing-bootstrap");
    }

    @Test
    void bootstrapCreatesAnEmptyEvidenceBaselineWhenNoEvidenceDossierExists() {
        FulfillmentCaseEntity dispute = hearingCase();
        CaseRoomEntity room = hearingRoom();
        HearingStateEntity state =
                HearingStateEntity.start(
                        "HEARING_STATE_BOOTSTRAP",
                        CASE_ID,
                        "hearing-bootstrap-" + CASE_ID,
                        "hearing-bootstrap");
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(hearingStateRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(state));
        when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
                .thenReturn(Optional.empty());
        when(hearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
                        state.getWorkflowId(),
                        "C0_COURT_BOOTSTRAP",
                        1,
                        "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(false);
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(CASE_ID), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L, 1L, 2L, 3L);
        when(evidenceDossierRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(hearingRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.bootstrap(
                CASE_ID,
                new AuthenticatedActor("user-local", ActorRole.USER),
                "TRACE_BOOTSTRAP_EMPTY_EVIDENCE");

        ArgumentCaptor<EvidenceDossierEntity> baseline =
                ArgumentCaptor.forClass(EvidenceDossierEntity.class);
        verify(evidenceDossierRepository).save(baseline.capture());
        assertThat(baseline.getValue().getCaseId()).isEqualTo(CASE_ID);
        assertThat(baseline.getValue().getDossierVersion()).isEqualTo(1);
        assertThat(baseline.getValue().getDossierStatus()).isEqualTo("FROZEN");
        assertThat(baseline.getValue().getSummaryJson())
                .contains("\"evidence_count\":0")
                .contains("evidence_items");
        assertThat(baseline.getValue().getMatrixSummaryJson())
                .contains("fact_evidence_matrix")
                .contains("evidence_gaps");
        verify(hearingRoundService)
                .ensureInitialRoundOpen(CASE_ID, 1, "hearing-bootstrap");
    }

    @Test
    void alreadyBootstrappedPostHearingCasesRemainReadableWithoutOpeningANewCourtSession() {
        FulfillmentCaseEntity dispute = postHearingReviewCase();
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(hearingStateRepository.findByCaseId(CASE_ID))
                .thenReturn(
                        Optional.of(
                                HearingStateEntity.start(
                                        "HEARING_STATE_BOOTSTRAP",
                                        CASE_ID,
                                        "hearing-bootstrap-" + CASE_ID,
                                        "hearing-bootstrap")));

        service.bootstrap(
                CASE_ID,
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "TRACE_BOOTSTRAP_REVIEW_READ");

        verify(roomRepository, never()).findByCaseIdAndRoomType(any(), any());
        verify(intakeDossierRepository, never()).findByCaseIdAndRoomType(any(), any());
        verify(evidenceDossierRepository, never()).findTopByCaseIdOrderByDossierVersionDesc(any());
        verify(hearingRecordRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
        verify(hearingRoundService, never()).ensureInitialRoundOpen(any(), any(int.class), any());
    }

    @Test
    void evidenceReadingLocalizesUnmappedMatrixRowsInsteadOfLeakingBackendFields() {
        FulfillmentCaseEntity dispute = hearingCase();
        CaseRoomEntity room = hearingRoom();
        HearingStateEntity state =
                HearingStateEntity.start(
                        "HEARING_STATE_BOOTSTRAP",
                        CASE_ID,
                        "hearing-bootstrap-" + CASE_ID,
                        "hearing-bootstrap");
        EvidenceDossierEntity evidenceDossier =
                EvidenceDossierEntity.frozen(
                        "EVIDENCE_DOSSIER_1",
                        CASE_ID,
                        1,
                        "evidence-clerk",
                        "{\"evidence_count\":1}",
                        "[]",
                        """
                        [
                          {
                            "evidence_id": "EVIDENCE_UNMAPPED",
                            "relation_type": "UNMAPPED",
                            "verification_status": "UNVERIFIED"
                          }
                        ]
                        """);
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(hearingStateRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(state));
        when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
                .thenReturn(Optional.of(evidenceDossier));
        when(hearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
                        state.getWorkflowId(),
                        "C0_COURT_BOOTSTRAP",
                        1,
                        "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(false);
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(CASE_ID), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L, 1L, 2L, 3L);
        when(hearingRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.bootstrap(
                CASE_ID,
                new AuthenticatedActor("user-local", ActorRole.USER),
                "TRACE_BOOTSTRAP_LOCALIZED");

        ArgumentCaptor<RoomMessageEntity> messages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(4)).save(messages.capture());
        RoomMessageEntity evidenceReading =
                messages.getAllValues().stream()
                        .filter(message -> "EVIDENCE_CLERK".equals(message.getSenderRole()))
                        .findFirst()
                        .orElseThrow();
        assertThat(evidenceReading.getMessageText()).contains("尚未映射到具体争议事实");
        assertThat(evidenceReading.getMessageText()).contains("待核验");
        assertThat(evidenceReading.getMessageText())
                .doesNotContain("evidence_id")
                .doesNotContain("relation_type")
                .doesNotContain("verification_status")
                .doesNotContain("UNMAPPED")
                .doesNotContain("UNVERIFIED");
    }

    private static FulfillmentCaseEntity hearingCase() {
        return FulfillmentCaseEntity.imported(
                CASE_ID,
                "ORDER-BOOTSTRAP",
                "AS-BOOTSTRAP",
                "LOG-BOOTSTRAP",
                "user-local",
                "merchant-local",
                "idem-bootstrap",
                "SIGNED_NOT_RECEIVED",
                "物流签收争议",
                "物流记录显示包裹已签收，但用户称本人未收到商品。",
                RiskLevel.MEDIUM,
                CaseStatus.HEARING_OPEN,
                "HEARING",
                OffsetDateTime.parse("2026-07-08T04:00:00Z"),
                "OMS",
                "EXT-BOOTSTRAP",
                "external-adapter");
    }

    private static FulfillmentCaseEntity postHearingReviewCase() {
        return FulfillmentCaseEntity.imported(
                CASE_ID,
                "ORDER-BOOTSTRAP",
                "AS-BOOTSTRAP",
                "LOG-BOOTSTRAP",
                "user-local",
                "merchant-local",
                "idem-bootstrap-review",
                "SIGNED_NOT_RECEIVED",
                "鐗╂祦绛炬敹浜夎",
                "鐗╂祦璁板綍鏄剧ず鍖呰９宸茬鏀讹紝浣嗙敤鎴风О鏈汉鏈敹鍒板晢鍝併€?",
                RiskLevel.MEDIUM,
                CaseStatus.WAITING_HUMAN_REVIEW,
                "HEARING",
                OffsetDateTime.parse("2026-07-08T04:00:00Z"),
                "OMS",
                "EXT-BOOTSTRAP-REVIEW",
                "external-adapter");
    }

    private static CaseRoomEntity hearingRoom() {
        return CaseRoomEntity.open(
                "ROOM_HEARING_BOOTSTRAP",
                CASE_ID,
                RoomType.HEARING,
                OffsetDateTime.parse("2026-07-08T01:00:00Z"),
                "system");
    }

    private static RoomMessageEntity existingMessage(CaseRoomEntity room, String senderRole) {
        return RoomMessageEntity.create(
                "MESSAGE_EXISTING_" + senderRole,
                CASE_ID,
                room.getId(),
                1,
                com.example.dispute.room.domain.MessageSenderType.AGENT,
                senderRole,
                senderRole.toLowerCase(),
                "[\"USER\",\"MERCHANT\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                "[]",
                MessageType.AGENT_MESSAGE,
                "existing",
                "[]",
                "existing-key",
                1,
                CLOCK.instant(),
                "TRACE_EXISTING");
    }
}

package com.example.dispute.caseintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.caseintake.application.AgentServiceClient;
import com.example.dispute.caseintake.application.CaseApplicationService;
import com.example.dispute.caseintake.application.CaseView;
import com.example.dispute.caseintake.application.CreateCaseCommand;
import com.example.dispute.caseintake.application.IntakeAnalysis;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
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
class CaseApplicationServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private AgentServiceClient agentServiceClient;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private ParticipantService participantService;
    @Mock private IntakeAgentTurnService intakeAgentTurnService;

    private CaseApplicationService service;

    @BeforeEach
    void setUp() {
        AppProperties properties =
                new AppProperties(
                        "test",
                        new AppProperties.Security("secret"),
                        new AppProperties.Integration("http://agent", "secret", 100),
                        new AppProperties.Integration("http://ocr", "secret", 100),
                        new AppProperties.Temporal("localhost:7233", "default", "queue"),
                        new AppProperties.Minio(
                                "http://minio",
                                "key",
                                "secret",
                                "evidence-original",
                                "evidence-desensitized"),
                        new AppProperties.Elasticsearch("http://elasticsearch"),
                        new AppProperties.Feature(true, true, true, true, true, true, false),
                        new AppProperties.Logging(true, true));
        service =
                new CaseApplicationService(
                        caseRepository,
                        auditLogRepository,
                        agentServiceClient,
                        roomRepository,
                        participantService,
                        intakeAgentTurnService,
                        properties,
                        Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC),
                        new ObjectMapper().findAndRegisterModules());
        lenient()
                .when(caseRepository.findByCreationIdempotencyKey(any()))
                .thenReturn(Optional.empty());
        lenient()
                .when(caseRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsDisputeCaseFromStructuredAgentAnalysisAndAuditsIt() {
        when(agentServiceClient.analyze(any(), any(), any()))
                .thenReturn(
                        new IntakeAnalysis(
                                "DISPUTE",
                                "NON_RECEIPT",
                                RiskLevel.HIGH,
                                true,
                                List.of(),
                                "物流签收争议",
                                "用户与物流状态存在冲突"));

        CaseView result =
                service.create(
                        command("订单 order-1 显示签收，但我没有收到", "order-1"),
                        new AuthenticatedActor("user-1", ActorRole.USER),
                        "idem-dispute",
                        "TRACE_test",
                        "REQ_test");

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.INTAKE_COMPLETED);
        assertThat(result.initiatorRole()).isEqualTo(ActorRole.USER);
        assertThat(result.potentialDispute()).isTrue();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.agentDegraded()).isFalse();
        assertThat(result.missingSlots()).isEmpty();
        verify(roomRepository).save(any(CaseRoomEntity.class));
        verify(participantService)
                .addInitiator(
                        any(),
                        any(AuthenticatedActor.class),
                        any());
        ArgumentCaptor<IntakeLobbySeed> lobbySeed =
                ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(),
                        any(AuthenticatedActor.class),
                        lobbySeed.capture(),
                        eq("TRACE_test"),
                        eq("REQ_test"));
        assertThat(lobbySeed.getValue().orderReference()).isEqualTo("order-1");
        assertThat(lobbySeed.getValue().initiatorRole()).isEqualTo("USER");
        assertThat(lobbySeed.getValue().rawText()).contains("order-1");

        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("CASE_CREATED");
        assertThat(audit.getValue().getTraceId()).isEqualTo("TRACE_test");
    }

    @Test
    void missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome() {
        when(agentServiceClient.analyze(any(), any(), any()))
                .thenReturn(
                        new IntakeAnalysis(
                                "TRANSFERRED",
                                null,
                                RiskLevel.LOW,
                                false,
                                List.of("ORDER_ID"),
                                "查询物流",
                                "需要订单号后继续处理"));

        CaseView result =
                service.create(
                        command("帮我查一下包裹到哪里了", null),
                        new AuthenticatedActor("user-1", ActorRole.USER),
                        "idem-missing",
                        "TRACE_test",
                        "REQ_test");

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.WAITING_SLOT_COMPLETION);
        assertThat(result.missingSlots()).containsExactly("ORDER_ID");
        assertThat(result.description()).doesNotContain("承诺退款", "同意补发", "驳回");
    }

    @Test
    void agentFailureFallsBackToDeterministicSafeIntake() {
        when(agentServiceClient.analyze(any(), any(), any()))
                .thenThrow(new IllegalStateException("timeout"));

        CaseView result =
                service.create(
                        command("订单 order-2 的物流一直没有更新", "order-2"),
                        new AuthenticatedActor("user-1", ActorRole.USER),
                        "idem-fallback",
                        "TRACE_test",
                        "REQ_test");

        assertThat(result.agentDegraded()).isTrue();
        assertThat(result.caseType()).isEqualTo("DISPUTE");
        assertThat(result.caseStatus()).isEqualTo(CaseStatus.INTAKE_COMPLETED);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.potentialDispute()).isTrue();
    }

    @Test
    void userCannotCreateCaseForAnotherUser() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        command("查询订单", "order-3"),
                                        new AuthenticatedActor("other-user", ActorRole.USER),
                                        "idem-forbidden",
                                        "TRACE_test",
                                        "REQ_test"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator() {
        when(agentServiceClient.analyze(any(), any(), any()))
                .thenReturn(
                        new IntakeAnalysis(
                                "DISPUTE",
                                "QUALITY_DISPUTE",
                                RiskLevel.MEDIUM,
                                true,
                                List.of(),
                                "商家发起质检争议",
                                "商家认为用户提交的故障描述与售后检测不一致。"));

        CaseView result =
                service.create(
                        new CreateCaseCommand(
                                "order-merchant",
                                null,
                                null,
                                "user-1",
                                "merchant-1",
                                "用户提交故障视频，但商家认为需要平台接待官介入核验。",
                                List.of(),
                                "WEB",
                                ActorRole.MERCHANT),
                        new AuthenticatedActor("merchant-1", ActorRole.MERCHANT),
                        "idem-merchant-initiator",
                        "TRACE_merchant",
                        "REQ_merchant");

        assertThat(result.initiatorRole()).isEqualTo(ActorRole.MERCHANT);
        ArgumentCaptor<IntakeLobbySeed> lobbySeed =
                ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(),
                        any(AuthenticatedActor.class),
                        lobbySeed.capture(),
                        eq("TRACE_merchant"),
                        eq("REQ_merchant"));
        assertThat(lobbySeed.getValue().initiatorRole()).isEqualTo("MERCHANT");
    }

    @Test
    void notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant() {
        FulfillmentCaseEntity merchantInitiated =
                FulfillmentCaseEntity.create(
                        "CASE_MERCHANT_INITIATED",
                        "ORDER_MERCHANT_INITIATED",
                        null,
                        null,
                        "user-local",
                        "merchant-local",
                        ActorRole.MERCHANT,
                        "merchant-initiated-idempotency",
                        "DISPUTE",
                        "商家发起的争议",
                        "商家认为用户提交的证据需要平台核验。",
                        RiskLevel.MEDIUM,
                        "merchant-local");
        when(caseRepository.findById("CASE_MERCHANT_INITIATED"))
                .thenReturn(Optional.of(merchantInitiated));

        CaseView result =
                service.get(
                        "CASE_MERCHANT_INITIATED",
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(result.userId()).isEqualTo("user-local");
        assertThat(result.merchantId()).isEqualTo("merchant-local");
        assertThat(result.initiatorRole()).isEqualTo(ActorRole.MERCHANT);
    }

    @Test
    void privilegedCreatorIsMappedToSystemForTheIntakeTurnContract() {
        when(agentServiceClient.analyze(any(), any(), any()))
                .thenReturn(
                        new IntakeAnalysis(
                                "DISPUTE",
                                "NON_RECEIPT",
                                RiskLevel.MEDIUM,
                                true,
                                List.of(),
                                "平台代创建争议",
                                "用户通过客服发起争议。"));

        service.create(
                command("customer service creates an intake case", "order-admin"),
                new AuthenticatedActor("reviewer-1", ActorRole.PLATFORM_REVIEWER),
                "idem-reviewer-created",
                "TRACE_reviewer",
                "REQ_reviewer");

        ArgumentCaptor<IntakeLobbySeed> lobbySeed =
                ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(),
                        any(AuthenticatedActor.class),
                        lobbySeed.capture(),
                        eq("TRACE_reviewer"),
                        eq("REQ_reviewer"));
        assertThat(lobbySeed.getValue().initiatorRole()).isEqualTo("SYSTEM");
    }

    @Test
    void passesLogisticsReferenceIntoTheIntakeTurnSeed() {
        when(agentServiceClient.analyze(any(), any(), any()))
                .thenReturn(
                        new IntakeAnalysis(
                                "DISPUTE",
                                "SIGNED_NOT_RECEIVED",
                                RiskLevel.MEDIUM,
                                true,
                                List.of(),
                                "物流签收争议",
                                "物流显示签收但用户未收到。"));

        service.create(
                commandWithLogistics(
                        "物流显示签收，但我没有收到包裹。",
                        "order-with-logistics",
                        "LOG-TRACK-1"),
                new AuthenticatedActor("user-1", ActorRole.USER),
                "idem-logistics",
                "TRACE_logistics",
                "REQ_logistics");

        ArgumentCaptor<IntakeLobbySeed> lobbySeed =
                ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(),
                        any(AuthenticatedActor.class),
                        lobbySeed.capture(),
                        eq("TRACE_logistics"),
                        eq("REQ_logistics"));
        assertThat(lobbySeed.getValue().logisticsReference()).isEqualTo("LOG-TRACK-1");
    }


    @Test
    void normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType() {
        FulfillmentCaseEntity legacy =
                FulfillmentCaseEntity.create(
                        "CASE_LEGACY",
                        "ORDER_LEGACY",
                        null,
                        "user-1",
                        "merchant-1",
                        "legacy-idempotency",
                        "FULFILLMENT_DISPUTE",
                        "历史争议订单",
                        "迁移前创建的履约争议。",
                        RiskLevel.MEDIUM,
                        "system");
        when(caseRepository.findById("CASE_LEGACY"))
                .thenReturn(Optional.of(legacy));

        CaseView result =
                service.get(
                        "CASE_LEGACY",
                        new AuthenticatedActor("user-1", ActorRole.USER));

        assertThat(result.caseType()).isEqualTo("DISPUTE");
    }

    @Test
    void admittingTransferredIntakeCasePromotesItToDispute() {
        FulfillmentCaseEntity transferred =
                FulfillmentCaseEntity.create(
                        "CASE_TRANSFERRED",
                        "ORDER_TRANSFERRED",
                        null,
                        "user-1",
                        "merchant-1",
                        "transferred-idempotency",
                        "TRANSFERRED",
                        "接待官待判断",
                        "创建时尚未确定是否构成履约争端。",
                        RiskLevel.MEDIUM,
                        "user-1");

        transferred.admitToEvidence(
                "NON_RECEIPT",
                RiskLevel.HIGH,
                "{\"potentialDispute\":true,\"missingSlots\":[],\"agentDegraded\":false,\"analyzedAt\":\"2026-07-03T00:00:00Z\"}",
                java.time.OffsetDateTime.parse("2026-07-03T14:00:00Z"),
                "user-1");

        assertThat(transferred.getCaseType()).isEqualTo("DISPUTE");
    }

    private static CreateCaseCommand command(String description, String orderId) {
        return new CreateCaseCommand(
                orderId,
                null,
                null,
                "user-1",
                "merchant-1",
                description,
                List.of(),
                "WEB",
                ActorRole.USER);
    }

    private static CreateCaseCommand commandWithLogistics(
            String description, String orderId, String logisticsId) {
        return new CreateCaseCommand(
                orderId,
                null,
                logisticsId,
                "user-1",
                "merchant-1",
                description,
                List.of(),
                "WEB",
                ActorRole.USER);
    }
}

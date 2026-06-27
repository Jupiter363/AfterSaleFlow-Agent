package com.example.dispute.caseintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

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
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
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
                        new AppProperties.Minio("http://minio", "key", "secret"),
                        new AppProperties.Elasticsearch("http://elasticsearch"),
                        new AppProperties.Feature(true, true, true, true, true, true, false),
                        new AppProperties.Logging(true, true));
        service =
                new CaseApplicationService(
                        caseRepository,
                        auditLogRepository,
                        agentServiceClient,
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
        assertThat(result.potentialDispute()).isTrue();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.agentDegraded()).isFalse();
        assertThat(result.missingSlots()).isEmpty();

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
                                "REGULAR_FULFILLMENT",
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
        assertThat(result.caseStatus()).isEqualTo(CaseStatus.INTAKE_COMPLETED);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.potentialDispute()).isFalse();
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

    private static CreateCaseCommand command(String description, String orderId) {
        return new CreateCaseCommand(
                orderId,
                null,
                "user-1",
                "merchant-1",
                description,
                List.of(),
                "WEB");
    }
}

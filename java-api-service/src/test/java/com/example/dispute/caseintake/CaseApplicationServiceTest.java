/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：验证案件应用，覆盖 「createsDeterministicShellAndStartsTheSingleIntakeAgentTurn」、「structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools」、「missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome」、「creationDoesNotPrecomputeAnAgentSummary」、「userCannotCreateCaseForAnotherUser」、「merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.caseintake.application.AgentServiceClient;
import com.example.dispute.caseintake.application.CaseApplicationService;
import com.example.dispute.caseintake.application.CaseView;
import com.example.dispute.caseintake.application.CreateCaseCommand;
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
import java.math.BigDecimal;
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

// 所属模块：【案件受理兼容链路 / 自动化测试层】类型「CaseApplicationServiceTest」。
// 类型职责：集中验证案件应用的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「createsDeterministicShellAndStartsTheSingleIntakeAgentTurn」、「structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools」、「missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome」、「creationDoesNotPrecomputeAnAgentSummary」、「userCannotCreateCaseForAnotherUser」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class CaseApplicationServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private AgentServiceClient agentServiceClient;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private ParticipantService participantService;
    @Mock private IntakeAgentTurnService intakeAgentTurnService;

    private CaseApplicationService service;

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.setUp()」。
    // 具体功能：「CaseApplicationServiceTest.setUp()」：在每个测试场景运行前创建「caseRepository.findByCreationIdempotencyKey」、「caseRepository.save」、「Clock.fixed」、「Instant.parse」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「CaseApplicationServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「CaseApplicationServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseApplicationServiceTest.setUp()」守住「案件受理兼容链路」的可执行规格，尤其防止 「test」、「secret」、「localhost:7233」、「default」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.createsDeterministicShellAndStartsTheSingleIntakeAgentTurn()」。
    // 具体功能：「CaseApplicationServiceTest.createsDeterministicShellAndStartsTheSingleIntakeAgentTurn()」：复现“创建并持久化（场景方法「createsDeterministicShellAndStartsTheSingleIntakeAgentTurn」）”场景：驱动 「service.create」，再用 「assertThat」、「verify」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「order-1」、「user-1」、「idem-dispute」、「TRACE_test」。
    // 上游调用：「CaseApplicationServiceTest.createsDeterministicShellAndStartsTheSingleIntakeAgentTurn()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.createsDeterministicShellAndStartsTheSingleIntakeAgentTurn()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.createsDeterministicShellAndStartsTheSingleIntakeAgentTurn()」守住「案件受理兼容链路」的可执行规格，尤其防止 「order-1」、「user-1」、「idem-dispute」、「TRACE_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void createsDeterministicShellAndStartsTheSingleIntakeAgentTurn() {
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
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
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
        assertThat(result.description()).isEqualTo("订单 order-1 显示签收，但我没有收到");
        verifyNoInteractions(agentServiceClient);

        ArgumentCaptor<AuditLogEntity> audit = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("CASE_CREATED");
        assertThat(audit.getValue().getTraceId()).isEqualTo("TRACE_test");
    }

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools()」。
    // 具体功能：「CaseApplicationServiceTest.structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools()」：复现“核对完整业务行为（场景方法「structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools」）”场景：驱动 「service.create」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「我没收到包裹，希望退款」、「ORDER-CLAIM-1」、「USER」、「REFUND」。
    // 上游调用：「CaseApplicationServiceTest.structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools()」守住「案件受理兼容链路」的可执行规格，尤其防止 「我没收到包裹，希望退款」、「ORDER-CLAIM-1」、「USER」、「REFUND」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools() {
        service.create(
                commandWithClaim(
                        "我没收到包裹，希望退款",
                        "ORDER-CLAIM-1",
                        new IntakeLobbySeed.ClaimResolutionSeed(
                                "USER",
                                "REFUND",
                                new BigDecimal("299"),
                                "儿童手表 1 件",
                                "物流显示签收但用户本人没有收到包裹，希望退款。",
                                "我没收到包裹，希望退款")),
                new AuthenticatedActor("user-1", ActorRole.USER),
                "idem-claim-seed",
                "TRACE_claim",
                "REQ_claim");

        ArgumentCaptor<IntakeLobbySeed> lobbySeed =
                ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(),
                        any(AuthenticatedActor.class),
                        lobbySeed.capture(),
                        eq("TRACE_claim"),
                        eq("REQ_claim"));

        assertThat(lobbySeed.getValue().requestedOutcomeHint()).isEqualTo("REFUND");
        assertThat(lobbySeed.getValue().claimResolutionSeed().requestedResolution())
                .isEqualTo("REFUND");
        assertThat(lobbySeed.getValue().claimResolutionSeed().requestedAmount())
                .isEqualByComparingTo("299");
        assertThat(lobbySeed.getValue().claimResolutionSeed().originalStatement())
                .isEqualTo("我没收到包裹，希望退款");
        assertThat(lobbySeed.getValue().respondentAttitudeSeed()).isNull();
    }

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome()」。
    // 具体功能：「CaseApplicationServiceTest.missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome()」：复现“核对完整业务行为（场景方法「missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome」）”场景：驱动 「service.create」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「帮我查一下包裹到哪里了」、「user-1」、「idem-missing」、「TRACE_test」。
    // 上游调用：「CaseApplicationServiceTest.missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome()」守住「案件受理兼容链路」的可执行规格，尤其防止 「帮我查一下包裹到哪里了」、「user-1」、「idem-missing」、「TRACE_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome() {
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.creationDoesNotPrecomputeAnAgentSummary()」。
    // 具体功能：「CaseApplicationServiceTest.creationDoesNotPrecomputeAnAgentSummary()」：复现“核对完整业务行为（场景方法「creationDoesNotPrecomputeAnAgentSummary」）”场景：驱动 「service.create」，再用 「assertThat」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「order-2」、「user-1」、「idem-fallback」、「TRACE_test」。
    // 上游调用：「CaseApplicationServiceTest.creationDoesNotPrecomputeAnAgentSummary()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.creationDoesNotPrecomputeAnAgentSummary()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.creationDoesNotPrecomputeAnAgentSummary()」守住「案件受理兼容链路」的可执行规格，尤其防止 「order-2」、「user-1」、「idem-fallback」、「TRACE_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void creationDoesNotPrecomputeAnAgentSummary() {
        String submittedDescription = "订单 order-2 的物流一直没有更新";
        CaseView result =
                service.create(
                        command(submittedDescription, "order-2"),
                        new AuthenticatedActor("user-1", ActorRole.USER),
                        "idem-fallback",
                        "TRACE_test",
                        "REQ_test");

        assertThat(result.agentDegraded()).isFalse();
        assertThat(result.caseType()).isEqualTo("DISPUTE");
        assertThat(result.caseStatus()).isEqualTo(CaseStatus.INTAKE_COMPLETED);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.potentialDispute()).isTrue();
        assertThat(result.title()).isEqualTo("履约争议待核实");
        assertThat(result.description()).isEqualTo(submittedDescription);
        verifyNoInteractions(agentServiceClient);
    }

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.userCannotCreateCaseForAnotherUser()」。
    // 具体功能：「CaseApplicationServiceTest.userCannotCreateCaseForAnotherUser()」：复现“核对完整业务行为（场景方法「userCannotCreateCaseForAnotherUser」）”场景：驱动 「service.create」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「查询订单」、「order-3」、「other-user」、「idem-forbidden」。
    // 上游调用：「CaseApplicationServiceTest.userCannotCreateCaseForAnotherUser()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.userCannotCreateCaseForAnotherUser()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.userCannotCreateCaseForAnotherUser()」守住「案件受理兼容链路」的可执行规格，尤其防止 「查询订单」、「order-3」、「other-user」、「idem-forbidden」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator()」。
    // 具体功能：「CaseApplicationServiceTest.merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator()」：复现“核对完整业务行为（场景方法「merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator」）”场景：驱动 「service.create」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「order-merchant」、「user-1」、「merchant-1」、「用户提交故障视频，但商家认为需要平台接待官介入核验。」。
    // 上游调用：「CaseApplicationServiceTest.merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator()」守住「案件受理兼容链路」的可执行规格，尤其防止 「order-merchant」、「user-1」、「merchant-1」、「用户提交故障视频，但商家认为需要平台接待官介入核验。」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void merchantCreatedDisputePersistsMerchantAsTheSingleIntakeInitiator() {
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant()」。
    // 具体功能：「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant()」：复现“核对完整业务行为（场景方法「notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant」）”场景：驱动 「caseRepository.findById」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_MERCHANT_INITIATED」、「ORDER_MERCHANT_INITIATED」、「user-local」、「merchant-local」。
    // 上游调用：「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant()」守住「案件受理兼容链路」的可执行规格，尤其防止 「CASE_MERCHANT_INITIATED」、「ORDER_MERCHANT_INITIATED」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.privilegedCreatorIsMappedToSystemForTheIntakeTurnContract()」。
    // 具体功能：「CaseApplicationServiceTest.privilegedCreatorIsMappedToSystemForTheIntakeTurnContract()」：复现“核对完整业务行为（场景方法「privilegedCreatorIsMappedToSystemForTheIntakeTurnContract」）”场景：驱动 「service.create」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「order-admin」、「reviewer-1」、「idem-reviewer-created」、「TRACE_reviewer」。
    // 上游调用：「CaseApplicationServiceTest.privilegedCreatorIsMappedToSystemForTheIntakeTurnContract()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.privilegedCreatorIsMappedToSystemForTheIntakeTurnContract()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.privilegedCreatorIsMappedToSystemForTheIntakeTurnContract()」守住「案件受理兼容链路」的可执行规格，尤其防止 「order-admin」、「reviewer-1」、「idem-reviewer-created」、「TRACE_reviewer」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void privilegedCreatorIsMappedToSystemForTheIntakeTurnContract() {
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.passesLogisticsReferenceIntoTheIntakeTurnSeed()」。
    // 具体功能：「CaseApplicationServiceTest.passesLogisticsReferenceIntoTheIntakeTurnSeed()」：复现“核对完整业务行为（场景方法「passesLogisticsReferenceIntoTheIntakeTurnSeed」）”场景：驱动 「service.create」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「物流显示签收，但我没有收到包裹。」、「order-with-logistics」、「LOG-TRACK-1」、「user-1」。
    // 上游调用：「CaseApplicationServiceTest.passesLogisticsReferenceIntoTheIntakeTurnSeed()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.passesLogisticsReferenceIntoTheIntakeTurnSeed()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.passesLogisticsReferenceIntoTheIntakeTurnSeed()」守住「案件受理兼容链路」的可执行规格，尤其防止 「物流显示签收，但我没有收到包裹。」、「order-with-logistics」、「LOG-TRACK-1」、「user-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void passesLogisticsReferenceIntoTheIntakeTurnSeed() {
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


    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType()」。
    // 具体功能：「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType()」：复现“核对完整业务行为（场景方法「normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType」）”场景：驱动 「caseRepository.findById」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_LEGACY」、「ORDER_LEGACY」、「user-1」、「merchant-1」。
    // 上游调用：「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType()」守住「案件受理兼容链路」的可执行规格，尤其防止 「CASE_LEGACY」、「ORDER_LEGACY」、「user-1」、「merchant-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.admittingTransferredIntakeCasePromotesItToDispute()」。
    // 具体功能：「CaseApplicationServiceTest.admittingTransferredIntakeCasePromotesItToDispute()」：复现“核对完整业务行为（场景方法「admittingTransferredIntakeCasePromotesItToDispute」）”场景：驱动 「FulfillmentCaseEntity.create」、「transferred.admitToEvidence」、「transferred.getCaseType」、「java.time.OffsetDateTime.parse」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_TRANSFERRED」、「ORDER_TRANSFERRED」、「user-1」、「merchant-1」。
    // 上游调用：「CaseApplicationServiceTest.admittingTransferredIntakeCasePromotesItToDispute()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseApplicationServiceTest.admittingTransferredIntakeCasePromotesItToDispute()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseApplicationServiceTest.admittingTransferredIntakeCasePromotesItToDispute()」守住「案件受理兼容链路」的可执行规格，尤其防止 「CASE_TRANSFERRED」、「ORDER_TRANSFERRED」、「user-1」、「merchant-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.command(String,String)」。
    // 具体功能：「CaseApplicationServiceTest.command(String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「CreateCaseCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「CaseApplicationServiceTest.command(String,String)」由本测试类中的 「CaseApplicationServiceTest.createsDeterministicShellAndStartsTheSingleIntakeAgentTurn」、「CaseApplicationServiceTest.missingOrderIdEntersWaitingSlotCompletionWithoutPromisingAnOutcome」、「CaseApplicationServiceTest.creationDoesNotPrecomputeAnAgentSummary」、「CaseApplicationServiceTest.userCannotCreateCaseForAnotherUser」 调用。
    // 下游影响：「CaseApplicationServiceTest.command(String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseApplicationServiceTest.command(String,String)」守住「案件受理兼容链路」的可执行规格，尤其防止 「user-1」、「merchant-1」、「WEB」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.commandWithLogistics(String,String,String)」。
    // 具体功能：「CaseApplicationServiceTest.commandWithLogistics(String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「commandWithLogistics」）”组装或读取「CreateCaseCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「CaseApplicationServiceTest.commandWithLogistics(String,String,String)」由本测试类中的 「CaseApplicationServiceTest.passesLogisticsReferenceIntoTheIntakeTurnSeed」 调用。
    // 下游影响：「CaseApplicationServiceTest.commandWithLogistics(String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseApplicationServiceTest.commandWithLogistics(String,String,String)」守住「案件受理兼容链路」的可执行规格，尤其防止 「user-1」、「merchant-1」、「WEB」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「CaseApplicationServiceTest.commandWithClaim(String,String,ClaimResolutionSeed)」。
    // 具体功能：「CaseApplicationServiceTest.commandWithClaim(String,String,ClaimResolutionSeed)」：作为测试辅助方法为“核对完整业务行为（场景方法「commandWithClaim」）”组装或读取「CreateCaseCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「CaseApplicationServiceTest.commandWithClaim(String,String,ClaimResolutionSeed)」由本测试类中的 「CaseApplicationServiceTest.structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools」 调用。
    // 下游影响：「CaseApplicationServiceTest.commandWithClaim(String,String,ClaimResolutionSeed)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseApplicationServiceTest.commandWithClaim(String,String,ClaimResolutionSeed)」守住「案件受理兼容链路」的可执行规格，尤其防止 「user-1」、「merchant-1」、「WEB」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CreateCaseCommand commandWithClaim(
            String description,
            String orderId,
            IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed) {
        return new CreateCaseCommand(
                orderId,
                null,
                null,
                "user-1",
                "merchant-1",
                description,
                List.of(),
                "WEB",
                ActorRole.USER,
                claimResolutionSeed,
                null);
    }
}

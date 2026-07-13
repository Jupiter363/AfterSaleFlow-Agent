/*
 * 所属模块：争议路由应用层。
 * 文件职责：验证Router应用，覆盖 「regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview」、「ruleFlowReferencesTheExactPolicyAndVersion」、「highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；汇集案件、证据和规则上下文并选择常规、规则或听证路线。
 * 关键边界：路由只决定下一条处理路径，不拥有终审或工具执行权限
 */
package com.example.dispute.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.infrastructure.persistence.repository.RouteDecisionRepository;
import com.example.dispute.router.application.RouterApplicationService;
import com.example.dispute.regularflow.application.RegularFlowService;
import com.example.dispute.ruleflow.application.RuleFlowService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【争议路由应用层 / 自动化测试层】类型「RouterApplicationServiceTest」。
// 类型职责：集中验证Router应用的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview」、「ruleFlowReferencesTheExactPolicyAndVersion」、「highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion」、「mockCaseAndDossier」、「dossierBuiltCase」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class RouterApplicationServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private PolicyRuleRepository policyRepository;
    @Mock private RouteDecisionRepository decisionRepository;
    @Mock private FlowConclusionRepository conclusionRepository;
    @Mock private AuditRecorder auditRecorder;

    private RouterApplicationService service;

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApplicationServiceTest.setUp()」。
    // 具体功能：「RouterApplicationServiceTest.setUp()」：在每个测试场景运行前创建「decisionRepository.findByCaseId」、「decisionRepository.save」、「Clock.fixed」、「Instant.parse」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「RouterApplicationServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「RouterApplicationServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApplicationServiceTest.setUp()」守住「争议路由应用层」的可执行规格，尤其防止 「2026-06-28T10:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service =
                new RouterApplicationService(
                        caseRepository,
                        dossierRepository,
                        policyRepository,
                        decisionRepository,
                        conclusionRepository,
                        auditRecorder,
                        objectMapper,
                        Clock.fixed(
                                Instant.parse("2026-06-28T10:00:00Z"),
                                ZoneOffset.UTC),
                        new RegularFlowService(),
                        new RuleFlowService(objectMapper));
        when(decisionRepository.findByCaseId(any())).thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview()」。
    // 具体功能：「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview()」：复现“核对完整业务行为（场景方法「regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview」）”场景：驱动 「conclusionRepository.save」、「policyRepository.findActive」、「service.route」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「LOGISTICS_QUERY」、「USER_route」、「ROUTE_regular」、「REGULAR_FLOW」。
    // 上游调用：「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview()」守住「争议路由应用层」的可执行规格，尤其防止 「LOGISTICS_QUERY」、「USER_route」、「ROUTE_regular」、「REGULAR_FLOW」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview() {
        when(conclusionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        FulfillmentCaseEntity disputeCase =
                dossierBuiltCase("LOGISTICS_QUERY", null, RiskLevel.LOW);
        mockCaseAndDossier(disputeCase, 0, 0);
        when(policyRepository.findActive(any(), any())).thenReturn(List.of());

        var decision =
                service.route(
                        disputeCase.getId(),
                        new AuthenticatedActor("USER_route", ActorRole.USER),
                        "ROUTE_regular");

        assertThat(decision.routeType()).isEqualTo(RouteType.TRANSFERRED);
        assertThat(decision.conclusion()).isNotNull();
        assertThat(decision.conclusion().conclusionType()).isEqualTo("REGULAR_FLOW");
        assertThat(decision.conclusion().requiresRemedyPlanning()).isTrue();
        assertThat(decision.conclusion().requiresHumanReview()).isTrue();
        assertThat(disputeCase.getCaseStatus()).isEqualTo(CaseStatus.ROUTED);
        verify(conclusionRepository).save(any(FlowConclusionEntity.class));
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion()」。
    // 具体功能：「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion()」：复现“核对完整业务行为（场景方法「ruleFlowReferencesTheExactPolicyAndVersion」）”场景：驱动 「conclusionRepository.save」、「policyRepository.findActive」、「service.route」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「UNSHIPPED_CANCEL」、「POLICY_test」、「2020-01-01T00:00:00Z」、「{\"requires_evidence\":true}」。
    // 上游调用：「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion()」守住「争议路由应用层」的可执行规格，尤其防止 「UNSHIPPED_CANCEL」、「POLICY_test」、「2020-01-01T00:00:00Z」、「{\"requires_evidence\":true}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void ruleFlowReferencesTheExactPolicyAndVersion() {
        when(conclusionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        FulfillmentCaseEntity disputeCase =
                dossierBuiltCase("UNSHIPPED_CANCEL", null, RiskLevel.MEDIUM);
        mockCaseAndDossier(disputeCase, 1, 0);
        PolicyRuleEntity policy =
                PolicyRuleEntity.active(
                        "POLICY_test",
                        "UNSHIPPED_CANCEL",
                        3,
                        "Unshipped cancellation",
                        "UNSHIPPED_CANCEL",
                        OffsetDateTime.parse("2020-01-01T00:00:00Z"),
                        100,
                        "{\"requires_evidence\":true}",
                        "{\"conclusion_code\":\"REFUND_OR_CANCEL_RECOMMENDED\","
                                + "\"recommended_actions\":[\"CANCEL_ORDER\",\"REFUND\"]}",
                        "{\"document_code\":\"FULFILLMENT_POLICY\"}",
                        "system");
        when(policyRepository.findActive(any(), any())).thenReturn(List.of(policy));

        var decision =
                service.route(
                        disputeCase.getId(),
                        new AuthenticatedActor("USER_route", ActorRole.USER),
                        "ROUTE_rule");

        assertThat(decision.routeType()).isEqualTo(RouteType.SIMPLE_HEARING);
        assertThat(decision.policyRuleId()).isEqualTo("POLICY_test");
        assertThat(decision.conclusion().policyVersion()).isEqualTo(3);
        assertThat(decision.conclusion().recommendedActions())
                .containsExactly("CANCEL_ORDER", "REFUND");
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion()」。
    // 具体功能：「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion()」：复现“核对完整业务行为（场景方法「highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion」）”场景：驱动 「policyRepository.findActive」、「service.route」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「UNSHIPPED_CANCEL」、「USER_route」、「ROUTE_hearing」。
    // 上游调用：「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion()」守住「争议路由应用层」的可执行规格，尤其防止 「UNSHIPPED_CANCEL」、「USER_route」、「ROUTE_hearing」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion() {
        FulfillmentCaseEntity disputeCase =
                dossierBuiltCase("UNSHIPPED_CANCEL", null, RiskLevel.HIGH);
        mockCaseAndDossier(disputeCase, 1, 0);
        when(policyRepository.findActive(any(), any())).thenReturn(List.of());

        var decision =
                service.route(
                        disputeCase.getId(),
                        new AuthenticatedActor("USER_route", ActorRole.USER),
                        "ROUTE_hearing");

        assertThat(decision.routeType()).isEqualTo(RouteType.FULL_HEARING);
        assertThat(decision.conclusion()).isNull();
        verify(conclusionRepository, never()).save(any());
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApplicationServiceTest.mockCaseAndDossier(FulfillmentCaseEntity,int,int)」。
    // 具体功能：「RouterApplicationServiceTest.mockCaseAndDossier(FulfillmentCaseEntity,int,int)」：作为测试辅助方法为“核对完整业务行为（场景方法「mockCaseAndDossier」）”组装或读取「caseRepository.findByIdForUpdate」、「dossierRepository.findByCaseId」、「EvidenceDossierEntity.firstBuild」、「disputeCase.getId」，供本测试类的场景方法复用。
    // 上游调用：「RouterApplicationServiceTest.mockCaseAndDossier(FulfillmentCaseEntity,int,int)」由本测试类中的 「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview」、「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion」、「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion」 调用。
    // 下游影响：「RouterApplicationServiceTest.mockCaseAndDossier(FulfillmentCaseEntity,int,int)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApplicationServiceTest.mockCaseAndDossier(FulfillmentCaseEntity,int,int)」守住「争议路由应用层」的可执行规格，尤其防止 「DOSSIER_」、「USER_route」、「{\"evidence_count\":」、「,\"pending_parse_count\":」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void mockCaseAndDossier(
            FulfillmentCaseEntity disputeCase, int evidenceCount, int pendingCount) {
        when(caseRepository.findByIdForUpdate(disputeCase.getId()))
                .thenReturn(Optional.of(disputeCase));
        EvidenceDossierEntity dossier =
                EvidenceDossierEntity.firstBuild(
                        "DOSSIER_" + disputeCase.getId(),
                        disputeCase.getId(),
                        "USER_route",
                        "{\"evidence_count\":"
                                + evidenceCount
                                + ",\"pending_parse_count\":"
                                + pendingCount
                                + "}",
                        "[]",
                        "[]");
        when(dossierRepository.findByCaseId(disputeCase.getId()))
                .thenReturn(Optional.of(dossier));
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「RouterApplicationServiceTest.dossierBuiltCase(String,String,RiskLevel)」。
    // 具体功能：「RouterApplicationServiceTest.dossierBuiltCase(String,String,RiskLevel)」：作为测试辅助方法为“核对完整业务行为（场景方法「dossierBuiltCase」）”组装或读取「FulfillmentCaseEntity.create」、「disputeCase.completeIntake」、「disputeCase.markDossierBuilt」，供本测试类的场景方法复用。
    // 上游调用：「RouterApplicationServiceTest.dossierBuiltCase(String,String,RiskLevel)」由本测试类中的 「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview」、「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion」、「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion」 调用。
    // 下游影响：「RouterApplicationServiceTest.dossierBuiltCase(String,String,RiskLevel)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RouterApplicationServiceTest.dossierBuiltCase(String,String,RiskLevel)」守住「争议路由应用层」的可执行规格，尤其防止 「CASE_」、「ORDER_route」、「USER_route」、「MERCHANT_route」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity dossierBuiltCase(
            String caseType, String disputeType, RiskLevel riskLevel) {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        "CASE_" + caseType,
                        "ORDER_route",
                        null,
                        "USER_route",
                        "MERCHANT_route",
                        "IDEMPOTENCY_" + caseType,
                        caseType,
                        "Routing test",
                        "Routing test description",
                        riskLevel,
                        "USER_route");
        disputeCase.completeIntake(
                disputeType,
                CaseStatus.INTAKE_COMPLETED,
                riskLevel,
                "{}",
                "USER_route");
        disputeCase.markDossierBuilt("USER_route");
        return disputeCase;
    }
}

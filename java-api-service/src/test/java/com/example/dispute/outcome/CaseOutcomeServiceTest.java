/*
 * 所属模块：裁决结果查询。
 * 文件职责：验证案件结果，覆盖 「projectsTheLatestHumanDecisionOverTheAdjudicationDraft」、「exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill」、「rejectsAUserWhoDoesNotOwnTheDispute」、「rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm」、「rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify」、「reviewerConfirmsLatestDraftByCaseReviewTask」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；聚合人工终审、非最终草案、补救执行和案件时间线形成角色可见结果页。
 * 关键边界：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
 */
package com.example.dispute.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.outcome.application.CaseOutcomeService;
import com.example.dispute.outcome.application.CaseOutcomeView;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.review.application.ReviewDecisionCommand;
import com.example.dispute.review.application.ReviewDecisionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【裁决结果查询 / 自动化测试层】类型「CaseOutcomeServiceTest」。
// 类型职责：集中验证案件结果的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「projectsTheLatestHumanDecisionOverTheAdjudicationDraft」、「exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill」、「rejectsAUserWhoDoesNotOwnTheDispute」、「rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm」、「rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class CaseOutcomeServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private ApprovalRecordRepository approvalRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private FlowConclusionRepository conclusionRepository;
    @Mock private ToolExecutorService executorService;
    @Mock private RemedyPlanRepository remedyPlanRepository;
    @Mock private ReviewTaskRepository reviewTaskRepository;
    @Mock private ReviewApplicationService reviewApplicationService;
    private CaseOutcomeService service;

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.setUp()」。
    // 具体功能：「CaseOutcomeServiceTest.setUp()」：在每个测试场景运行前创建测试对象和内存夹具，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「CaseOutcomeServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「CaseOutcomeServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseOutcomeServiceTest.setUp()」守住「裁决结果查询」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new CaseOutcomeService(
                        caseRepository,
                        approvalRepository,
                        draftRepository,
                        conclusionRepository,
                        executorService,
                        remedyPlanRepository,
                        reviewTaskRepository,
                        reviewApplicationService,
                        new ObjectMapper());
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft()」。
    // 具体功能：「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft()」：复现“核对完整业务行为（场景方法「projectsTheLatestHumanDecisionOverTheAdjudicationDraft」）”场景：驱动 「caseRepository.findById」、「approvalRepository.findAllByCaseIdOrderByCreatedAtAsc」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「conclusionRepository.findByCaseId」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「APPROVAL_1」、「TASK_1」、「PLAN_1」、「reviewer-1」。
    // 上游调用：「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft()」守住「裁决结果查询」的可执行规格，尤其防止 「APPROVAL_1」、「TASK_1」、「PLAN_1」、「reviewer-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void projectsTheLatestHumanDecisionOverTheAdjudicationDraft() {
        FulfillmentCaseEntity dispute = dispute();
        ApprovalRecordEntity approval =
                ApprovalRecordEntity.record(
                        "APPROVAL_1",
                        dispute.getId(),
                        "TASK_1",
                        "PLAN_1",
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        ApprovalDecisionType.APPROVE,
                        "{}",
                        "{\"actions\":[{\"type\":\"REFUND\"}]}",
                        "审核员确认证据链完整。",
                        "hash-1");
        AdjudicationDraftEntity draft =
                AdjudicationDraftEntity.create(
                        "DRAFT_1",
                        dispute.getId(),
                        "HEARING_1",
                        2,
                        "[{\"fact\":\"物流记录显示已签收\",\"support_level\":\"SUPPORTED\"}]",
                        "[{\"assessment\":\"商家证据不足以证明用户本人签收\"}]",
                        "[{\"rule\":\"签收争议举证责任\"}]",
                        "[\"核验签收人身份\"]",
                        "支持用户退款请求",
                        new BigDecimal("0.9200"),
                        "商家举证不足。",
                        "adjudication-agent",
                        "READY",
                        "system");

        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(approvalRepository.findAllByCaseIdOrderByCreatedAtAsc(dispute.getId()))
                .thenReturn(List.of(approval));
        when(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(dispute.getId()))
                .thenReturn(Optional.of(draft));
        when(conclusionRepository.findByCaseId(dispute.getId()))
                .thenReturn(Optional.empty());
        when(executorService.actions(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER)))
                .thenReturn(List.of());

        CaseOutcomeView outcome =
                service.get(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(outcome.finalDecision().conclusion())
                .isEqualTo("支持用户退款请求");
        assertThat(outcome.finalDecision().explanation())
                .isEqualTo("商家举证不足。");
        assertThat(outcome.finalDecision().reviewReason())
                .isEqualTo("审核员确认证据链完整。");
        assertThat(outcome.finalDecision().humanConfirmed()).isTrue();
        assertThat(outcome.finalDecision().approvedPlan().at("/actions/0/type").asText())
                .isEqualTo("REFUND");
        assertThat(outcome.adjudicationDraft()).isNotNull();
        assertThat(outcome.adjudicationDraft().id()).isEqualTo("DRAFT_1");
        assertThat(outcome.adjudicationDraft().factFindings().get(0).path("fact").asText())
                .isEqualTo("物流记录显示已签收");
        assertThat(outcome.adjudicationDraft().evidenceAssessment().get(0).path("assessment").asText())
                .isEqualTo("商家证据不足以证明用户本人签收");
        assertThat(outcome.adjudicationDraft().policyApplication().get(0).path("rule").asText())
                .isEqualTo("签收争议举证责任");
        assertThat(outcome.adjudicationDraft().reviewerAttention().get(0).asText())
                .isEqualTo("核验签收人身份");
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill()」。
    // 具体功能：「CaseOutcomeServiceTest.exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill()」：复现“核对完整业务行为（场景方法「exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill」）”场景：驱动 「caseRepository.findById」、「approvalRepository.findAllByCaseIdOrderByCreatedAtAsc」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「conclusionRepository.findByCaseId」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「DRAFT_PREFILL」、「HEARING_1」、「[]」、「SUPPORT_REFUND」。
    // 上游调用：「CaseOutcomeServiceTest.exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeServiceTest.exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseOutcomeServiceTest.exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill()」守住「裁决结果查询」的可执行规格，尤其防止 「DRAFT_PREFILL」、「HEARING_1」、「[]」、「SUPPORT_REFUND」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill() {
        FulfillmentCaseEntity dispute = dispute();
        AdjudicationDraftEntity draft =
                AdjudicationDraftEntity.create(
                        "DRAFT_PREFILL",
                        dispute.getId(),
                        "HEARING_1",
                        3,
                        "[]",
                        "[]",
                        "[]",
                        "[]",
                        "SUPPORT_REFUND",
                        new BigDecimal("0.8100"),
                        "AI 法官已形成非最终裁决草案。",
                        "adjudication-agent",
                        "READY",
                        "system");
        RemedyPlanEntity plan =
                RemedyPlanEntity.pendingApproval(
                        "PLAN_PREFILL",
                        dispute.getId(),
                        draft.getId(),
                        1,
                        RouteType.SIMPLE_HEARING,
                        RiskLevel.MEDIUM,
                        "[{\"action_type\":\"REFUND\",\"amount\":188}]",
                        "[\"审核员确认后执行\"]",
                        "[\"通知用户和商家\"]",
                        "system");

        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(approvalRepository.findAllByCaseIdOrderByCreatedAtAsc(dispute.getId()))
                .thenReturn(List.of());
        when(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(dispute.getId()))
                .thenReturn(Optional.of(draft));
        when(conclusionRepository.findByCaseId(dispute.getId()))
                .thenReturn(Optional.empty());
        when(remedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(dispute.getId()))
                .thenReturn(Optional.of(plan));
        when(executorService.actions(
                        dispute.getId(),
                        new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER)))
                .thenReturn(List.of());

        CaseOutcomeView outcome =
                service.get(
                        dispute.getId(),
                        new AuthenticatedActor(
                                "reviewer-local",
                                ActorRole.PLATFORM_REVIEWER));

        assertThat(outcome.adjudicationDraft().approvedPlan().path("id").asText())
                .isEqualTo("PLAN_PREFILL");
        assertThat(outcome.adjudicationDraft().approvedPlan().path("version").asInt())
                .isEqualTo(1);
        assertThat(outcome.adjudicationDraft().approvedPlan().at("/actions/0/action_type").asText())
                .isEqualTo("REFUND");
        assertThat(outcome.adjudicationDraft().approvedPlan().at("/actions/0/amount").asInt())
                .isEqualTo(188);
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.rejectsAUserWhoDoesNotOwnTheDispute()」。
    // 具体功能：「CaseOutcomeServiceTest.rejectsAUserWhoDoesNotOwnTheDispute()」：复现“拒绝非法输入或越权操作（场景方法「rejectsAUserWhoDoesNotOwnTheDispute」）”场景：驱动 「caseRepository.findById」，再用 「assertThatThrownBy」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「another-user」。
    // 上游调用：「CaseOutcomeServiceTest.rejectsAUserWhoDoesNotOwnTheDispute()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeServiceTest.rejectsAUserWhoDoesNotOwnTheDispute()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseOutcomeServiceTest.rejectsAUserWhoDoesNotOwnTheDispute()」守住「裁决结果查询」的可执行规格，尤其防止 「another-user」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsAUserWhoDoesNotOwnTheDispute() {
        FulfillmentCaseEntity dispute = dispute();
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));

        assertThatThrownBy(
                        () ->
                                service.get(
                                        dispute.getId(),
                                        new AuthenticatedActor(
                                                "another-user",
                                                ActorRole.USER)))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(
                approvalRepository,
                draftRepository,
                conclusionRepository,
                executorService,
                remedyPlanRepository,
                reviewTaskRepository,
                reviewApplicationService);
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm()」。
    // 具体功能：「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm()」：复现“拒绝非法输入或越权操作（场景方法「rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm」）”场景：驱动 「service.confirmDraft」，再用 「assertThatThrownBy」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「reviewer-1」、「CASE_outcome」、「outcome-confirm-forbidden」。
    // 上游调用：「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm()」守住「裁决结果查询」的可执行规格，尤其防止 「reviewer-1」、「CASE_outcome」、「outcome-confirm-forbidden」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm() {
        AuthenticatedActor anotherReviewer =
                new AuthenticatedActor(
                        "reviewer-1",
                        ActorRole.PLATFORM_REVIEWER);

        assertThatThrownBy(
                        () ->
                                service.confirmDraft(
                                        "CASE_outcome",
                                        "attempted by another reviewer",
                                        "outcome-confirm-forbidden",
                                        anotherReviewer))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(reviewTaskRepository, reviewApplicationService);
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify()」。
    // 具体功能：「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify()」：复现“拒绝非法输入或越权操作（场景方法「rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify」）”场景：驱动 「service.modifyDraft」，再用 「assertThatThrownBy」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「reviewer-1」、「CASE_outcome」、「outcome-modify-forbidden」。
    // 上游调用：「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify()」守住「裁决结果查询」的可执行规格，尤其防止 「reviewer-1」、「CASE_outcome」、「outcome-modify-forbidden」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify() {
        AuthenticatedActor anotherReviewer =
                new AuthenticatedActor(
                        "reviewer-1",
                        ActorRole.PLATFORM_REVIEWER);

        assertThatThrownBy(
                        () ->
                                service.modifyDraft(
                                        "CASE_outcome",
                                        "attempted by another reviewer",
                                        new ObjectMapper().createObjectNode(),
                                        "outcome-modify-forbidden",
                                        anotherReviewer))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(reviewTaskRepository, reviewApplicationService);
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.reviewerConfirmsLatestDraftByCaseReviewTask()」。
    // 具体功能：「CaseOutcomeServiceTest.reviewerConfirmsLatestDraftByCaseReviewTask()」：复现“核对完整业务行为（场景方法「reviewerConfirmsLatestDraftByCaseReviewTask」）”场景：驱动 「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」、「reviewApplicationService.decide」、「service.confirmDraft」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REVIEW_1」、「CASE_outcome」、「PLAN_1」、「PACKET_1」。
    // 上游调用：「CaseOutcomeServiceTest.reviewerConfirmsLatestDraftByCaseReviewTask()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeServiceTest.reviewerConfirmsLatestDraftByCaseReviewTask()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseOutcomeServiceTest.reviewerConfirmsLatestDraftByCaseReviewTask()」守住「裁决结果查询」的可执行规格，尤其防止 「REVIEW_1」、「CASE_outcome」、「PLAN_1」、「PACKET_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewerConfirmsLatestDraftByCaseReviewTask() {
        ReviewTaskEntity task =
                ReviewTaskEntity.pending(
                        "REVIEW_1",
                        "CASE_outcome",
                        "PLAN_1",
                        "PACKET_1",
                        "HIGH",
                        "PLATFORM_REVIEWER",
                        OffsetDateTime.parse("2026-07-10T00:00:00Z"),
                        "system");
        AuthenticatedActor reviewer =
                new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER);
        ReviewDecisionView expected =
                new ReviewDecisionView(
                        "APPROVAL_1",
                        "REVIEW_1",
                        "CASE_outcome",
                        "APPROVE",
                        "APPROVED",
                        "APPROVED_FOR_EXECUTION",
                        true);
        when(reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc("CASE_outcome"))
                .thenReturn(Optional.of(task));
        when(reviewApplicationService.decide(
                        org.mockito.ArgumentMatchers.eq("REVIEW_1"),
                        org.mockito.ArgumentMatchers.any(ReviewDecisionCommand.class),
                        org.mockito.ArgumentMatchers.eq(reviewer)))
                .thenReturn(expected);

        ReviewDecisionView actual =
                service.confirmDraft(
                        "CASE_outcome",
                        "审核员确认 AI 裁决草案。",
                        "outcome-confirm-1",
                        reviewer);

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<ReviewDecisionCommand> command =
                ArgumentCaptor.forClass(ReviewDecisionCommand.class);
        verify(reviewApplicationService).decide(
                org.mockito.ArgumentMatchers.eq("REVIEW_1"),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(reviewer));
        assertThat(command.getValue().decision()).isEqualTo(ApprovalDecisionType.APPROVE);
        assertThat(command.getValue().reason()).contains("审核员确认");
        assertThat(command.getValue().idempotencyKey()).isEqualTo("outcome-confirm-1");
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.reviewerModifiesLatestDraftByCaseReviewTask()」。
    // 具体功能：「CaseOutcomeServiceTest.reviewerModifiesLatestDraftByCaseReviewTask()」：复现“核对完整业务行为（场景方法「reviewerModifiesLatestDraftByCaseReviewTask」）”场景：驱动 「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」、「reviewApplicationService.decide」、「service.modifyDraft」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REVIEW_2」、「CASE_outcome」、「PLAN_2」、「PACKET_2」。
    // 上游调用：「CaseOutcomeServiceTest.reviewerModifiesLatestDraftByCaseReviewTask()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseOutcomeServiceTest.reviewerModifiesLatestDraftByCaseReviewTask()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseOutcomeServiceTest.reviewerModifiesLatestDraftByCaseReviewTask()」守住「裁决结果查询」的可执行规格，尤其防止 「REVIEW_2」、「CASE_outcome」、「PLAN_2」、「PACKET_2」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewerModifiesLatestDraftByCaseReviewTask() throws Exception {
        ReviewTaskEntity task =
                ReviewTaskEntity.pending(
                        "REVIEW_2",
                        "CASE_outcome",
                        "PLAN_2",
                        "PACKET_2",
                        "HIGH",
                        "PLATFORM_REVIEWER",
                        OffsetDateTime.parse("2026-07-10T00:00:00Z"),
                        "system");
        AuthenticatedActor reviewer =
                new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER);
        ObjectMapper mapper = new ObjectMapper();
        var modifiedPlan =
                mapper.readTree(
                        "{\"id\":\"PLAN_2\",\"actions\":[{\"action_type\":\"REFUND\",\"amount\":199}]}");
        ReviewDecisionView expected =
                new ReviewDecisionView(
                        "APPROVAL_2",
                        "REVIEW_2",
                        "CASE_outcome",
                        "MODIFY_AND_APPROVE",
                        "APPROVED",
                        "APPROVED_FOR_EXECUTION",
                        true);
        when(reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc("CASE_outcome"))
                .thenReturn(Optional.of(task));
        when(reviewApplicationService.decide(
                        org.mockito.ArgumentMatchers.eq("REVIEW_2"),
                        org.mockito.ArgumentMatchers.any(ReviewDecisionCommand.class),
                        org.mockito.ArgumentMatchers.eq(reviewer)))
                .thenReturn(expected);

        ReviewDecisionView actual =
                service.modifyDraft(
                        "CASE_outcome",
                        "审核员调整退款金额。",
                        modifiedPlan,
                        "outcome-modify-1",
                        reviewer);

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<ReviewDecisionCommand> command =
                ArgumentCaptor.forClass(ReviewDecisionCommand.class);
        verify(reviewApplicationService).decide(
                org.mockito.ArgumentMatchers.eq("REVIEW_2"),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(reviewer));
        assertThat(command.getValue().decision())
                .isEqualTo(ApprovalDecisionType.MODIFY_AND_APPROVE);
        assertThat(command.getValue().approvedPlan()).isEqualTo(modifiedPlan);
    }

    // 所属模块：【裁决结果查询 / 自动化测试层】「CaseOutcomeServiceTest.dispute()」。
    // 具体功能：「CaseOutcomeServiceTest.dispute()」：作为测试辅助方法为“核对完整业务行为（场景方法「dispute」）”组装或读取「FulfillmentCaseEntity.create」，供本测试类的场景方法复用。
    // 上游调用：「CaseOutcomeServiceTest.dispute()」由本测试类中的 「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft」、「CaseOutcomeServiceTest.exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill」、「CaseOutcomeServiceTest.rejectsAUserWhoDoesNotOwnTheDispute」 调用。
    // 下游影响：「CaseOutcomeServiceTest.dispute()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseOutcomeServiceTest.dispute()」守住「裁决结果查询」的可执行规格，尤其防止 「CASE_outcome」、「ORDER_1」、「AFTER_SALE_1」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity dispute() {
        return FulfillmentCaseEntity.create(
                "CASE_outcome",
                "ORDER_1",
                "AFTER_SALE_1",
                "user-local",
                "merchant-local",
                "create-1",
                "DISPUTE",
                "签收未收到争议",
                "用户主张未收到包裹。",
                RiskLevel.MEDIUM,
                "user-local");
    }
}

/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证终态，覆盖 「finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration」、「completedSharedHearingCreatesReviewTaskForHumanGate」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.application.HearingOutcomeOrchestrationResult;
import com.example.dispute.hearing.application.HearingOutcomeOrchestrationService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.infrastructure.persistence.entity.SettlementProposalEntity;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.DeliberationReportRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.workflow.application.CaseFulfillmentDisputeActivitiesImpl;
import com.example.dispute.workflow.application.FinalWorkflowActivitiesAdapter;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「FinalWorkflowActivitiesAdapterTest」。
// 类型职责：集中验证终态的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration」、「completedSharedHearingCreatesReviewTaskForHumanGate」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class FinalWorkflowActivitiesAdapterTest {

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowActivitiesAdapterTest.finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration()」。
    // 具体功能：「FinalWorkflowActivitiesAdapterTest.finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration()」：复现“核对完整业务行为（场景方法「finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration」）”场景：驱动 「Clock.systemUTC」、「legacy.analyzeHearing」、「adapter.runStage」、「result.draftId」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「DRAFT_FINAL_C6」、「COMPLETED」、「C1_ISSUE_FRAMING」、「C2_EVIDENCE_GAP」。
    // 上游调用：「FinalWorkflowActivitiesAdapterTest.finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FinalWorkflowActivitiesAdapterTest.finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FinalWorkflowActivitiesAdapterTest.finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「DRAFT_FINAL_C6」、「COMPLETED」、「C1_ISSUE_FRAMING」、「C2_EVIDENCE_GAP」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration() {
        CaseFulfillmentDisputeActivitiesImpl legacy =
                mock(CaseFulfillmentDisputeActivitiesImpl.class);
        when(legacy.analyzeHearing(any()))
                .thenReturn(
                        new HearingAnalysisActivityResult(
                                false,
                                true,
                                "DRAFT_FINAL_C6",
                                "COMPLETED"));
        FinalWorkflowActivitiesAdapter adapter =
                new FinalWorkflowActivitiesAdapter(
                        legacy,
                        mock(ApprovalRecordRepository.class),
                        mock(ReviewPacketRepository.class),
                        mock(ReviewTaskRepository.class),
                        mock(DeliberationReportRepository.class),
                        mock(AdjudicationDraftRepository.class),
                        mock(HearingStateRepository.class),
                        mock(SettlementProposalRepository.class),
                        new ObjectMapper().findAndRegisterModules(),
                        mock(HearingRoundService.class),
                        mock(HearingOutcomeOrchestrationService.class),
                        mock(CasePhaseClockRepository.class),
                        Clock.systemUTC());

        for (String stage :
                List.of(
                        "C1_ISSUE_FRAMING",
                        "C2_EVIDENCE_GAP",
                        "C3_EVIDENCE_REQUEST",
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION")) {
            var result =
                    adapter.runStage(
                            "CASE_C6_ONLY",
                            "WORKFLOW_C6_ONLY",
                            stage,
                            3,
                            2,
                            false,
                            true,
                            3);
            assertThat(result.draftId()).isNull();
        }
        verify(legacy, never()).analyzeHearing(any());

        var draftStage =
                adapter.runStage(
                        "CASE_C6_ONLY",
                        "WORKFLOW_C6_ONLY",
                        "C6_DRAFT_GENERATION",
                        3,
                        2,
                        false,
                        true,
                        3);

        assertThat(draftStage.draftId()).isEqualTo("DRAFT_FINAL_C6");
        verify(legacy, times(1)).analyzeHearing(any());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate()」。
    // 具体功能：「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate()」：复现“核对完整业务行为（场景方法「completedSharedHearingCreatesReviewTaskForHumanGate」）”场景：驱动 「phaseClockRepository.findByCaseIdAndClockType」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「hearingStateRepository.findByCaseId」、「settlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_shared_hearing」、「HEARING_shared」、「temporal-worker」、「SETTLEMENT_shared」。
    // 上游调用：「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_shared_hearing」、「HEARING_shared」、「temporal-worker」、「SETTLEMENT_shared」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void completedSharedHearingCreatesReviewTaskForHumanGate() {
        CaseFulfillmentDisputeActivitiesImpl legacy =
                mock(CaseFulfillmentDisputeActivitiesImpl.class);
        CasePhaseClockRepository phaseClockRepository =
                mock(CasePhaseClockRepository.class);
        when(phaseClockRepository.findByCaseIdAndClockType(
                        "CASE_shared_hearing", PhaseClockType.HEARING))
                .thenReturn(Optional.empty());
        AdjudicationDraftRepository draftRepository = mock(AdjudicationDraftRepository.class);
        HearingStateRepository hearingStateRepository = mock(HearingStateRepository.class);
        SettlementProposalRepository settlementProposalRepository =
                mock(SettlementProposalRepository.class);
        when(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc("CASE_shared_hearing"))
                .thenReturn(Optional.empty());
        HearingStateEntity hearingState =
                HearingStateEntity.start(
                        "HEARING_shared",
                        "CASE_shared_hearing",
                        "hearing-window-CASE_shared_hearing",
                        "temporal-worker");
        when(hearingStateRepository.findByCaseId("CASE_shared_hearing"))
                .thenReturn(Optional.of(hearingState));
        SettlementProposalEntity settlement =
                SettlementProposalEntity.propose(
                        "SETTLEMENT_shared",
                        "CASE_shared_hearing",
                        1,
                        ActorRole.MERCHANT,
                        "merchant-local",
                        "商家同意补发正确型号 A-2026，用户退回错发商品。",
                        "{\"source\":\"PARTY_CONSENSUS\"}",
                        null,
                        Instant.now(),
                        "TRACE_shared");
        settlement.confirm("system", Instant.now());
        when(settlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc(
                        "CASE_shared_hearing"))
                .thenReturn(Optional.of(settlement));
        HearingOutcomeOrchestrationService outcomeOrchestration =
                mock(HearingOutcomeOrchestrationService.class);
        when(outcomeOrchestration.orchestrate(
                        "CASE_shared_hearing", "temporal-worker"))
                .thenReturn(
                        new HearingOutcomeOrchestrationResult(
                                "CASE_shared_hearing",
                                "REMEDY_shared_hearing",
                                "REVIEW_shared_hearing",
                                true,
                                true,
                                "REVIEW_GATE_READY"));
        FinalWorkflowActivitiesAdapter adapter =
                new FinalWorkflowActivitiesAdapter(
                        legacy,
                        mock(ApprovalRecordRepository.class),
                        mock(ReviewPacketRepository.class),
                        mock(ReviewTaskRepository.class),
                        mock(DeliberationReportRepository.class),
                        draftRepository,
                        hearingStateRepository,
                        settlementProposalRepository,
                        new ObjectMapper().findAndRegisterModules(),
                        mock(HearingRoundService.class),
                        outcomeOrchestration,
                        phaseClockRepository,
                        Clock.systemUTC());

        adapter.complete(
                "CASE_shared_hearing",
                "hearing-window-CASE_shared_hearing",
                "SETTLEMENT_CONFIRMED",
                false,
                false,
                1,
                "SETTLEMENT_CONFIRMED");

        verify(legacy)
                .completeHearing(
                        "CASE_shared_hearing",
                        "hearing-window-CASE_shared_hearing",
                        false,
                        false);
        verify(draftRepository)
                .save(
                        argThat(
                                draft ->
                                        draft.getDraftStatus()
                                                        .equals("SETTLEMENT_CONFIRMED")
                                                && draft.getRecommendedDecision()
                                                        .equals(
                                                                "RESHIP_BY_CONFIRMED_SETTLEMENT")
                                                && draft.getDraftText()
                                                        .contains("商家同意补发正确型号")));
        verify(outcomeOrchestration)
                .orchestrate("CASE_shared_hearing", "temporal-worker");
        verify(legacy, never())
                .planRemedy(
                        "CASE_shared_hearing",
                        "hearing-window-CASE_shared_hearing");
        verify(legacy, never())
                .createReviewTask("CASE_shared_hearing", "REMEDY_shared_hearing");
    }
}

/*
 * 所属模块：共享小法庭。
 * 文件职责：验证庭审终态轮次恢复，覆盖 「repairsFormalJuryReportBeforeResignalingFinalRound」、「doesNotSignalWhenFormalJuryReportStillCannotBePersisted」、「doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A」、「skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion」、「rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.DisputeProperties;
import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.hearing.application.HearingCourtOrchestrator;
import com.example.dispute.hearing.application.HearingFinalRoundRecoveryService;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

// 所属模块：【共享小法庭 / 自动化测试层】类型「HearingFinalRoundRecoveryServiceTest」。
// 类型职责：集中验证庭审终态轮次恢复的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「repairsFormalJuryReportBeforeResignalingFinalRound」、「doesNotSignalWhenFormalJuryReportStillCannotBePersisted」、「doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A」、「skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion」、「rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class HearingFinalRoundRecoveryServiceTest {

    @Mock private HearingRoundRepository roundRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private HearingCourtOrchestrator courtOrchestrator;
    @Mock private HearingWorkflowCoordinator workflowCoordinator;
    @Mock private AgentRunCoordinator agentRunCoordinator;
    @Mock private DisputeProperties disputeProperties;

    private HearingFinalRoundRecoveryService service;

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalRoundRecoveryServiceTest.setUp()」。
    // 具体功能：「HearingFinalRoundRecoveryServiceTest.setUp()」：在每个测试场景运行前创建「disputeProperties.maxHearingRounds」、「when」、「when(disputeProperties.maxHearingRounds()).thenReturn」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「HearingFinalRoundRecoveryServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「HearingFinalRoundRecoveryServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingFinalRoundRecoveryServiceTest.setUp()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        when(disputeProperties.maxHearingRounds()).thenReturn(3);
        service =
                new HearingFinalRoundRecoveryService(
                        roundRepository,
                        draftRepository,
                        courtOrchestrator,
                        workflowCoordinator,
                        agentRunCoordinator,
                        disputeProperties);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound()」。
    // 具体功能：「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound()」：复现“核对完整业务行为（场景方法「repairsFormalJuryReportBeforeResignalingFinalRound」）”场景：驱动 「roundRepository.findFinalRoundsWithoutDraft」、「draftRepository.findByCaseIdAndDraftVersion」、「workflowCoordinator.roundCompletedNow」、「service.recoverFinalRoundsWithoutDraft」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_RECOVER」、「TRACE_HEARING_FINAL_RECOVERY_3」。
    // 上游调用：「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_RECOVER」、「TRACE_HEARING_FINAL_RECOVERY_3」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void repairsFormalJuryReportBeforeResignalingFinalRound() {
        HearingRoundEntity round = finalRound("CASE_RECOVER");
        when(roundRepository.findFinalRoundsWithoutDraft(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_RECOVER", 4))
                .thenReturn(Optional.empty());
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_RECOVER", 3))
                .thenReturn(true);
        when(workflowCoordinator.roundCompletedNow("CASE_RECOVER", 3, false))
                .thenReturn(true);

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isEqualTo(1);

        InOrder order = inOrder(courtOrchestrator, workflowCoordinator);
        order.verify(courtOrchestrator)
                .afterRoundClosed("CASE_RECOVER", 3, true, "TRACE_HEARING_FINAL_RECOVERY_3");
        order.verify(courtOrchestrator)
                .hasCompleteFormalJuryReport("CASE_RECOVER", 3);
        order.verify(workflowCoordinator)
                .roundCompletedNow("CASE_RECOVER", 3, false);
    }

    @Test
    void restartsFinalConvergenceWhenThePreviousWorkflowAlreadyTerminated() {
        HearingRoundEntity round = finalRound("CASE_RESTART");
        when(round.getDossierVersion()).thenReturn(7);
        when(roundRepository.findFinalRoundsWithoutDraft(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_RESTART", 4))
                .thenReturn(Optional.empty());
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_RESTART", 3))
                .thenReturn(true);
        when(workflowCoordinator.roundCompletedNow("CASE_RESTART", 3, false))
                .thenReturn(false);
        when(agentRunCoordinator.hasRestartableFinalConvergenceFailure(
                        "CASE_RESTART", "hearing-analysis:CASE_RESTART:3:final"))
                .thenReturn(true);
        when(workflowCoordinator.restartFinalConvergenceNow("CASE_RESTART", 7, 3))
                .thenReturn(true);

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isEqualTo(1);

        verify(workflowCoordinator).restartFinalConvergenceNow("CASE_RESTART", 7, 3);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted()」。
    // 具体功能：「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted()」：复现“核对完整业务行为（场景方法「doesNotSignalWhenFormalJuryReportStillCannotBePersisted」）”场景：驱动 「roundRepository.findFinalRoundsWithoutDraft」、「draftRepository.findByCaseIdAndDraftVersion」、「service.recoverFinalRoundsWithoutDraft」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_REPORT_MISSING」。
    // 上游调用：「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_REPORT_MISSING」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void doesNotSignalWhenFormalJuryReportStillCannotBePersisted() {
        HearingRoundEntity round = finalRound("CASE_REPORT_MISSING");
        when(roundRepository.findFinalRoundsWithoutDraft(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_REPORT_MISSING", 4))
                .thenReturn(Optional.empty());
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_REPORT_MISSING", 3))
                .thenReturn(false);

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isZero();

        verify(workflowCoordinator, never())
                .roundCompletedNow(any(), any(Integer.class), any(Boolean.class));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A()」。
    // 具体功能：「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A()」：复现“核对完整业务行为（场景方法「doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A」）”场景：驱动 「roundRepository.findFinalRoundsWithoutDraft」、「draftRepository.findByCaseIdAndDraftVersion」、「service.recoverFinalRoundsWithoutDraft」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_A2A_MISSING」。
    // 上游调用：「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_A2A_MISSING」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A() {
        HearingRoundEntity round = finalRound("CASE_A2A_MISSING");
        when(roundRepository.findFinalRoundsWithoutDraft(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_A2A_MISSING", 4))
                .thenReturn(Optional.empty());
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_A2A_MISSING", 3))
                .thenReturn(false);

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isZero();

        verify(workflowCoordinator, never())
                .roundCompletedNow(any(), any(Integer.class), any(Boolean.class));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalRoundRecoveryServiceTest.skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion()」。
    // 具体功能：「HearingFinalRoundRecoveryServiceTest.skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion()」：复现“核对完整业务行为（场景方法「skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion」）”场景：驱动 「roundRepository.findFinalRoundsWithoutDraft」、「draftRepository.findByCaseIdAndDraftVersion」、「service.recoverFinalRoundsWithoutDraft」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_ALREADY_DRAFTED」。
    // 上游调用：「HearingFinalRoundRecoveryServiceTest.skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingFinalRoundRecoveryServiceTest.skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingFinalRoundRecoveryServiceTest.skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_ALREADY_DRAFTED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion() {
        HearingRoundEntity round = finalRound("CASE_ALREADY_DRAFTED");
        when(roundRepository.findFinalRoundsWithoutDraft(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        any(Pageable.class)))
                .thenReturn(List.of(round));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_ALREADY_DRAFTED", 4))
                .thenReturn(Optional.of(mock()));

        assertThat(service.recoverFinalRoundsWithoutDraft(10)).isZero();

        verify(courtOrchestrator, never())
                .afterRoundClosed(any(), any(Integer.class), any(Boolean.class), any());
        verify(workflowCoordinator, never())
                .roundCompletedNow(any(), any(Integer.class), any(Boolean.class));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalRoundRecoveryServiceTest.rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate()」。
    // 具体功能：「HearingFinalRoundRecoveryServiceTest.rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate()」：复现“核对完整业务行为（场景方法「rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate」）”场景：驱动 「roundRepository.findFinalRoundsWithoutDraft」、「roundRepository.findFinalRoundsWithoutDraftAfter」、「draftRepository.findByCaseIdAndDraftVersion」、「workflowCoordinator.roundCompletedNow」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ROUND_PERMANENT_FAILURE」、「CASE_PERMANENT_FAILURE」、「2026-07-10T01:00:00Z」、「ROUND_RECOVERABLE」。
    // 上游调用：「HearingFinalRoundRecoveryServiceTest.rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingFinalRoundRecoveryServiceTest.rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingFinalRoundRecoveryServiceTest.rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate()」守住「共享小法庭」的可执行规格，尤其防止 「ROUND_PERMANENT_FAILURE」、「CASE_PERMANENT_FAILURE」、「2026-07-10T01:00:00Z」、「ROUND_RECOVERABLE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate() {
        HearingRoundEntity permanentlyFailing =
                finalRound(
                        "ROUND_PERMANENT_FAILURE",
                        "CASE_PERMANENT_FAILURE",
                        Instant.parse("2026-07-10T01:00:00Z"));
        HearingRoundEntity recoverable =
                finalRound(
                        "ROUND_RECOVERABLE",
                        "CASE_RECOVERABLE",
                        Instant.parse("2026-07-10T01:01:00Z"));
        when(roundRepository.findFinalRoundsWithoutDraft(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        any(Pageable.class)))
                .thenReturn(List.of(permanentlyFailing));
        when(roundRepository.findFinalRoundsWithoutDraftAfter(
                        eq(3),
                        eq(4),
                        eq(List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED)),
                        eq(Instant.parse("2026-07-10T01:00:00Z")),
                        eq("ROUND_PERMANENT_FAILURE"),
                        any(Pageable.class)))
                .thenReturn(List.of(recoverable));
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_PERMANENT_FAILURE", 4))
                .thenReturn(Optional.empty());
        when(draftRepository.findByCaseIdAndDraftVersion("CASE_RECOVERABLE", 4))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("permanent recovery failure"))
                .when(courtOrchestrator)
                .afterRoundClosed(
                        "CASE_PERMANENT_FAILURE",
                        3,
                        true,
                        "TRACE_HEARING_FINAL_RECOVERY_3");
        when(courtOrchestrator.hasCompleteFormalJuryReport("CASE_RECOVERABLE", 3))
                .thenReturn(true);
        when(workflowCoordinator.roundCompletedNow("CASE_RECOVERABLE", 3, false))
                .thenReturn(true);

        assertThat(service.recoverFinalRoundsWithoutDraft(1)).isZero();
        assertThat(service.recoverFinalRoundsWithoutDraft(1)).isEqualTo(1);

        verify(courtOrchestrator)
                .afterRoundClosed(
                        "CASE_RECOVERABLE",
                        3,
                        true,
                        "TRACE_HEARING_FINAL_RECOVERY_3");
        verify(workflowCoordinator)
                .roundCompletedNow("CASE_RECOVERABLE", 3, false);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalRoundRecoveryServiceTest.finalRound(String)」。
    // 具体功能：「HearingFinalRoundRecoveryServiceTest.finalRound(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「finalRound」）”组装或读取「Instant.parse」、「finalRound」，供本测试类的场景方法复用。
    // 上游调用：「HearingFinalRoundRecoveryServiceTest.finalRound(String)」由本测试类中的 「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A」、「HearingFinalRoundRecoveryServiceTest.skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion」 调用。
    // 下游影响：「HearingFinalRoundRecoveryServiceTest.finalRound(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingFinalRoundRecoveryServiceTest.finalRound(String)」守住「共享小法庭」的可执行规格，尤其防止 「ROUND_」、「2026-07-10T01:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static HearingRoundEntity finalRound(String caseId) {
        return finalRound(
                "ROUND_" + caseId,
                caseId,
                Instant.parse("2026-07-10T01:00:00Z"));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalRoundRecoveryServiceTest.finalRound(String,String,Instant)」。
    // 具体功能：「HearingFinalRoundRecoveryServiceTest.finalRound(String,String,Instant)」：作为测试辅助方法为“核对完整业务行为（场景方法「finalRound」）”组装或读取「round.getId」、「round.getCaseId」、「round.getClosedAt」、「mock」，供本测试类的场景方法复用。
    // 上游调用：「HearingFinalRoundRecoveryServiceTest.finalRound(String,String,Instant)」由本测试类中的 「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenFormalJuryReportStillCannotBePersisted」、「HearingFinalRoundRecoveryServiceTest.doesNotSignalWhenOnlyTheJuryRoomCardExistsWithoutFormalA2A」、「HearingFinalRoundRecoveryServiceTest.skipsFinalRoundsThatAlreadyHaveTheExactFinalDraftVersion」 调用。
    // 下游影响：「HearingFinalRoundRecoveryServiceTest.finalRound(String,String,Instant)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingFinalRoundRecoveryServiceTest.finalRound(String,String,Instant)」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static HearingRoundEntity finalRound(
            String roundId, String caseId, Instant closedAt) {
        HearingRoundEntity round = mock(HearingRoundEntity.class);
        when(round.getId()).thenReturn(roundId);
        when(round.getCaseId()).thenReturn(caseId);
        when(round.getClosedAt()).thenReturn(closedAt);
        return round;
    }
}

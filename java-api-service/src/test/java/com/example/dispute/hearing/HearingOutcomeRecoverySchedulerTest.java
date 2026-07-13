/*
 * 所属模块：共享小法庭。
 * 文件职责：验证庭审结果恢复，覆盖 「recoversFinalRoundConvergenceBeforeCompletedOutcomeReview」、「outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.application.HearingFinalRoundRecoveryService;
import com.example.dispute.hearing.application.HearingOutcomeOrchestrationService;
import com.example.dispute.hearing.application.HearingOutcomeRecoveryScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

// 所属模块：【共享小法庭 / 自动化测试层】类型「HearingOutcomeRecoverySchedulerTest」。
// 类型职责：集中验证庭审结果恢复的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「recoversFinalRoundConvergenceBeforeCompletedOutcomeReview」、「outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class HearingOutcomeRecoverySchedulerTest {

    private final HearingFinalRoundRecoveryService finalRoundRecoveryService =
            mock(HearingFinalRoundRecoveryService.class);
    private final HearingOutcomeOrchestrationService outcomeService =
            mock(HearingOutcomeOrchestrationService.class);
    private final HearingOutcomeRecoveryScheduler scheduler =
            new HearingOutcomeRecoveryScheduler(finalRoundRecoveryService, outcomeService);

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeRecoverySchedulerTest.recoversFinalRoundConvergenceBeforeCompletedOutcomeReview()」。
    // 具体功能：「HearingOutcomeRecoverySchedulerTest.recoversFinalRoundConvergenceBeforeCompletedOutcomeReview()」：复现“恢复中断状态（场景方法「recoversFinalRoundConvergenceBeforeCompletedOutcomeReview」）”场景：驱动 「scheduler.recover」、「inOrder」、「order.verify(finalRoundRecoveryService).recoverFinalRoundsWithoutDraft」、「order.verify(outcomeService).recoverCompletedHearingsWithoutReview」，再用 「verify」 核对返回值、状态变化或协作者调用。
    // 上游调用：「HearingOutcomeRecoverySchedulerTest.recoversFinalRoundConvergenceBeforeCompletedOutcomeReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingOutcomeRecoverySchedulerTest.recoversFinalRoundConvergenceBeforeCompletedOutcomeReview()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingOutcomeRecoverySchedulerTest.recoversFinalRoundConvergenceBeforeCompletedOutcomeReview()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void recoversFinalRoundConvergenceBeforeCompletedOutcomeReview() {
        scheduler.recover();

        InOrder order = inOrder(finalRoundRecoveryService, outcomeService);
        order.verify(finalRoundRecoveryService).recoverFinalRoundsWithoutDraft(20);
        order.verify(outcomeService).recoverCompletedHearingsWithoutReview(20);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeRecoverySchedulerTest.outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails()」。
    // 具体功能：「HearingOutcomeRecoverySchedulerTest.outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails()」：复现“核对完整业务行为（场景方法「outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails」）”场景：驱动 「finalRoundRecoveryService.recoverFinalRoundsWithoutDraft」，再用 「verify」 核对返回值、状态变化或协作者调用。
    // 上游调用：「HearingOutcomeRecoverySchedulerTest.outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingOutcomeRecoverySchedulerTest.outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingOutcomeRecoverySchedulerTest.outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails() {
        when(finalRoundRecoveryService.recoverFinalRoundsWithoutDraft(20))
                .thenThrow(new IllegalStateException("final round recovery unavailable"));

        scheduler.recover();

        verify(outcomeService).recoverCompletedHearingsWithoutReview(20);
    }
}

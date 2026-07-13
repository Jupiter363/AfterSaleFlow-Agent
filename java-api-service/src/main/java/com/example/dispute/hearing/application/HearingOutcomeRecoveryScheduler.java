/*
 * 所属模块：共享小法庭。
 * 文件职责：定时扫描庭审结果恢复的超时或中断状态并触发幂等恢复。
 * 业务链路：核心入口/契约为 「recover」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingOutcomeRecoveryScheduler」。
// 类型职责：定时扫描庭审结果恢复的超时或中断状态并触发幂等恢复；本类型显式提供 「HearingOutcomeRecoveryScheduler」、「recover」。
// 协作关系：主要由 「HearingOutcomeRecoverySchedulerTest.outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails」、「HearingOutcomeRecoverySchedulerTest.recoversFinalRoundConvergenceBeforeCompletedOutcomeReview」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class HearingOutcomeRecoveryScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingOutcomeRecoveryScheduler.class);
    private final HearingFinalRoundRecoveryService finalRoundRecoveryService;
    private final HearingOutcomeOrchestrationService outcomeService;

    // 所属模块：【共享小法庭 / 应用编排层】「HearingOutcomeRecoveryScheduler.HearingOutcomeRecoveryScheduler(HearingFinalRoundRecoveryService,HearingOutcomeOrchestrationService)」。
    // 具体功能：「HearingOutcomeRecoveryScheduler.HearingOutcomeRecoveryScheduler(HearingFinalRoundRecoveryService,HearingOutcomeOrchestrationService)」：通过构造器接收 「finalRoundRecoveryService」(HearingFinalRoundRecoveryService)、「outcomeService」(HearingOutcomeOrchestrationService) 并保存为「HearingOutcomeRecoveryScheduler」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingOutcomeRecoveryScheduler.HearingOutcomeRecoveryScheduler(HearingFinalRoundRecoveryService,HearingOutcomeOrchestrationService)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「HearingOutcomeRecoveryScheduler.HearingOutcomeRecoveryScheduler(HearingFinalRoundRecoveryService,HearingOutcomeOrchestrationService)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingOutcomeRecoveryScheduler.HearingOutcomeRecoveryScheduler(HearingFinalRoundRecoveryService,HearingOutcomeOrchestrationService)」负责主链路中的“庭审结果恢复调度器”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingOutcomeRecoveryScheduler(
            HearingFinalRoundRecoveryService finalRoundRecoveryService,
            HearingOutcomeOrchestrationService outcomeService) {
        this.finalRoundRecoveryService = finalRoundRecoveryService;
        this.outcomeService = outcomeService;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingOutcomeRecoveryScheduler.recover()」。
    // 具体功能：「HearingOutcomeRecoveryScheduler.recover()」：恢复庭审结果恢复；实际协作者为 「finalRoundRecoveryService.recoverFinalRoundsWithoutDraft」、「outcomeService.recoverCompletedHearingsWithoutReview」、「LOGGER.info」、「LOGGER.warn」，最终返回「void」。
    // 上游调用：「HearingOutcomeRecoveryScheduler.recover()」由 Spring 定时调度器触发；它在固定间隔扫描未收敛记录，不由浏览器直接触发。
    // 下游影响：「HearingOutcomeRecoveryScheduler.recover()」向下依次触达 「finalRoundRecoveryService.recoverFinalRoundsWithoutDraft」、「outcomeService.recoverCompletedHearingsWithoutReview」、「LOGGER.info」、「LOGGER.warn」。
    // 系统意义：「HearingOutcomeRecoveryScheduler.recover()」负责主链路中的“庭审结果恢复”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    @Scheduled(fixedDelayString = "${dispute.post-hearing-recovery-scan-delay:PT30S}")
    public void recover() {
        try {
            int finalRounds = finalRoundRecoveryService.recoverFinalRoundsWithoutDraft(20);
            if (finalRounds > 0) {
                LOGGER.info(
                        "Recovered sealed final hearing rounds into Temporal convergence: count={}",
                        finalRounds);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to recover sealed final hearing rounds", exception);
        }
        try {
            int recovered = outcomeService.recoverCompletedHearingsWithoutReview(20);
            if (recovered > 0) {
                LOGGER.info(
                        "Recovered completed hearing outcomes into review gate: count={}",
                        recovered);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to recover completed hearing outcomes", exception);
        }
    }
}

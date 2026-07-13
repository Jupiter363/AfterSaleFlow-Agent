/*
 * 所属模块：共享小法庭。
 * 文件职责：定时扫描庭审轮次截止时间的超时或中断状态并触发幂等恢复。
 * 业务链路：核心入口/契约为 「expireDueRounds」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingRoundDeadlineScheduler」。
// 类型职责：定时扫描庭审轮次截止时间的超时或中断状态并触发幂等恢复；本类型显式提供 「HearingRoundDeadlineScheduler」、「expireDueRounds」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class HearingRoundDeadlineScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingRoundDeadlineScheduler.class);

    private final HearingRoundService hearingRoundService;

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundDeadlineScheduler.HearingRoundDeadlineScheduler(HearingRoundService)」。
    // 具体功能：「HearingRoundDeadlineScheduler.HearingRoundDeadlineScheduler(HearingRoundService)」：通过构造器接收 「hearingRoundService」(HearingRoundService) 并保存为「HearingRoundDeadlineScheduler」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingRoundDeadlineScheduler.HearingRoundDeadlineScheduler(HearingRoundService)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「HearingRoundDeadlineScheduler.HearingRoundDeadlineScheduler(HearingRoundService)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRoundDeadlineScheduler.HearingRoundDeadlineScheduler(HearingRoundService)」负责主链路中的“庭审轮次截止时间调度器”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingRoundDeadlineScheduler(HearingRoundService hearingRoundService) {
        this.hearingRoundService = hearingRoundService;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingRoundDeadlineScheduler.expireDueRounds()」。
    // 具体功能：「HearingRoundDeadlineScheduler.expireDueRounds()」：标记过期DueRounds；实际协作者为 「hearingRoundService.expireDueRounds」、「LOGGER.info」，最终返回「void」。
    // 上游调用：「HearingRoundDeadlineScheduler.expireDueRounds()」由 Spring 定时调度器触发；它在固定间隔扫描未收敛记录，不由浏览器直接触发。
    // 下游影响：「HearingRoundDeadlineScheduler.expireDueRounds()」向下依次触达 「hearingRoundService.expireDueRounds」、「LOGGER.info」。
    // 系统意义：「HearingRoundDeadlineScheduler.expireDueRounds()」负责主链路中的“DueRounds”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    @Scheduled(fixedDelayString = "${dispute.hearing-round-timeout-scan-delay:PT15S}")
    public void expireDueRounds() {
        int expired = hearingRoundService.expireDueRounds();
        if (expired > 0) {
            LOGGER.info("Expired due hearing rounds: count={}", expired);
        }
    }
}

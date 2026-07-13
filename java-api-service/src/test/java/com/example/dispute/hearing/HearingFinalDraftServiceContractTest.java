/*
 * 所属模块：共享小法庭。
 * 文件职责：验证庭审终态草案Contract，覆盖 「finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.hearing.application.HearingFinalDraftService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

// 所属模块：【共享小法庭 / 自动化测试层】类型「HearingFinalDraftServiceContractTest」。
// 类型职责：集中验证庭审终态草案Contract的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class HearingFinalDraftServiceContractTest {

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingFinalDraftServiceContractTest.finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne()」。
    // 具体功能：「HearingFinalDraftServiceContractTest.finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne()」：复现“核对完整业务行为（场景方法「finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne」）”场景：驱动 「field.getType」、「doesNotContain」、「HearingFinalDraftService.class.getDeclaredMethods」、「HearingFinalDraftService.class.getDeclaredFields」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ensureDraftForFinalSealedRound」。
    // 上游调用：「HearingFinalDraftServiceContractTest.finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingFinalDraftServiceContractTest.finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingFinalDraftServiceContractTest.finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne()」守住「共享小法庭」的可执行规格，尤其防止 「ensureDraftForFinalSealedRound」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalDraftServiceCanOnlyAdoptATemporalDraftAndCannotGenerateOne() {
        assertThat(
                        Arrays.stream(HearingFinalDraftService.class.getDeclaredMethods())
                                .map(method -> method.getName()))
                .contains("adoptExistingDraftForFinalSealedRound")
                .doesNotContain("ensureDraftForFinalSealedRound");
        assertThat(
                        Arrays.stream(HearingFinalDraftService.class.getDeclaredFields())
                                .map(field -> field.getType().getName()))
                .doesNotContain(
                        "com.example.dispute.workflow.application.HearingAgentClient");
    }
}

/*
 * 所属模块：共享小法庭。
 * 文件职责：验证庭审，覆盖 「startFailuresDoNotPropagateAfterEvidenceHasBeenSealed」、「signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【共享小法庭 / 自动化测试层】类型「HearingWorkflowCoordinatorTest」。
// 类型职责：集中验证庭审的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「startFailuresDoNotPropagateAfterEvidenceHasBeenSealed」、「signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved」、「coordinator」、「appProperties」、「disputeProperties」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class HearingWorkflowCoordinatorTest {

    @Mock private WorkflowClient workflowClient;

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingWorkflowCoordinatorTest.startFailuresDoNotPropagateAfterEvidenceHasBeenSealed()」。
    // 具体功能：「HearingWorkflowCoordinatorTest.startFailuresDoNotPropagateAfterEvidenceHasBeenSealed()」：复现“核对完整业务行为（场景方法「startFailuresDoNotPropagateAfterEvidenceHasBeenSealed」）”场景：驱动 「workflowClient.newWorkflowStub」、「coordinator.startAfterCommit」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_1」。
    // 上游调用：「HearingWorkflowCoordinatorTest.startFailuresDoNotPropagateAfterEvidenceHasBeenSealed()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingWorkflowCoordinatorTest.startFailuresDoNotPropagateAfterEvidenceHasBeenSealed()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingWorkflowCoordinatorTest.startFailuresDoNotPropagateAfterEvidenceHasBeenSealed()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void startFailuresDoNotPropagateAfterEvidenceHasBeenSealed() {
        HearingWorkflowCoordinator coordinator = coordinator();
        when(workflowClient.newWorkflowStub(
                        eq(DisputeHearingWorkflow.class),
                        any(WorkflowOptions.class)))
                .thenThrow(new IllegalStateException("temporal unavailable"));

        assertThatCode(() -> coordinator.startAfterCommit("CASE_1", 1))
                .doesNotThrowAnyException();
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingWorkflowCoordinatorTest.signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved()」。
    // 具体功能：「HearingWorkflowCoordinatorTest.signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved()」：复现“核对完整业务行为（场景方法「signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved」）”场景：驱动 「workflowClient.newWorkflowStub」、「coordinator.roundCompletedAfterCommit」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「hearing-window-CASE_1」、「CASE_1」。
    // 上游调用：「HearingWorkflowCoordinatorTest.signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingWorkflowCoordinatorTest.signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingWorkflowCoordinatorTest.signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved()」守住「共享小法庭」的可执行规格，尤其防止 「hearing-window-CASE_1」、「CASE_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved() {
        HearingWorkflowCoordinator coordinator = coordinator();
        when(workflowClient.newWorkflowStub(
                        DisputeHearingWorkflow.class,
                        "hearing-window-CASE_1"))
                .thenThrow(new IllegalStateException("temporal unavailable"));

        assertThatCode(() -> coordinator.roundCompletedAfterCommit("CASE_1", 1, false))
                .doesNotThrowAnyException();
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingWorkflowCoordinatorTest.coordinator()」。
    // 具体功能：「HearingWorkflowCoordinatorTest.coordinator()」：作为测试辅助方法为“核对完整业务行为（场景方法「coordinator」）”组装或读取「HearingWorkflowCoordinator」、「PostCommitSideEffectExecutor」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「HearingWorkflowCoordinatorTest.coordinator()」由本测试类中的 「HearingWorkflowCoordinatorTest.startFailuresDoNotPropagateAfterEvidenceHasBeenSealed」、「HearingWorkflowCoordinatorTest.signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved」 调用。
    // 下游影响：「HearingWorkflowCoordinatorTest.coordinator()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingWorkflowCoordinatorTest.coordinator()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private HearingWorkflowCoordinator coordinator() {
        return new HearingWorkflowCoordinator(
                workflowClient,
                appProperties(),
                disputeProperties(),
                new PostCommitSideEffectExecutor(Runnable::run));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingWorkflowCoordinatorTest.appProperties()」。
    // 具体功能：「HearingWorkflowCoordinatorTest.appProperties()」：作为测试辅助方法为“核对完整业务行为（场景方法「appProperties」）”组装或读取「AppProperties」、「Security」、「Temporal」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「HearingWorkflowCoordinatorTest.appProperties()」由本测试类中的 「HearingWorkflowCoordinatorTest.coordinator」 调用。
    // 下游影响：「HearingWorkflowCoordinatorTest.appProperties()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingWorkflowCoordinatorTest.appProperties()」守住「共享小法庭」的可执行规格，尤其防止 「test」、「secret」、「localhost:7233」、「default」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AppProperties appProperties() {
        return new AppProperties(
                "test",
                new AppProperties.Security("secret"),
                null,
                null,
                new AppProperties.Temporal("localhost:7233", "default", "test-task-queue"),
                null,
                null,
                null,
                null);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingWorkflowCoordinatorTest.disputeProperties()」。
    // 具体功能：「HearingWorkflowCoordinatorTest.disputeProperties()」：作为测试辅助方法为“核对完整业务行为（场景方法「disputeProperties」）”组装或读取「DisputeProperties」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「HearingWorkflowCoordinatorTest.disputeProperties()」由本测试类中的 「HearingWorkflowCoordinatorTest.coordinator」 调用。
    // 下游影响：「HearingWorkflowCoordinatorTest.disputeProperties()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingWorkflowCoordinatorTest.disputeProperties()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static DisputeProperties disputeProperties() {
        return new DisputeProperties(
                Duration.ofHours(2),
                Duration.ofHours(3),
                Duration.ofMinutes(5),
                3,
                Duration.ofSeconds(15),
                true);
    }
}

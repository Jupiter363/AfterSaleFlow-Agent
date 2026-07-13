/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证证据Window，覆盖 「partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflow;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「EvidenceWindowCoordinatorTest」。
// 类型职责：集中验证证据Window的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest」、「appProperties」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceWindowCoordinatorTest {

    @Mock private WorkflowClient workflowClient;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「EvidenceWindowCoordinatorTest.partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest()」。
    // 具体功能：「EvidenceWindowCoordinatorTest.partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest()」：复现“核对完整业务行为（场景方法「partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest」）”场景：驱动 「workflowClient.newWorkflowStub」、「coordinator.signalPartyCompletedAfterCommit」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「evidence-window-CASE_1」、「CASE_1」、「USER」。
    // 上游调用：「EvidenceWindowCoordinatorTest.partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceWindowCoordinatorTest.partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceWindowCoordinatorTest.partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「evidence-window-CASE_1」、「CASE_1」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest() {
        EvidenceWindowCoordinator coordinator =
                new EvidenceWindowCoordinator(
                        workflowClient,
                        appProperties(),
                        new PostCommitSideEffectExecutor(Runnable::run));
        when(workflowClient.newWorkflowStub(
                        EvidenceWindowWorkflow.class,
                        "evidence-window-CASE_1"))
                .thenThrow(new IllegalStateException("temporal unavailable"));

        assertThatCode(
                        () ->
                                coordinator.signalPartyCompletedAfterCommit(
                                        "CASE_1", "USER"))
                .doesNotThrowAnyException();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「EvidenceWindowCoordinatorTest.appProperties()」。
    // 具体功能：「EvidenceWindowCoordinatorTest.appProperties()」：作为测试辅助方法为“核对完整业务行为（场景方法「appProperties」）”组装或读取「AppProperties」、「Security」、「Temporal」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「EvidenceWindowCoordinatorTest.appProperties()」由本测试类中的 「EvidenceWindowCoordinatorTest.partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest」 调用。
    // 下游影响：「EvidenceWindowCoordinatorTest.appProperties()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceWindowCoordinatorTest.appProperties()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「test」、「secret」、「localhost:7233」、「default」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
}

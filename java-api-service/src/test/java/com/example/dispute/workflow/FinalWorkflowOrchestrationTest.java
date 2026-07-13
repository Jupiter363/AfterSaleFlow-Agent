/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证终态Orchestration，覆盖 「transferredRouteTerminatesWithoutHearingReviewOrExecution」、「simpleHearingPlansRemedyButStillRequiresHumanReview」、「fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren」、「mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.DeliberationInterventionMode;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import com.example.dispute.workflow.domain.FulfillmentDisputeCommand;
import com.example.dispute.workflow.domain.FulfillmentDisputeResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.ReviewGateSnapshot;
import com.example.dispute.workflow.temporal.DeliberationPanelWorkflow;
import com.example.dispute.workflow.temporal.ExecutionWorkflow;
import com.example.dispute.workflow.temporal.FulfillmentDisputeActivities;
import com.example.dispute.workflow.temporal.FulfillmentDisputeWorkflow;
import com.example.dispute.workflow.temporal.FulfillmentDisputeWorkflowImpl;
import com.example.dispute.workflow.temporal.HumanReviewWorkflow;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「FinalWorkflowOrchestrationTest」。
// 类型职责：集中验证终态Orchestration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「tearDown」、「transferredRouteTerminatesWithoutHearingReviewOrExecution」、「simpleHearingPlansRemedyButStillRequiresHumanReview」、「fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren」、「mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class FinalWorkflowOrchestrationTest {

    private static final String TASK_QUEUE = "final-orchestration-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.setUp()」。
    // 具体功能：「FinalWorkflowOrchestrationTest.setUp()」：在每个测试场景运行前创建「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」、「TestWorkflowEnvironment.newInstance」、「environment.newWorker」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「FinalWorkflowOrchestrationTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「FinalWorkflowOrchestrationTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「FinalWorkflowOrchestrationTest.setUp()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                FulfillmentDisputeWorkflowImpl.class,
                StubHearingWorkflow.class,
                StubPanelWorkflow.class,
                StubReviewWorkflow.class,
                StubExecutionWorkflow.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
        StubHearingWorkflow.calls.set(0);
        StubPanelWorkflow.calls.set(0);
        StubPanelWorkflow.lastCommand = null;
        StubReviewWorkflow.calls.set(0);
        StubExecutionWorkflow.calls.set(0);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.tearDown()」。
    // 具体功能：「FinalWorkflowOrchestrationTest.tearDown()」：作为测试辅助方法为“核对完整业务行为（场景方法「tearDown」）”组装或读取「environment.close」，供本测试类的场景方法复用。
    // 上游调用：「FinalWorkflowOrchestrationTest.tearDown()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「FinalWorkflowOrchestrationTest.tearDown()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「FinalWorkflowOrchestrationTest.tearDown()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterEach
    void tearDown() {
        environment.close();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.transferredRouteTerminatesWithoutHearingReviewOrExecution()」。
    // 具体功能：「FinalWorkflowOrchestrationTest.transferredRouteTerminatesWithoutHearingReviewOrExecution()」：复现“核对完整业务行为（场景方法「transferredRouteTerminatesWithoutHearingReviewOrExecution」）”场景：驱动 「result.workflowStatus」、「result.nextStage」、「result.humanReviewRequired」、「workflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_transferred」、「CASE_transferred」、「COMPLETED」、「TRANSFERRED」。
    // 上游调用：「FinalWorkflowOrchestrationTest.transferredRouteTerminatesWithoutHearingReviewOrExecution()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FinalWorkflowOrchestrationTest.transferredRouteTerminatesWithoutHearingReviewOrExecution()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FinalWorkflowOrchestrationTest.transferredRouteTerminatesWithoutHearingReviewOrExecution()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_transferred」、「CASE_transferred」、「COMPLETED」、「TRANSFERRED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void transferredRouteTerminatesWithoutHearingReviewOrExecution() {
        FulfillmentDisputeResult result =
                workflow("WORKFLOW_transferred")
                        .run(command("CASE_transferred", "WORKFLOW_transferred",
                                RouteType.TRANSFERRED, false));

        assertThat(result.workflowStatus()).isEqualTo("COMPLETED");
        assertThat(result.nextStage()).isEqualTo("TRANSFERRED");
        assertThat(result.humanReviewRequired()).isFalse();
        assertThat(activities.transferredCalls).isEqualTo(1);
        assertThat(activities.remedyCalls).isZero();
        assertThat(StubHearingWorkflow.calls).hasValue(0);
        assertThat(StubReviewWorkflow.calls).hasValue(0);
        assertThat(StubExecutionWorkflow.calls).hasValue(0);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.simpleHearingPlansRemedyButStillRequiresHumanReview()」。
    // 具体功能：「FinalWorkflowOrchestrationTest.simpleHearingPlansRemedyButStillRequiresHumanReview()」：复现“核对完整业务行为（场景方法「simpleHearingPlansRemedyButStillRequiresHumanReview」）”场景：驱动 「result.nextStage」、「result.humanReviewRequired」、「workflow」、「command」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_simple」、「CASE_simple」、「EVALUATION_COMPLETE」。
    // 上游调用：「FinalWorkflowOrchestrationTest.simpleHearingPlansRemedyButStillRequiresHumanReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FinalWorkflowOrchestrationTest.simpleHearingPlansRemedyButStillRequiresHumanReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FinalWorkflowOrchestrationTest.simpleHearingPlansRemedyButStillRequiresHumanReview()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_simple」、「CASE_simple」、「EVALUATION_COMPLETE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void simpleHearingPlansRemedyButStillRequiresHumanReview() {
        FulfillmentDisputeResult result =
                workflow("WORKFLOW_simple")
                        .run(command("CASE_simple", "WORKFLOW_simple",
                                RouteType.SIMPLE_HEARING, false));

        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(result.humanReviewRequired()).isTrue();
        assertThat(StubHearingWorkflow.calls).hasValue(0);
        assertThat(StubPanelWorkflow.calls).hasValue(0);
        assertThat(StubReviewWorkflow.calls).hasValue(1);
        assertThat(StubExecutionWorkflow.calls).hasValue(1);
        assertThat(activities.closeCalls).isEqualTo(1);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren()」。
    // 具体功能：「FinalWorkflowOrchestrationTest.fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren()」：复现“核对完整业务行为（场景方法「fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren」）”场景：驱动 「result.nextStage」、「result.draftId」、「result.deliberationId」、「workflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_full」、「CASE_full」、「EVALUATION_COMPLETE」、「DRAFT_test」。
    // 上游调用：「FinalWorkflowOrchestrationTest.fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FinalWorkflowOrchestrationTest.fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FinalWorkflowOrchestrationTest.fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_full」、「CASE_full」、「EVALUATION_COMPLETE」、「DRAFT_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren() {
        FulfillmentDisputeResult result =
                workflow("WORKFLOW_full")
                        .run(command("CASE_full", "WORKFLOW_full",
                                RouteType.FULL_HEARING, true));

        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(result.draftId()).isEqualTo("DRAFT_test");
        assertThat(result.deliberationId()).isEqualTo("DELIBERATION_test");
        assertThat(StubHearingWorkflow.calls).hasValue(1);
        assertThat(StubPanelWorkflow.calls).hasValue(1);
        assertThat(StubPanelWorkflow.lastCommand.scoreThreshold()).isEqualTo(80);
        assertThat(StubPanelWorkflow.lastCommand.maxRevisionAttempts()).isEqualTo(2);
        assertThat(StubPanelWorkflow.lastCommand.triggerReasons())
                .contains("RISK_LEVEL_HIGH", "DELIBERATION_MODE_FINAL_ONLY");
        assertThat(StubReviewWorkflow.calls).hasValue(1);
        assertThat(StubExecutionWorkflow.calls).hasValue(1);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost()」。
    // 具体功能：「FinalWorkflowOrchestrationTest.mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost()」：复现“核对完整业务行为（场景方法「mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost」）”场景：驱动 「result.nextStage」、「result.draftId」、「result.deliberationId」、「workflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_medium」、「CASE_medium」、「MEDIUM」、「EVALUATION_COMPLETE」。
    // 上游调用：「FinalWorkflowOrchestrationTest.mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FinalWorkflowOrchestrationTest.mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FinalWorkflowOrchestrationTest.mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_medium」、「CASE_medium」、「MEDIUM」、「EVALUATION_COMPLETE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost() {
        FulfillmentDisputeResult result =
                workflow("WORKFLOW_medium")
                        .run(
                                command(
                                        "CASE_medium",
                                        "WORKFLOW_medium",
                                        RouteType.FULL_HEARING,
                                        "MEDIUM",
                                        DeliberationInterventionMode.FINAL_ONLY));

        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(result.draftId()).isEqualTo("DRAFT_test");
        assertThat(result.deliberationId()).isNull();
        assertThat(StubHearingWorkflow.calls).hasValue(1);
        assertThat(StubPanelWorkflow.calls).hasValue(0);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.workflow(String)」。
    // 具体功能：「FinalWorkflowOrchestrationTest.workflow(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「workflow」）”组装或读取「client.newWorkflowStub」、「WorkflowOptions.newBuilder」、「setTaskQueue」、「WorkflowOptions.newBuilder().setWorkflowId」，供本测试类的场景方法复用。
    // 上游调用：「FinalWorkflowOrchestrationTest.workflow(String)」由本测试类中的 「FinalWorkflowOrchestrationTest.transferredRouteTerminatesWithoutHearingReviewOrExecution」、「FinalWorkflowOrchestrationTest.simpleHearingPlansRemedyButStillRequiresHumanReview」、「FinalWorkflowOrchestrationTest.fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren」、「FinalWorkflowOrchestrationTest.mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost」 调用。
    // 下游影响：「FinalWorkflowOrchestrationTest.workflow(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「FinalWorkflowOrchestrationTest.workflow(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private FulfillmentDisputeWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                FulfillmentDisputeWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.command(String,String,RouteType,boolean)」。
    // 具体功能：「FinalWorkflowOrchestrationTest.command(String,String,RouteType,boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「FulfillmentDisputeCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「FinalWorkflowOrchestrationTest.command(String,String,RouteType,boolean)」由本测试类中的 「FinalWorkflowOrchestrationTest.transferredRouteTerminatesWithoutHearingReviewOrExecution」、「FinalWorkflowOrchestrationTest.simpleHearingPlansRemedyButStillRequiresHumanReview」、「FinalWorkflowOrchestrationTest.fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren」、「FinalWorkflowOrchestrationTest.mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost」 调用。
    // 下游影响：「FinalWorkflowOrchestrationTest.command(String,String,RouteType,boolean)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「FinalWorkflowOrchestrationTest.command(String,String,RouteType,boolean)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentDisputeCommand command(
            String caseId,
            String workflowId,
            RouteType routeType,
            boolean deliberationRequired) {
        return new FulfillmentDisputeCommand(
                caseId,
                workflowId,
                routeType,
                1,
                Duration.ofHours(24),
                Duration.ofDays(7),
                2,
                deliberationRequired);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.command(String,String,RouteType,String,DeliberationInterventionMode)」。
    // 具体功能：「FinalWorkflowOrchestrationTest.command(String,String,RouteType,String,DeliberationInterventionMode)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「FulfillmentDisputeCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「FinalWorkflowOrchestrationTest.command(String,String,RouteType,String,DeliberationInterventionMode)」由本测试类中的 「FinalWorkflowOrchestrationTest.transferredRouteTerminatesWithoutHearingReviewOrExecution」、「FinalWorkflowOrchestrationTest.simpleHearingPlansRemedyButStillRequiresHumanReview」、「FinalWorkflowOrchestrationTest.fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren」、「FinalWorkflowOrchestrationTest.mediumRiskFullHearingSkipsFinalOnlyPanelToControlCost」 调用。
    // 下游影响：「FinalWorkflowOrchestrationTest.command(String,String,RouteType,String,DeliberationInterventionMode)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「FinalWorkflowOrchestrationTest.command(String,String,RouteType,String,DeliberationInterventionMode)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「HIGH」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentDisputeCommand command(
            String caseId,
            String workflowId,
            RouteType routeType,
            String riskLevel,
            DeliberationInterventionMode mode) {
        return new FulfillmentDisputeCommand(
                caseId,
                workflowId,
                routeType,
                1,
                Duration.ofHours(24),
                Duration.ofDays(7),
                2,
                riskLevel,
                mode,
                "HIGH",
                80,
                2);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「StubHearingWorkflow」。
    // 类型职责：定义Stub庭审的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」、「submitEvidence」、「hearingRoundCompleted」、「settlementConfirmed」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    public static final class StubHearingWorkflow
            implements DisputeHearingWorkflow {
        static final AtomicInteger calls = new AtomicInteger();

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.StubHearingWorkflow.run(HearingWorkflowCommand)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.run(HearingWorkflowCommand)」：作为「StubHearingWorkflow」测试替身实现「run」：返回预设值 「newHearingWorkflowResult("DRAFT_test",false,f...」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.run(HearingWorkflowCommand)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.run(HearingWorkflowCommand)」下游仅修改测试内存状态或返回桩值：返回预设值 「newHearingWorkflowResult("DRAFT_test",false,f...」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.run(HearingWorkflowCommand)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「DRAFT_test」、「COMPLETED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public HearingWorkflowResult run(HearingWorkflowCommand command) {
            calls.incrementAndGet();
            return new HearingWorkflowResult(
                    "DRAFT_test", false, false, 2, "COMPLETED");
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.StubHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」：作为「StubHearingWorkflow」测试替身实现「submitEvidence」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void submitEvidence(
                com.example.dispute.workflow.domain.EvidenceSubmissionSignal signal) {}

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.StubHearingWorkflow.hearingRoundCompleted(int,boolean)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.hearingRoundCompleted(int,boolean)」：作为「StubHearingWorkflow」测试替身实现「hearingRoundCompleted」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.hearingRoundCompleted(int,boolean)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.hearingRoundCompleted(int,boolean)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.hearingRoundCompleted(int,boolean)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void hearingRoundCompleted(int roundNo, boolean factsSufficient) {}

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.StubHearingWorkflow.settlementConfirmed(int)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.settlementConfirmed(int)」：作为「StubHearingWorkflow」测试替身实现「settlementConfirmed」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.settlementConfirmed(int)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.settlementConfirmed(int)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.StubHearingWorkflow.settlementConfirmed(int)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void settlementConfirmed(int settlementVersion) {}
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「StubPanelWorkflow」。
    // 类型职责：定义StubPanel的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    public static final class StubPanelWorkflow
            implements DeliberationPanelWorkflow {
        static final AtomicInteger calls = new AtomicInteger();
        static volatile DeliberationPanelCommand lastCommand;

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.StubPanelWorkflow.run(DeliberationPanelCommand)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.StubPanelWorkflow.run(DeliberationPanelCommand)」：作为「StubPanelWorkflow」测试替身实现「run」：更新测试记录字段 「lastCommand」；返回预设值 「newDeliberationPanelResult("DELIBERATION_test...」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.StubPanelWorkflow.run(DeliberationPanelCommand)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.StubPanelWorkflow.run(DeliberationPanelCommand)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「lastCommand」；返回预设值 「newDeliberationPanelResult("DELIBERATION_test...」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.StubPanelWorkflow.run(DeliberationPanelCommand)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「DELIBERATION_test」、「NO_MAJOR_OBJECTION」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public DeliberationPanelResult run(DeliberationPanelCommand command) {
            calls.incrementAndGet();
            lastCommand = command;
            return new DeliberationPanelResult(
                    "DELIBERATION_test",
                    "NO_MAJOR_OBJECTION",
                    false,
                    false,
                    List.of(),
                    List.of());
        }
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「StubReviewWorkflow」。
    // 类型职责：定义Stub审核的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」、「submitDecision」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    public static final class StubReviewWorkflow
            implements HumanReviewWorkflow {
        static final AtomicInteger calls = new AtomicInteger();

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.StubReviewWorkflow.run(HumanReviewCommand)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.StubReviewWorkflow.run(HumanReviewCommand)」：作为「StubReviewWorkflow」测试替身实现「run」：返回预设值 「newHumanReviewResult("REVIEW_test","APPROVED"...」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.StubReviewWorkflow.run(HumanReviewCommand)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.StubReviewWorkflow.run(HumanReviewCommand)」下游仅修改测试内存状态或返回桩值：返回预设值 「newHumanReviewResult("REVIEW_test","APPROVED"...」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.StubReviewWorkflow.run(HumanReviewCommand)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「REVIEW_test」、「APPROVED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public HumanReviewResult run(HumanReviewCommand command) {
            calls.incrementAndGet();
            return new HumanReviewResult(
                    "REVIEW_test", "APPROVED", true, false, null);
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.StubReviewWorkflow.submitDecision(HumanReviewSignal)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.StubReviewWorkflow.submitDecision(HumanReviewSignal)」：作为「StubReviewWorkflow」测试替身实现「submitDecision」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.StubReviewWorkflow.submitDecision(HumanReviewSignal)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.StubReviewWorkflow.submitDecision(HumanReviewSignal)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.StubReviewWorkflow.submitDecision(HumanReviewSignal)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void submitDecision(
                com.example.dispute.workflow.domain.HumanReviewSignal signal) {}
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「StubExecutionWorkflow」。
    // 类型职责：定义Stub执行的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    public static final class StubExecutionWorkflow
            implements ExecutionWorkflow {
        static final AtomicInteger calls = new AtomicInteger();

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.StubExecutionWorkflow.run(ExecutionCommand)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.StubExecutionWorkflow.run(ExecutionCommand)」：作为「StubExecutionWorkflow」测试替身实现「run」：返回预设值 「newExecutionResult("SUCCEEDED",false,List.of())」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.StubExecutionWorkflow.run(ExecutionCommand)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.StubExecutionWorkflow.run(ExecutionCommand)」下游仅修改测试内存状态或返回桩值：返回预设值 「newExecutionResult("SUCCEEDED",false,List.of())」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.StubExecutionWorkflow.run(ExecutionCommand)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「SUCCEEDED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public ExecutionResult run(ExecutionCommand command) {
            calls.incrementAndGet();
            return new ExecutionResult("SUCCEEDED", false, List.of());
        }
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「RecordingActivities」。
    // 类型职责：定义Recording可由 Temporal 重试的 Activity 契约；本类型显式提供 「markTransferred」、「planRemedy」、「createReviewPacket」、「closeCaseAndEvaluate」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class RecordingActivities
            implements FulfillmentDisputeActivities {
        private int transferredCalls;
        private int remedyCalls;
        private int closeCalls;

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.RecordingActivities.markTransferred(String,String)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.RecordingActivities.markTransferred(String,String)」：作为「RecordingActivities」测试替身实现「markTransferred」：更新测试记录字段 「transferredCalls」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.RecordingActivities.markTransferred(String,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.RecordingActivities.markTransferred(String,String)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「transferredCalls」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.RecordingActivities.markTransferred(String,String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void markTransferred(String caseId, String workflowId) {
            transferredCalls++;
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.RecordingActivities.planRemedy(String,String,String,String)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.RecordingActivities.planRemedy(String,String,String,String)」：作为「RecordingActivities」测试替身实现「planRemedy」：更新测试记录字段 「remedyCalls」；返回预设值 「"REMEDY_test"」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.RecordingActivities.planRemedy(String,String,String,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.RecordingActivities.planRemedy(String,String,String,String)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「remedyCalls」；返回预设值 「"REMEDY_test"」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.RecordingActivities.planRemedy(String,String,String,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「REMEDY_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public String planRemedy(
                String caseId,
                String workflowId,
                String draftId,
                String deliberationId) {
            remedyCalls++;
            return "REMEDY_test";
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.RecordingActivities.createReviewPacket(String,String,String,String)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.RecordingActivities.createReviewPacket(String,String,String,String)」：作为「RecordingActivities」测试替身实现「createReviewPacket」：返回预设值 「newReviewGateSnapshot("REVIEW_test","PACKET_t...」；记录 「System.currentTimeMillis」、「Duration.ofDays」、「Duration.ofDays(7).toMillis」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.RecordingActivities.createReviewPacket(String,String,String,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.RecordingActivities.createReviewPacket(String,String,String,String)」下游仅修改测试内存状态或返回桩值：返回预设值 「newReviewGateSnapshot("REVIEW_test","PACKET_t...」；记录 「System.currentTimeMillis」、「Duration.ofDays」、「Duration.ofDays(7).toMillis」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.RecordingActivities.createReviewPacket(String,String,String,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「REVIEW_test」、「PACKET_test」、「ACTION_HASH_test」、「PLATFORM_REVIEWER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public ReviewGateSnapshot createReviewPacket(
                String caseId,
                String draftId,
                String deliberationId,
                String remedyPlanId) {
            return new ReviewGateSnapshot(
                    "REVIEW_test",
                    "PACKET_test",
                    1,
                    "ACTION_HASH_test",
                    System.currentTimeMillis() + Duration.ofDays(7).toMillis(),
                    "PLATFORM_REVIEWER");
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「FinalWorkflowOrchestrationTest.RecordingActivities.closeCaseAndEvaluate(String)」。
        // 具体功能：「FinalWorkflowOrchestrationTest.RecordingActivities.closeCaseAndEvaluate(String)」：作为「RecordingActivities」测试替身实现「closeCaseAndEvaluate」：更新测试记录字段 「closeCalls」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「FinalWorkflowOrchestrationTest.RecordingActivities.closeCaseAndEvaluate(String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「FinalWorkflowOrchestrationTest.RecordingActivities.closeCaseAndEvaluate(String)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「closeCalls」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「FinalWorkflowOrchestrationTest.RecordingActivities.closeCaseAndEvaluate(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void closeCaseAndEvaluate(String caseId) {
            closeCalls++;
        }
    }
}

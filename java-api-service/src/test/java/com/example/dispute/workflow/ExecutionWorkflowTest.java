/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证执行，覆盖 「validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys」、「unknownExternalResultIsLookedUpBeforeAnyRetryDecision」、「invalidOrExpiredApprovalNeverCallsExternalAction」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.ApprovalValidationResult;
import com.example.dispute.workflow.domain.ExecutionAction;
import com.example.dispute.workflow.domain.ExecutionActionActivityResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import com.example.dispute.workflow.temporal.ExecutionActivities;
import com.example.dispute.workflow.temporal.ExecutionWorkflow;
import com.example.dispute.workflow.temporal.ExecutionWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「ExecutionWorkflowTest」。
// 类型职责：集中验证执行的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「tearDown」、「validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys」、「unknownExternalResultIsLookedUpBeforeAnyRetryDecision」、「invalidOrExpiredApprovalNeverCallsExternalAction」、「workflow」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class ExecutionWorkflowTest {

    private static final String TASK_QUEUE = "final-execution-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.setUp()」。
    // 具体功能：「ExecutionWorkflowTest.setUp()」：在每个测试场景运行前创建「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」、「TestWorkflowEnvironment.newInstance」、「environment.newWorker」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「ExecutionWorkflowTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「ExecutionWorkflowTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ExecutionWorkflowTest.setUp()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                ExecutionWorkflowImpl.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.tearDown()」。
    // 具体功能：「ExecutionWorkflowTest.tearDown()」：作为测试辅助方法为“核对完整业务行为（场景方法「tearDown」）”组装或读取「environment.close」，供本测试类的场景方法复用。
    // 上游调用：「ExecutionWorkflowTest.tearDown()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「ExecutionWorkflowTest.tearDown()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ExecutionWorkflowTest.tearDown()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterEach
    void tearDown() {
        environment.close();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys()」。
    // 具体功能：「ExecutionWorkflowTest.validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys()」：复现“校验业务契约（场景方法「validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys」）”场景：驱动 「result.status」、「result.manualHandoff」、「workflow」、「command」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_execution_order」、「ACTION_notify」、「NOTIFY」、「IDEMPOTENCY_notify」。
    // 上游调用：「ExecutionWorkflowTest.validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ExecutionWorkflowTest.validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ExecutionWorkflowTest.validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_execution_order」、「ACTION_notify」、「NOTIFY」、「IDEMPOTENCY_notify」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys() {
        ExecutionResult result =
                workflow("WORKFLOW_execution_order")
                        .run(
                                command(
                                        List.of(
                                                new ExecutionAction(
                                                        "ACTION_notify",
                                                        "NOTIFY",
                                                        "IDEMPOTENCY_notify",
                                                        List.of("ACTION_refund")),
                                                new ExecutionAction(
                                                        "ACTION_refund",
                                                        "REFUND",
                                                        "IDEMPOTENCY_refund",
                                                        List.of()))));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.manualHandoff()).isFalse();
        assertThat(activities.executedActions)
                .containsExactly("ACTION_refund", "ACTION_notify");
        assertThat(activities.idempotencyKeys)
                .containsExactly(
                        "IDEMPOTENCY_refund", "IDEMPOTENCY_notify");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.unknownExternalResultIsLookedUpBeforeAnyRetryDecision()」。
    // 具体功能：「ExecutionWorkflowTest.unknownExternalResultIsLookedUpBeforeAnyRetryDecision()」：复现“核对完整业务行为（场景方法「unknownExternalResultIsLookedUpBeforeAnyRetryDecision」）”场景：驱动 「result.status」、「workflow」、「command」、「workflow("WORKFLOW_execution_lookup").run」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「ACTION_refund」、「WORKFLOW_execution_lookup」、「REFUND」、「IDEMPOTENCY_refund」。
    // 上游调用：「ExecutionWorkflowTest.unknownExternalResultIsLookedUpBeforeAnyRetryDecision()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ExecutionWorkflowTest.unknownExternalResultIsLookedUpBeforeAnyRetryDecision()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ExecutionWorkflowTest.unknownExternalResultIsLookedUpBeforeAnyRetryDecision()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「ACTION_refund」、「WORKFLOW_execution_lookup」、「REFUND」、「IDEMPOTENCY_refund」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void unknownExternalResultIsLookedUpBeforeAnyRetryDecision() {
        activities.unknownAction = "ACTION_refund";

        ExecutionResult result =
                workflow("WORKFLOW_execution_lookup")
                        .run(
                                command(
                                        List.of(
                                                new ExecutionAction(
                                                        "ACTION_refund",
                                                        "REFUND",
                                                        "IDEMPOTENCY_refund",
                                                        List.of()))));

        assertThat(activities.lookupKeys)
                .containsExactly("IDEMPOTENCY_refund");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction()」。
    // 具体功能：「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction()」：复现“核对完整业务行为（场景方法「invalidOrExpiredApprovalNeverCallsExternalAction」）”场景：驱动 「invalid.status」、「environment.currentTimeMillis」、「expiredResult.status」、「workflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_execution_invalid」、「ACTION_refund」、「REFUND」、「IDEMPOTENCY_refund」。
    // 上游调用：「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_execution_invalid」、「ACTION_refund」、「REFUND」、「IDEMPOTENCY_refund」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void invalidOrExpiredApprovalNeverCallsExternalAction() {
        activities.approvalValid = false;
        ExecutionResult invalid =
                workflow("WORKFLOW_execution_invalid")
                        .run(command(List.of(
                                new ExecutionAction(
                                        "ACTION_refund",
                                        "REFUND",
                                        "IDEMPOTENCY_refund",
                                        List.of()))));

        assertThat(invalid.status()).isEqualTo("MANUAL_HANDOFF");
        assertThat(activities.executedActions).isEmpty();

        activities.approvalValid = true;
        ExecutionCommand expired =
                new ExecutionCommand(
                        "CASE_execution",
                        "REVIEW_execution",
                        2,
                        "ACTION_HASH_1",
                        true,
                        environment.currentTimeMillis() - 1,
                        List.of(
                                new ExecutionAction(
                                        "ACTION_refund",
                                        "REFUND",
                                        "IDEMPOTENCY_refund",
                                        List.of())));
        ExecutionResult expiredResult =
                workflow("WORKFLOW_execution_expired").run(expired);
        assertThat(expiredResult.status()).isEqualTo("MANUAL_HANDOFF");
        assertThat(activities.executedActions).isEmpty();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.workflow(String)」。
    // 具体功能：「ExecutionWorkflowTest.workflow(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「workflow」）”组装或读取「client.newWorkflowStub」、「WorkflowOptions.newBuilder」、「setTaskQueue」、「WorkflowOptions.newBuilder().setWorkflowId」，供本测试类的场景方法复用。
    // 上游调用：「ExecutionWorkflowTest.workflow(String)」由本测试类中的 「ExecutionWorkflowTest.validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys」、「ExecutionWorkflowTest.unknownExternalResultIsLookedUpBeforeAnyRetryDecision」、「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction」 调用。
    // 下游影响：「ExecutionWorkflowTest.workflow(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ExecutionWorkflowTest.workflow(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private ExecutionWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                ExecutionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.command(List)」。
    // 具体功能：「ExecutionWorkflowTest.command(List)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「ExecutionCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「ExecutionWorkflowTest.command(List)」由本测试类中的 「ExecutionWorkflowTest.validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys」、「ExecutionWorkflowTest.unknownExternalResultIsLookedUpBeforeAnyRetryDecision」、「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction」 调用。
    // 下游影响：「ExecutionWorkflowTest.command(List)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ExecutionWorkflowTest.command(List)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_execution」、「REVIEW_execution」、「ACTION_HASH_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private ExecutionCommand command(List<ExecutionAction> actions) {
        return new ExecutionCommand(
                "CASE_execution",
                "REVIEW_execution",
                2,
                "ACTION_HASH_1",
                true,
                environment.currentTimeMillis()
                        + Duration.ofHours(1).toMillis(),
                actions);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「RecordingActivities」。
    // 类型职责：定义Recording可由 Temporal 重试的 Activity 契约；本类型显式提供 「validateApproval」、「executeAction」、「lookupAction」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class RecordingActivities
            implements ExecutionActivities {
        private final List<String> executedActions =
                new CopyOnWriteArrayList<>();
        private final List<String> idempotencyKeys =
                new CopyOnWriteArrayList<>();
        private final List<String> lookupKeys =
                new CopyOnWriteArrayList<>();
        private volatile boolean approvalValid = true;
        private volatile String unknownAction;

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.RecordingActivities.validateApproval(ExecutionCommand)」。
        // 具体功能：「ExecutionWorkflowTest.RecordingActivities.validateApproval(ExecutionCommand)」：作为「RecordingActivities」测试替身实现「validateApproval」：返回预设值 「newApprovalValidationResult(approvalValid,app...」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「ExecutionWorkflowTest.RecordingActivities.validateApproval(ExecutionCommand)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「ExecutionWorkflowTest.RecordingActivities.validateApproval(ExecutionCommand)」下游仅修改测试内存状态或返回桩值：返回预设值 「newApprovalValidationResult(approvalValid,app...」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「ExecutionWorkflowTest.RecordingActivities.validateApproval(ExecutionCommand)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「ACTION_HASH_MISMATCH」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public ApprovalValidationResult validateApproval(
                ExecutionCommand command) {
            return new ApprovalValidationResult(
                    approvalValid,
                    approvalValid ? null : "ACTION_HASH_MISMATCH");
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.RecordingActivities.executeAction(String,ExecutionAction)」。
        // 具体功能：「ExecutionWorkflowTest.RecordingActivities.executeAction(String,ExecutionAction)」：作为「RecordingActivities」测试替身实现「executeAction」：返回预设值 「newExecutionActionActivityResult(action.actio...」；记录 「action.actionId」、「action.idempotencyKey」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「ExecutionWorkflowTest.RecordingActivities.executeAction(String,ExecutionAction)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「ExecutionWorkflowTest.RecordingActivities.executeAction(String,ExecutionAction)」下游仅修改测试内存状态或返回桩值：返回预设值 「newExecutionActionActivityResult(action.actio...」；记录 「action.actionId」、「action.idempotencyKey」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「ExecutionWorkflowTest.RecordingActivities.executeAction(String,ExecutionAction)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「UNKNOWN」、「SUCCEEDED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public ExecutionActionActivityResult executeAction(
                String caseId,
                ExecutionAction action) {
            executedActions.add(action.actionId());
            idempotencyKeys.add(action.idempotencyKey());
            return new ExecutionActionActivityResult(
                    action.actionId(),
                    action.actionId().equals(unknownAction)
                            ? "UNKNOWN"
                            : "SUCCEEDED",
                    null);
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「ExecutionWorkflowTest.RecordingActivities.lookupAction(String,ExecutionAction)」。
        // 具体功能：「ExecutionWorkflowTest.RecordingActivities.lookupAction(String,ExecutionAction)」：作为「RecordingActivities」测试替身实现「lookupAction」：返回预设值 「newExecutionActionActivityResult(action.actio...」；记录 「action.idempotencyKey」、「action.actionId」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「ExecutionWorkflowTest.RecordingActivities.lookupAction(String,ExecutionAction)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「ExecutionWorkflowTest.RecordingActivities.lookupAction(String,ExecutionAction)」下游仅修改测试内存状态或返回桩值：返回预设值 「newExecutionActionActivityResult(action.actio...」；记录 「action.idempotencyKey」、「action.actionId」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「ExecutionWorkflowTest.RecordingActivities.lookupAction(String,ExecutionAction)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「SUCCEEDED」、「EXTERNAL_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public ExecutionActionActivityResult lookupAction(
                String caseId,
                ExecutionAction action) {
            lookupKeys.add(action.idempotencyKey());
            return new ExecutionActionActivityResult(
                    action.actionId(), "SUCCEEDED", "EXTERNAL_1");
        }
    }
}

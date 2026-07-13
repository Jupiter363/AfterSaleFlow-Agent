/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证证据Window，覆盖 「oneAbsentPartyCausesExpiryAfterTwoVirtualHours」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.domain.EvidenceWindowResult;
import com.example.dispute.workflow.temporal.EvidenceWindowActivities;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflow;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「EvidenceWindowWorkflowTest」。
// 类型职责：集中验证证据Window的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「tearDown」、「oneAbsentPartyCausesExpiryAfterTwoVirtualHours」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class EvidenceWindowWorkflowTest {

    private static final String TASK_QUEUE = "evidence-window-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「EvidenceWindowWorkflowTest.setUp()」。
    // 具体功能：「EvidenceWindowWorkflowTest.setUp()」：在每个测试场景运行前创建「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」、「TestWorkflowEnvironment.newInstance」、「environment.newWorker」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceWindowWorkflowTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceWindowWorkflowTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceWindowWorkflowTest.setUp()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(EvidenceWindowWorkflowImpl.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「EvidenceWindowWorkflowTest.tearDown()」。
    // 具体功能：「EvidenceWindowWorkflowTest.tearDown()」：作为测试辅助方法为“核对完整业务行为（场景方法「tearDown」）”组装或读取「environment.close」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceWindowWorkflowTest.tearDown()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceWindowWorkflowTest.tearDown()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceWindowWorkflowTest.tearDown()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterEach
    void tearDown() {
        environment.close();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「EvidenceWindowWorkflowTest.oneAbsentPartyCausesExpiryAfterTwoVirtualHours()」。
    // 具体功能：「EvidenceWindowWorkflowTest.oneAbsentPartyCausesExpiryAfterTwoVirtualHours()」：复现“核对完整业务行为（场景方法「oneAbsentPartyCausesExpiryAfterTwoVirtualHours」）”场景：驱动 「client.newWorkflowStub」、「WorkflowClient.start」、「workflow.partyCompleted」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「evidence-window-CASE_TIMEOUT」、「CASE_TIMEOUT」、「USER」、「DEADLINE_EXPIRED」。
    // 上游调用：「EvidenceWindowWorkflowTest.oneAbsentPartyCausesExpiryAfterTwoVirtualHours()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceWindowWorkflowTest.oneAbsentPartyCausesExpiryAfterTwoVirtualHours()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceWindowWorkflowTest.oneAbsentPartyCausesExpiryAfterTwoVirtualHours()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「evidence-window-CASE_TIMEOUT」、「CASE_TIMEOUT」、「USER」、「DEADLINE_EXPIRED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void oneAbsentPartyCausesExpiryAfterTwoVirtualHours() {
        long startedAt = environment.currentTimeMillis();
        EvidenceWindowWorkflow workflow =
                client.newWorkflowStub(
                        EvidenceWindowWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId("evidence-window-CASE_TIMEOUT")
                                .setTaskQueue(TASK_QUEUE)
                                .build());
        WorkflowClient.start(
                workflow::run,
                new EvidenceWindowCommand("CASE_TIMEOUT", Duration.ofHours(2)));
        workflow.partyCompleted("USER");

        EvidenceWindowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(EvidenceWindowResult.class);

        assertThat(result.stopReason()).isEqualTo("DEADLINE_EXPIRED");
        assertThat(result.completedRoles()).containsExactly("USER");
        assertThat(activities.warnedCases).containsExactly("CASE_TIMEOUT");
        assertThat(activities.expiredCases).containsExactly("CASE_TIMEOUT");
        assertThat(environment.currentTimeMillis())
                .isGreaterThanOrEqualTo(
                        startedAt + Duration.ofHours(2).toMillis());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「RecordingActivities」。
    // 类型职责：定义Recording可由 Temporal 重试的 Activity 契约；本类型显式提供 「warn」、「expire」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    static final class RecordingActivities implements EvidenceWindowActivities {
        final List<String> expiredCases = new ArrayList<>();
        final List<String> warnedCases = new ArrayList<>();

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「EvidenceWindowWorkflowTest.RecordingActivities.warn(String)」。
        // 具体功能：「EvidenceWindowWorkflowTest.RecordingActivities.warn(String)」：作为「RecordingActivities」测试替身实现「warn」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「EvidenceWindowWorkflowTest.RecordingActivities.warn(String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「EvidenceWindowWorkflowTest.RecordingActivities.warn(String)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「EvidenceWindowWorkflowTest.RecordingActivities.warn(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void warn(String caseId) {
            warnedCases.add(caseId);
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「EvidenceWindowWorkflowTest.RecordingActivities.expire(String)」。
        // 具体功能：「EvidenceWindowWorkflowTest.RecordingActivities.expire(String)」：作为「RecordingActivities」测试替身实现「expire」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「EvidenceWindowWorkflowTest.RecordingActivities.expire(String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「EvidenceWindowWorkflowTest.RecordingActivities.expire(String)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「EvidenceWindowWorkflowTest.RecordingActivities.expire(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void expire(String caseId) {
            expiredCases.add(caseId);
        }
    }
}

/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证人工审核，覆盖 「validatesReviewerRolePacketVersionAndActionHashBeforeApproval」、「modifyReturnRejectAndEscalateRemainDistinctDecisions」、「expiredPacketCannotBeApproved」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import com.example.dispute.workflow.temporal.HumanReviewActivities;
import com.example.dispute.workflow.temporal.HumanReviewWorkflow;
import com.example.dispute.workflow.temporal.HumanReviewWorkflowImpl;
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

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「HumanReviewWorkflowTest」。
// 类型职责：集中验证人工审核的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「tearDown」、「validatesReviewerRolePacketVersionAndActionHashBeforeApproval」、「modifyReturnRejectAndEscalateRemainDistinctDecisions」、「expiredPacketCannotBeApproved」、「assertDecision」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class HumanReviewWorkflowTest {

    private static final String TASK_QUEUE = "final-review-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.setUp()」。
    // 具体功能：「HumanReviewWorkflowTest.setUp()」：在每个测试场景运行前创建「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」、「TestWorkflowEnvironment.newInstance」、「environment.newWorker」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「HumanReviewWorkflowTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「HumanReviewWorkflowTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HumanReviewWorkflowTest.setUp()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                HumanReviewWorkflowImpl.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.tearDown()」。
    // 具体功能：「HumanReviewWorkflowTest.tearDown()」：作为测试辅助方法为“核对完整业务行为（场景方法「tearDown」）”组装或读取「environment.close」，供本测试类的场景方法复用。
    // 上游调用：「HumanReviewWorkflowTest.tearDown()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「HumanReviewWorkflowTest.tearDown()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HumanReviewWorkflowTest.tearDown()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterEach
    void tearDown() {
        environment.close();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.validatesReviewerRolePacketVersionAndActionHashBeforeApproval()」。
    // 具体功能：「HumanReviewWorkflowTest.validatesReviewerRolePacketVersionAndActionHashBeforeApproval()」：复现“校验业务契约（场景方法「validatesReviewerRolePacketVersionAndActionHashBeforeApproval」）”场景：驱动 「WorkflowClient.start」、「workflow.submitDecision」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_review_validate」、「reviewer-1」、「MERCHANT」、「APPROVE」。
    // 上游调用：「HumanReviewWorkflowTest.validatesReviewerRolePacketVersionAndActionHashBeforeApproval()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HumanReviewWorkflowTest.validatesReviewerRolePacketVersionAndActionHashBeforeApproval()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HumanReviewWorkflowTest.validatesReviewerRolePacketVersionAndActionHashBeforeApproval()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_review_validate」、「reviewer-1」、「MERCHANT」、「APPROVE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void validatesReviewerRolePacketVersionAndActionHashBeforeApproval() {
        HumanReviewWorkflow workflow = workflow("WORKFLOW_review_validate");
        WorkflowClient.start(
                workflow::run,
                command(environment.currentTimeMillis() + Duration.ofDays(1).toMillis()));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "MERCHANT",
                        "APPROVE",
                        2,
                        "ACTION_HASH_1",
                        "HUMAN_REVIEW_1",
                        "unauthorized role"));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        "APPROVE",
                        1,
                        "ACTION_HASH_1",
                        "HUMAN_REVIEW_1",
                        "stale packet"));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        "APPROVE",
                        2,
                        "WRONG_HASH",
                        "HUMAN_REVIEW_1",
                        "wrong action hash"));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        "APPROVE",
                        2,
                        "ACTION_HASH_1",
                        "HUMAN_REVIEW_1",
                        "evidence and policy verified"));

        HumanReviewResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HumanReviewResult.class);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.approved()).isTrue();
        assertThat(activities.invalidReasons)
                .containsExactly(
                        "UNAUTHORIZED_REVIEWER_ROLE",
                        "STALE_REVIEW_PACKET",
                        "ACTION_HASH_MISMATCH");
        assertThat(activities.acceptedDecisions).containsExactly("APPROVE");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.modifyReturnRejectAndEscalateRemainDistinctDecisions()」。
    // 具体功能：「HumanReviewWorkflowTest.modifyReturnRejectAndEscalateRemainDistinctDecisions()」：复现“核对完整业务行为（场景方法「modifyReturnRejectAndEscalateRemainDistinctDecisions」）”场景：驱动 「assertDecision」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MODIFY_AND_APPROVE」、「MODIFIED_AND_APPROVED」、「RETURN_FOR_REVISION」、「RETURNED_FOR_REVISION」。
    // 上游调用：「HumanReviewWorkflowTest.modifyReturnRejectAndEscalateRemainDistinctDecisions()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HumanReviewWorkflowTest.modifyReturnRejectAndEscalateRemainDistinctDecisions()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HumanReviewWorkflowTest.modifyReturnRejectAndEscalateRemainDistinctDecisions()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「MODIFY_AND_APPROVE」、「MODIFIED_AND_APPROVED」、「RETURN_FOR_REVISION」、「RETURNED_FOR_REVISION」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void modifyReturnRejectAndEscalateRemainDistinctDecisions() {
        assertDecision("MODIFY_AND_APPROVE", "MODIFIED_AND_APPROVED", true, true);
        assertDecision("RETURN_FOR_REVISION", "RETURNED_FOR_REVISION", false, false);
        assertDecision("REQUEST_MORE_EVIDENCE", "MORE_EVIDENCE_REQUESTED", false, false);
        assertDecision("REJECT", "REJECTED", false, false);
        assertDecision("ESCALATE", "ESCALATED", false, false);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.expiredPacketCannotBeApproved()」。
    // 具体功能：「HumanReviewWorkflowTest.expiredPacketCannotBeApproved()」：复现“核对完整业务行为（场景方法「expiredPacketCannotBeApproved」）”场景：驱动 「environment.currentTimeMillis」、「result.status」、「result.approved」、「result.failureReason」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_review_expired」、「EXPIRED」、「REVIEW_PACKET_EXPIRED」。
    // 上游调用：「HumanReviewWorkflowTest.expiredPacketCannotBeApproved()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HumanReviewWorkflowTest.expiredPacketCannotBeApproved()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HumanReviewWorkflowTest.expiredPacketCannotBeApproved()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_review_expired」、「EXPIRED」、「REVIEW_PACKET_EXPIRED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void expiredPacketCannotBeApproved() {
        HumanReviewResult result =
                workflow("WORKFLOW_review_expired")
                        .run(command(environment.currentTimeMillis() - 1));

        assertThat(result.status()).isEqualTo("EXPIRED");
        assertThat(result.approved()).isFalse();
        assertThat(result.failureReason()).isEqualTo("REVIEW_PACKET_EXPIRED");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.assertDecision(String,String,boolean,boolean)」。
    // 具体功能：「HumanReviewWorkflowTest.assertDecision(String,String,boolean,boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「assertDecision」）”组装或读取「HumanReviewSignal」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「HumanReviewWorkflowTest.assertDecision(String,String,boolean,boolean)」由本测试类中的 「HumanReviewWorkflowTest.modifyReturnRejectAndEscalateRemainDistinctDecisions」 调用。
    // 下游影响：「HumanReviewWorkflowTest.assertDecision(String,String,boolean,boolean)」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HumanReviewWorkflowTest.assertDecision(String,String,boolean,boolean)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_review_」、「reviewer-1」、「PLATFORM_REVIEWER」、「ACTION_HASH_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void assertDecision(
            String decision,
            String expectedStatus,
            boolean approved,
            boolean modified) {
        String workflowId = "WORKFLOW_review_" + decision.toLowerCase();
        HumanReviewWorkflow workflow = workflow(workflowId);
        WorkflowClient.start(
                workflow::run,
                command(environment.currentTimeMillis() + Duration.ofDays(1).toMillis()));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        decision,
                        2,
                        "ACTION_HASH_1",
                        "HUMAN_REVIEW_1",
                        "review reason"));
        HumanReviewResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HumanReviewResult.class);
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.approved()).isEqualTo(approved);
        assertThat(result.modified()).isEqualTo(modified);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.workflow(String)」。
    // 具体功能：「HumanReviewWorkflowTest.workflow(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「workflow」）”组装或读取「client.newWorkflowStub」、「WorkflowOptions.newBuilder」、「setTaskQueue」、「WorkflowOptions.newBuilder().setWorkflowId」，供本测试类的场景方法复用。
    // 上游调用：「HumanReviewWorkflowTest.workflow(String)」由本测试类中的 「HumanReviewWorkflowTest.validatesReviewerRolePacketVersionAndActionHashBeforeApproval」、「HumanReviewWorkflowTest.expiredPacketCannotBeApproved」、「HumanReviewWorkflowTest.assertDecision」 调用。
    // 下游影响：「HumanReviewWorkflowTest.workflow(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HumanReviewWorkflowTest.workflow(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private HumanReviewWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                HumanReviewWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.command(long)」。
    // 具体功能：「HumanReviewWorkflowTest.command(long)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「HumanReviewCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「HumanReviewWorkflowTest.command(long)」由本测试类中的 「HumanReviewWorkflowTest.validatesReviewerRolePacketVersionAndActionHashBeforeApproval」、「HumanReviewWorkflowTest.expiredPacketCannotBeApproved」、「HumanReviewWorkflowTest.assertDecision」 调用。
    // 下游影响：「HumanReviewWorkflowTest.command(long)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HumanReviewWorkflowTest.command(long)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_review」、「PACKET_review」、「ACTION_HASH_1」、「PLATFORM_REVIEWER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static HumanReviewCommand command(long expiresAt) {
        return new HumanReviewCommand(
                "CASE_review",
                "PACKET_review",
                2,
                "ACTION_HASH_1",
                expiresAt,
                Duration.ofDays(7),
                "PLATFORM_REVIEWER");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「RecordingActivities」。
    // 类型职责：定义Recording可由 Temporal 重试的 Activity 契约；本类型显式提供 「recordInvalidDecision」、「persistDecision」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class RecordingActivities
            implements HumanReviewActivities {
        private final List<String> invalidReasons =
                new CopyOnWriteArrayList<>();
        private final List<String> acceptedDecisions =
                new CopyOnWriteArrayList<>();

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.RecordingActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」。
        // 具体功能：「HumanReviewWorkflowTest.RecordingActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」：作为「RecordingActivities」测试替身实现「recordInvalidDecision」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「HumanReviewWorkflowTest.RecordingActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HumanReviewWorkflowTest.RecordingActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「HumanReviewWorkflowTest.RecordingActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void recordInvalidDecision(
                HumanReviewCommand command,
                HumanReviewSignal signal,
                String reason) {
            invalidReasons.add(reason);
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HumanReviewWorkflowTest.RecordingActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」。
        // 具体功能：「HumanReviewWorkflowTest.RecordingActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」：作为「RecordingActivities」测试替身实现「persistDecision」：返回预设值 「"REVIEW_test"」；记录 「signal.decision」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「HumanReviewWorkflowTest.RecordingActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HumanReviewWorkflowTest.RecordingActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」下游仅修改测试内存状态或返回桩值：返回预设值 「"REVIEW_test"」；记录 「signal.decision」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「HumanReviewWorkflowTest.RecordingActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「REVIEW_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public String persistDecision(
                HumanReviewCommand command,
                HumanReviewSignal signal,
                String status) {
            acceptedDecisions.add(signal.decision());
            return "REVIEW_test";
        }
    }
}

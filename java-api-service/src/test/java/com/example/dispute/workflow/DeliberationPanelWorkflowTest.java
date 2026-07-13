/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证评议Panel，覆盖 「runsOnlyRiskSelectedCriticsAgainstOneFrozenInput」、「blockerAndMinorityMajorObjectionCannotBeAveragedAway」、「criticScoreBelowThresholdRequiresRevision」、「failedOrTimedOutCriticRequiresManualReview」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.CriticActivityResult;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import com.example.dispute.workflow.domain.FrozenDeliberationSnapshot;
import com.example.dispute.workflow.temporal.DeliberationPanelActivities;
import com.example.dispute.workflow.temporal.DeliberationPanelWorkflow;
import com.example.dispute.workflow.temporal.DeliberationPanelWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「DeliberationPanelWorkflowTest」。
// 类型职责：集中验证评议Panel的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「tearDown」、「runsOnlyRiskSelectedCriticsAgainstOneFrozenInput」、「blockerAndMinorityMajorObjectionCannotBeAveragedAway」、「criticScoreBelowThresholdRequiresRevision」、「failedOrTimedOutCriticRequiresManualReview」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class DeliberationPanelWorkflowTest {

    private static final String TASK_QUEUE = "final-panel-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.setUp()」。
    // 具体功能：「DeliberationPanelWorkflowTest.setUp()」：在每个测试场景运行前创建「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」、「TestWorkflowEnvironment.newInstance」、「environment.newWorker」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「DeliberationPanelWorkflowTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「DeliberationPanelWorkflowTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DeliberationPanelWorkflowTest.setUp()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                DeliberationPanelWorkflowImpl.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.tearDown()」。
    // 具体功能：「DeliberationPanelWorkflowTest.tearDown()」：作为测试辅助方法为“核对完整业务行为（场景方法「tearDown」）”组装或读取「environment.close」，供本测试类的场景方法复用。
    // 上游调用：「DeliberationPanelWorkflowTest.tearDown()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「DeliberationPanelWorkflowTest.tearDown()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DeliberationPanelWorkflowTest.tearDown()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterEach
    void tearDown() {
        environment.close();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.runsOnlyRiskSelectedCriticsAgainstOneFrozenInput()」。
    // 具体功能：「DeliberationPanelWorkflowTest.runsOnlyRiskSelectedCriticsAgainstOneFrozenInput()」：复现“核对完整业务行为（场景方法「runsOnlyRiskSelectedCriticsAgainstOneFrozenInput」）”场景：驱动 「result.panelResult」、「result.manualRequired」、「workflow」、「command」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_panel_selected」、「EVIDENCE_CRITIC」、「RISK_CRITIC」、「FROZEN_fingerprint」。
    // 上游调用：「DeliberationPanelWorkflowTest.runsOnlyRiskSelectedCriticsAgainstOneFrozenInput()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DeliberationPanelWorkflowTest.runsOnlyRiskSelectedCriticsAgainstOneFrozenInput()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DeliberationPanelWorkflowTest.runsOnlyRiskSelectedCriticsAgainstOneFrozenInput()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_panel_selected」、「EVIDENCE_CRITIC」、「RISK_CRITIC」、「FROZEN_fingerprint」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void runsOnlyRiskSelectedCriticsAgainstOneFrozenInput() {
        DeliberationPanelResult result =
                workflow("WORKFLOW_panel_selected")
                        .run(
                                command(
                                        List.of(
                                                "EVIDENCE_CRITIC",
                                                "RISK_CRITIC")));

        assertThat(activities.critics)
                .containsExactlyInAnyOrder(
                        "EVIDENCE_CRITIC", "RISK_CRITIC");
        assertThat(activities.fingerprints)
                .containsOnly("FROZEN_fingerprint");
        assertThat(result.panelResult()).isEqualTo("NO_MAJOR_OBJECTION");
        assertThat(result.manualRequired()).isFalse();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.blockerAndMinorityMajorObjectionCannotBeAveragedAway()」。
    // 具体功能：「DeliberationPanelWorkflowTest.blockerAndMinorityMajorObjectionCannotBeAveragedAway()」：复现“核对完整业务行为（场景方法「blockerAndMinorityMajorObjectionCannotBeAveragedAway」）”场景：驱动 「result.panelResult」、「result.revisionRequired」、「result.majorObjections」、「workflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_CRITIC」、「WORKFLOW_panel_blocker」、「RULE_CRITIC」、「RISK_CRITIC」。
    // 上游调用：「DeliberationPanelWorkflowTest.blockerAndMinorityMajorObjectionCannotBeAveragedAway()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DeliberationPanelWorkflowTest.blockerAndMinorityMajorObjectionCannotBeAveragedAway()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DeliberationPanelWorkflowTest.blockerAndMinorityMajorObjectionCannotBeAveragedAway()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「EVIDENCE_CRITIC」、「WORKFLOW_panel_blocker」、「RULE_CRITIC」、「RISK_CRITIC」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void blockerAndMinorityMajorObjectionCannotBeAveragedAway() {
        activities.blockingCritic = "EVIDENCE_CRITIC";

        DeliberationPanelResult result =
                workflow("WORKFLOW_panel_blocker")
                        .run(command(
                                List.of(
                                        "EVIDENCE_CRITIC",
                                        "RULE_CRITIC",
                                        "RISK_CRITIC",
                                        "REMEDY_CRITIC",
                                        "FAIRNESS_CRITIC")));

        assertThat(result.panelResult()).isEqualTo("REVISION_REQUIRED");
        assertThat(result.revisionRequired()).isTrue();
        assertThat(result.majorObjections())
                .containsExactly("UNRESOLVED_EVIDENCE_CONFLICT");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision()」。
    // 具体功能：「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision()」：复现“核对完整业务行为（场景方法「criticScoreBelowThresholdRequiresRevision」）”场景：驱动 「result.panelResult」、「result.revisionRequired」、「result.majorObjections」、「workflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「RISK_CRITIC」、「WORKFLOW_panel_low_score」、「CASE_panel」、「WORKFLOW_panel」。
    // 上游调用：「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「RISK_CRITIC」、「WORKFLOW_panel_low_score」、「CASE_panel」、「WORKFLOW_panel」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void criticScoreBelowThresholdRequiresRevision() {
        activities.lowScoreCritic = "RISK_CRITIC";

        DeliberationPanelResult result =
                workflow("WORKFLOW_panel_low_score")
                        .run(
                                new DeliberationPanelCommand(
                                        "CASE_panel",
                                        "WORKFLOW_panel",
                                        "DRAFT_panel",
                                        3,
                                        List.of("RISK_CRITIC"),
                                        List.of("HIGH_VALUE_CASE"),
                                        80,
                                        2));

        assertThat(result.panelResult()).isEqualTo("REVISION_REQUIRED");
        assertThat(result.revisionRequired()).isTrue();
        assertThat(result.majorObjections())
                .containsExactly("RISK_CRITIC_SCORE_BELOW_THRESHOLD_79");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.failedOrTimedOutCriticRequiresManualReview()」。
    // 具体功能：「DeliberationPanelWorkflowTest.failedOrTimedOutCriticRequiresManualReview()」：复现“核对完整业务行为（场景方法「failedOrTimedOutCriticRequiresManualReview」）”场景：驱动 「result.panelResult」、「result.manualRequired」、「result.unavailableCritics」、「workflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「RULE_CRITIC」、「WORKFLOW_panel_timeout」、「EVIDENCE_CRITIC」、「MANUAL_REVIEW_REQUIRED」。
    // 上游调用：「DeliberationPanelWorkflowTest.failedOrTimedOutCriticRequiresManualReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DeliberationPanelWorkflowTest.failedOrTimedOutCriticRequiresManualReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DeliberationPanelWorkflowTest.failedOrTimedOutCriticRequiresManualReview()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「RULE_CRITIC」、「WORKFLOW_panel_timeout」、「EVIDENCE_CRITIC」、「MANUAL_REVIEW_REQUIRED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void failedOrTimedOutCriticRequiresManualReview() {
        activities.timedOutCritic = "RULE_CRITIC";

        DeliberationPanelResult result =
                workflow("WORKFLOW_panel_timeout")
                        .run(
                                command(
                                        List.of(
                                                "EVIDENCE_CRITIC",
                                                "RULE_CRITIC")));

        assertThat(result.panelResult())
                .isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(result.manualRequired()).isTrue();
        assertThat(result.unavailableCritics())
                .containsExactly("RULE_CRITIC");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.workflow(String)」。
    // 具体功能：「DeliberationPanelWorkflowTest.workflow(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「workflow」）”组装或读取「client.newWorkflowStub」、「WorkflowOptions.newBuilder」、「setTaskQueue」、「WorkflowOptions.newBuilder().setWorkflowId」，供本测试类的场景方法复用。
    // 上游调用：「DeliberationPanelWorkflowTest.workflow(String)」由本测试类中的 「DeliberationPanelWorkflowTest.runsOnlyRiskSelectedCriticsAgainstOneFrozenInput」、「DeliberationPanelWorkflowTest.blockerAndMinorityMajorObjectionCannotBeAveragedAway」、「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision」、「DeliberationPanelWorkflowTest.failedOrTimedOutCriticRequiresManualReview」 调用。
    // 下游影响：「DeliberationPanelWorkflowTest.workflow(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DeliberationPanelWorkflowTest.workflow(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private DeliberationPanelWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                DeliberationPanelWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.command(List)」。
    // 具体功能：「DeliberationPanelWorkflowTest.command(List)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「DeliberationPanelCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DeliberationPanelWorkflowTest.command(List)」由本测试类中的 「DeliberationPanelWorkflowTest.runsOnlyRiskSelectedCriticsAgainstOneFrozenInput」、「DeliberationPanelWorkflowTest.blockerAndMinorityMajorObjectionCannotBeAveragedAway」、「DeliberationPanelWorkflowTest.failedOrTimedOutCriticRequiresManualReview」 调用。
    // 下游影响：「DeliberationPanelWorkflowTest.command(List)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DeliberationPanelWorkflowTest.command(List)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_panel」、「WORKFLOW_panel」、「DRAFT_panel」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static DeliberationPanelCommand command(
            List<String> selectedCritics) {
        return new DeliberationPanelCommand(
                "CASE_panel",
                "WORKFLOW_panel",
                "DRAFT_panel",
                3,
                selectedCritics);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「RecordingActivities」。
    // 类型职责：定义Recording可由 Temporal 重试的 Activity 契约；本类型显式提供 「freeze」、「runCritic」、「persistReport」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class RecordingActivities
            implements DeliberationPanelActivities {
        private final List<String> critics = new CopyOnWriteArrayList<>();
        private final List<String> fingerprints =
                new CopyOnWriteArrayList<>();
        private volatile String blockingCritic;
        private volatile String timedOutCritic;
        private volatile String lowScoreCritic;

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.RecordingActivities.freeze(DeliberationPanelCommand)」。
        // 具体功能：「DeliberationPanelWorkflowTest.RecordingActivities.freeze(DeliberationPanelCommand)」：作为「RecordingActivities」测试替身实现「freeze」：返回预设值 「newFrozenDeliberationSnapshot(command.caseId(...」；记录 「command.caseId」、「command.dossierVersion」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DeliberationPanelWorkflowTest.RecordingActivities.freeze(DeliberationPanelCommand)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DeliberationPanelWorkflowTest.RecordingActivities.freeze(DeliberationPanelCommand)」下游仅修改测试内存状态或返回桩值：返回预设值 「newFrozenDeliberationSnapshot(command.caseId(...」；记录 「command.caseId」、「command.dossierVersion」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DeliberationPanelWorkflowTest.RecordingActivities.freeze(DeliberationPanelCommand)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「RULE_2026_01」、「FROZEN_fingerprint」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public FrozenDeliberationSnapshot freeze(
                DeliberationPanelCommand command) {
            return new FrozenDeliberationSnapshot(
                    command.caseId(),
                    7,
                    command.dossierVersion(),
                    2,
                    "RULE_2026_01",
                    1,
                    "FROZEN_fingerprint");
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.RecordingActivities.runCritic(FrozenDeliberationSnapshot,String)」。
        // 具体功能：「DeliberationPanelWorkflowTest.RecordingActivities.runCritic(FrozenDeliberationSnapshot,String)」：作为「RecordingActivities」测试替身实现「runCritic」：返回预设值 「newCriticActivityResult(critic,"TIMED_OUT","B...」、「newCriticActivityResult(critic,"COMPLETED","B...」、「newCriticActivityResult(critic,"COMPLETED","N...」；记录 「snapshot.fingerprint」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DeliberationPanelWorkflowTest.RecordingActivities.runCritic(FrozenDeliberationSnapshot,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DeliberationPanelWorkflowTest.RecordingActivities.runCritic(FrozenDeliberationSnapshot,String)」下游仅修改测试内存状态或返回桩值：返回预设值 「newCriticActivityResult(critic,"TIMED_OUT","B...」、「newCriticActivityResult(critic,"COMPLETED","B...」、「newCriticActivityResult(critic,"COMPLETED","N...」；记录 「snapshot.fingerprint」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DeliberationPanelWorkflowTest.RecordingActivities.runCritic(FrozenDeliberationSnapshot,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「TIMED_OUT」、「BLOCKER」、「CRITIC_TIMEOUT」、「COMPLETED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public CriticActivityResult runCritic(
                FrozenDeliberationSnapshot snapshot,
                String critic) {
            critics.add(critic);
            fingerprints.add(snapshot.fingerprint());
            if (critic.equals(timedOutCritic)) {
                return new CriticActivityResult(
                        critic,
                        "TIMED_OUT",
                        "BLOCKER",
                        List.of("CRITIC_TIMEOUT"),
                        snapshot.fingerprint());
            }
            if (critic.equals(blockingCritic)) {
                return new CriticActivityResult(
                        critic,
                        "COMPLETED",
                        "BLOCKER",
                        List.of("UNRESOLVED_EVIDENCE_CONFLICT"),
                        snapshot.fingerprint());
            }
            if (critic.equals(lowScoreCritic)) {
                return new CriticActivityResult(
                        critic,
                        "COMPLETED",
                        "NONE",
                        List.of(),
                        snapshot.fingerprint(),
                        79);
            }
            return new CriticActivityResult(
                    critic,
                    "COMPLETED",
                    "NONE",
                    List.of(),
                    snapshot.fingerprint());
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPanelWorkflowTest.RecordingActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」。
        // 具体功能：「DeliberationPanelWorkflowTest.RecordingActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」：作为「RecordingActivities」测试替身实现「persistReport」：返回预设值 「"DELIBERATION_test"」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DeliberationPanelWorkflowTest.RecordingActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DeliberationPanelWorkflowTest.RecordingActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」下游仅修改测试内存状态或返回桩值：返回预设值 「"DELIBERATION_test"」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DeliberationPanelWorkflowTest.RecordingActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「DELIBERATION_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public String persistReport(
                DeliberationPanelCommand command,
                FrozenDeliberationSnapshot snapshot,
                List<CriticActivityResult> reports,
                String panelResult) {
            return "DELIBERATION_test";
        }
    }
}

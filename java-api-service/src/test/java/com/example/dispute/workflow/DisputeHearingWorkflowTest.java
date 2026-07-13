/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证争议庭审，覆盖 「runsC1ToC6InOrderAndResumesOnEvidenceSignal」、「evidenceTimerAndBoundedRoundsEndInManualReview」、「invalidStructuredStageInterruptsBeforeLaterCognition」、「initializesHearingStateBeforeWaitingForStatementRounds」、「threeHourDeadlineAlwaysConvergesThroughC6」、「threeCompletedRoundsForceConvergenceBeforeDeadline」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.HearingStageActivityResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import com.example.dispute.workflow.temporal.DisputeHearingActivities;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「DisputeHearingWorkflowTest」。
// 类型职责：集中验证争议庭审的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「tearDown」、「runsC1ToC6InOrderAndResumesOnEvidenceSignal」、「evidenceTimerAndBoundedRoundsEndInManualReview」、「invalidStructuredStageInterruptsBeforeLaterCognition」、「initializesHearingStateBeforeWaitingForStatementRounds」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class DisputeHearingWorkflowTest {

    private static final String TASK_QUEUE = "final-hearing-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.setUp()」。
    // 具体功能：「DisputeHearingWorkflowTest.setUp()」：在每个测试场景运行前创建「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」、「TestWorkflowEnvironment.newInstance」、「environment.newWorker」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「DisputeHearingWorkflowTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「DisputeHearingWorkflowTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeHearingWorkflowTest.setUp()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                DisputeHearingWorkflowImpl.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.tearDown()」。
    // 具体功能：「DisputeHearingWorkflowTest.tearDown()」：作为测试辅助方法为“核对完整业务行为（场景方法「tearDown」）”组装或读取「environment.close」，供本测试类的场景方法复用。
    // 上游调用：「DisputeHearingWorkflowTest.tearDown()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「DisputeHearingWorkflowTest.tearDown()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeHearingWorkflowTest.tearDown()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterEach
    void tearDown() {
        environment.close();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.runsC1ToC6InOrderAndResumesOnEvidenceSignal()」。
    // 具体功能：「DisputeHearingWorkflowTest.runsC1ToC6InOrderAndResumesOnEvidenceSignal()」：复现“核对完整业务行为（场景方法「runsC1ToC6InOrderAndResumesOnEvidenceSignal」）”场景：驱动 「WorkflowClient.start」、「workflow.submitEvidence」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_hearing_signal」、「CASE_hearing_signal」、「SUBMISSION_2」、「USER」。
    // 上游调用：「DisputeHearingWorkflowTest.runsC1ToC6InOrderAndResumesOnEvidenceSignal()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.runsC1ToC6InOrderAndResumesOnEvidenceSignal()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.runsC1ToC6InOrderAndResumesOnEvidenceSignal()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_hearing_signal」、「CASE_hearing_signal」、「SUBMISSION_2」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void runsC1ToC6InOrderAndResumesOnEvidenceSignal() {
        DisputeHearingWorkflow workflow = workflow("WORKFLOW_hearing_signal");
        WorkflowClient.start(
                workflow::run,
                command(
                        "CASE_hearing_signal",
                        "WORKFLOW_hearing_signal",
                        Duration.ofHours(24),
                        2));
        workflow.submitEvidence(
                new EvidenceSubmissionSignal(
                        "SUBMISSION_2",
                        "USER",
                        List.of("EVIDENCE_2")));

        HearingWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HearingWorkflowResult.class);

        assertThat(activities.stages)
                .containsExactly(
                        "C1_ISSUE_FRAMING",
                        "C2_EVIDENCE_GAP",
                        "C3_EVIDENCE_REQUEST",
                        "C1_ISSUE_FRAMING",
                        "C2_EVIDENCE_GAP",
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION",
                        "C6_DRAFT_GENERATION");
        assertThat(activities.traces).containsExactlyElementsOf(activities.stages);
        assertThat(activities.recordedEvidence).containsExactly("SUBMISSION_2");
        assertThat(result.dossierVersion()).isEqualTo(2);
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
        assertThat(result.evidenceTimedOut()).isFalse();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.evidenceTimerAndBoundedRoundsEndInManualReview()」。
    // 具体功能：「DisputeHearingWorkflowTest.evidenceTimerAndBoundedRoundsEndInManualReview()」：复现“核对完整业务行为（场景方法「evidenceTimerAndBoundedRoundsEndInManualReview」）”场景：驱动 「Duration.ofHours」、「environment.currentTimeMillis」、「result.evidenceTimedOut」、「result.manualRequired」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_hearing_timeout」、「CASE_hearing_timeout」、「C4_EVIDENCE_CROSS_CHECK」、「C5_RULE_APPLICATION」。
    // 上游调用：「DisputeHearingWorkflowTest.evidenceTimerAndBoundedRoundsEndInManualReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.evidenceTimerAndBoundedRoundsEndInManualReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.evidenceTimerAndBoundedRoundsEndInManualReview()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_hearing_timeout」、「CASE_hearing_timeout」、「C4_EVIDENCE_CROSS_CHECK」、「C5_RULE_APPLICATION」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evidenceTimerAndBoundedRoundsEndInManualReview() {
        activities.alwaysRequireEvidence = true;
        long startedAt = environment.currentTimeMillis();

        HearingWorkflowResult result =
                workflow("WORKFLOW_hearing_timeout")
                        .run(
                                command(
                                        "CASE_hearing_timeout",
                                        "WORKFLOW_hearing_timeout",
                                        Duration.ofHours(48),
                                        1));

        assertThat(result.evidenceTimedOut()).isTrue();
        assertThat(result.manualRequired()).isTrue();
        assertThat(environment.currentTimeMillis())
                .isGreaterThanOrEqualTo(
                        startedAt + Duration.ofHours(48).toMillis());
        assertThat(activities.stages)
                .endsWith(
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION",
                        "C6_DRAFT_GENERATION");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.invalidStructuredStageInterruptsBeforeLaterCognition()」。
    // 具体功能：「DisputeHearingWorkflowTest.invalidStructuredStageInterruptsBeforeLaterCognition()」：复现“核对完整业务行为（场景方法「invalidStructuredStageInterruptsBeforeLaterCognition」）”场景：驱动 「Duration.ofHours」、「result.status」、「result.manualRequired」、「workflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「C4_EVIDENCE_CROSS_CHECK」、「WORKFLOW_hearing_invalid」、「CASE_hearing_invalid」、「VALIDATION_INTERRUPTED」。
    // 上游调用：「DisputeHearingWorkflowTest.invalidStructuredStageInterruptsBeforeLaterCognition()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.invalidStructuredStageInterruptsBeforeLaterCognition()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.invalidStructuredStageInterruptsBeforeLaterCognition()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「C4_EVIDENCE_CROSS_CHECK」、「WORKFLOW_hearing_invalid」、「CASE_hearing_invalid」、「VALIDATION_INTERRUPTED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void invalidStructuredStageInterruptsBeforeLaterCognition() {
        activities.invalidStage = "C4_EVIDENCE_CROSS_CHECK";

        assertThatThrownBy(
                        () ->
                                workflow("WORKFLOW_hearing_invalid")
                                        .run(
                                                command(
                                                        "CASE_hearing_invalid",
                                                        "WORKFLOW_hearing_invalid",
                                                        Duration.ofHours(1),
                                                        1)))
                .isInstanceOf(WorkflowFailedException.class);

        assertThat(activities.stages)
                .doesNotContain("C5_RULE_APPLICATION", "C6_DRAFT_GENERATION");
        assertThat(activities.completionCalls).isZero();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.initializesHearingStateBeforeWaitingForStatementRounds()」。
    // 具体功能：「DisputeHearingWorkflowTest.initializesHearingStateBeforeWaitingForStatementRounds()」：复现“核对完整业务行为（场景方法「initializesHearingStateBeforeWaitingForStatementRounds」）”场景：驱动 「WorkflowClient.start」、「workflow.hearingRoundCompleted」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MAX_ROUNDS」、「DRAFT_final」。
    // 上游调用：「DisputeHearingWorkflowTest.initializesHearingStateBeforeWaitingForStatementRounds()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.initializesHearingStateBeforeWaitingForStatementRounds()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.initializesHearingStateBeforeWaitingForStatementRounds()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「MAX_ROUNDS」、「DRAFT_final」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void initializesHearingStateBeforeWaitingForStatementRounds() {
        DisputeHearingWorkflow workflow =
                workflow("WORKFLOW_shared_hearing_initializes_first");
        WorkflowClient.start(
                workflow::run,
                new HearingWorkflowCommand(
                        "CASE_shared_hearing_initializes_first",
                        "WORKFLOW_shared_hearing_initializes_first",
                        1,
                        Duration.ofHours(24),
                        2,
                        Duration.ofHours(3),
                        3));

        environment.sleep(Duration.ofSeconds(1));

        assertThat(activities.initializedCases)
                .containsExactly("CASE_shared_hearing_initializes_first");
        assertThat(activities.stages).isEmpty();

        workflow.hearingRoundCompleted(1, false);
        workflow.hearingRoundCompleted(2, false);
        workflow.hearingRoundCompleted(3, false);

        HearingWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HearingWorkflowResult.class);

        assertThat(result.stopReason()).isEqualTo("MAX_ROUNDS");
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.threeHourDeadlineAlwaysConvergesThroughC6()」。
    // 具体功能：「DisputeHearingWorkflowTest.threeHourDeadlineAlwaysConvergesThroughC6()」：复现“核对完整业务行为（场景方法「threeHourDeadlineAlwaysConvergesThroughC6」）”场景：驱动 「Duration.ofHours」、「environment.currentTimeMillis」、「result.stopReason」、「result.draftId」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_shared_hearing_timeout」、「CASE_shared_hearing_timeout」、「DEADLINE_EXPIRED」、「DRAFT_final」。
    // 上游调用：「DisputeHearingWorkflowTest.threeHourDeadlineAlwaysConvergesThroughC6()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.threeHourDeadlineAlwaysConvergesThroughC6()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.threeHourDeadlineAlwaysConvergesThroughC6()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_shared_hearing_timeout」、「CASE_shared_hearing_timeout」、「DEADLINE_EXPIRED」、「DRAFT_final」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void threeHourDeadlineAlwaysConvergesThroughC6() {
        long startedAt = environment.currentTimeMillis();

        HearingWorkflowResult result =
                workflow("WORKFLOW_shared_hearing_timeout")
                        .run(
                                new HearingWorkflowCommand(
                                        "CASE_shared_hearing_timeout",
                                        "WORKFLOW_shared_hearing_timeout",
                                        1,
                                        Duration.ofHours(24),
                                        2,
                                        Duration.ofHours(3),
                                        3));

        assertThat(result.stopReason()).isEqualTo("DEADLINE_EXPIRED");
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
        assertThat(result.manualRequired()).isTrue();
        assertThat(environment.currentTimeMillis())
                .isGreaterThanOrEqualTo(
                        startedAt + Duration.ofHours(3).toMillis());
        assertThat(activities.stages).contains("C6_DRAFT_GENERATION");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.threeCompletedRoundsForceConvergenceBeforeDeadline()」。
    // 具体功能：「DisputeHearingWorkflowTest.threeCompletedRoundsForceConvergenceBeforeDeadline()」：复现“核对完整业务行为（场景方法「threeCompletedRoundsForceConvergenceBeforeDeadline」）”场景：驱动 「WorkflowClient.start」、「workflow.hearingRoundCompleted」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_shared_hearing_rounds」、「CASE_shared_hearing_rounds」、「MAX_ROUNDS」、「DRAFT_final」。
    // 上游调用：「DisputeHearingWorkflowTest.threeCompletedRoundsForceConvergenceBeforeDeadline()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.threeCompletedRoundsForceConvergenceBeforeDeadline()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.threeCompletedRoundsForceConvergenceBeforeDeadline()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_shared_hearing_rounds」、「CASE_shared_hearing_rounds」、「MAX_ROUNDS」、「DRAFT_final」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void threeCompletedRoundsForceConvergenceBeforeDeadline() {
        long startedAt = environment.currentTimeMillis();
        DisputeHearingWorkflow workflow =
                workflow("WORKFLOW_shared_hearing_rounds");
        WorkflowClient.start(
                workflow::run,
                new HearingWorkflowCommand(
                        "CASE_shared_hearing_rounds",
                        "WORKFLOW_shared_hearing_rounds",
                        1,
                        Duration.ofHours(24),
                        2,
                        Duration.ofHours(3),
                        3));
        workflow.hearingRoundCompleted(1, false);
        workflow.hearingRoundCompleted(2, false);
        workflow.hearingRoundCompleted(3, false);

        HearingWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HearingWorkflowResult.class);

        assertThat(result.stopReason()).isEqualTo("MAX_ROUNDS");
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
        assertThat(activities.rounds)
                .containsExactly(3, 3, 3, 3, 3);
        assertThat(activities.finalConvergences)
                .containsExactly(true, true, true, true, true);
        assertThat(activities.maxHearingRounds)
                .containsExactly(3, 3, 3, 3, 3);
        assertThat(environment.currentTimeMillis())
                .isLessThan(startedAt + Duration.ofHours(3).toMillis());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.factsSufficientSignalDoesNotBypassTheThreeStatementRounds()」。
    // 具体功能：「DisputeHearingWorkflowTest.factsSufficientSignalDoesNotBypassTheThreeStatementRounds()」：复现“核对完整业务行为（场景方法「factsSufficientSignalDoesNotBypassTheThreeStatementRounds」）”场景：驱动 「WorkflowClient.start」、「workflow.hearingRoundCompleted」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MAX_ROUNDS」、「DRAFT_final」。
    // 上游调用：「DisputeHearingWorkflowTest.factsSufficientSignalDoesNotBypassTheThreeStatementRounds()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.factsSufficientSignalDoesNotBypassTheThreeStatementRounds()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.factsSufficientSignalDoesNotBypassTheThreeStatementRounds()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「MAX_ROUNDS」、「DRAFT_final」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void factsSufficientSignalDoesNotBypassTheThreeStatementRounds() {
        DisputeHearingWorkflow workflow =
                workflow("WORKFLOW_shared_hearing_three_rounds_required");
        WorkflowClient.start(
                workflow::run,
                new HearingWorkflowCommand(
                        "CASE_shared_hearing_three_rounds_required",
                        "WORKFLOW_shared_hearing_three_rounds_required",
                        1,
                        Duration.ofHours(24),
                        2,
                        Duration.ofHours(3),
                        3));

        workflow.hearingRoundCompleted(1, true);
        workflow.hearingRoundCompleted(2, false);
        workflow.hearingRoundCompleted(3, false);

        HearingWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HearingWorkflowResult.class);

        assertThat(result.stopReason()).isEqualTo("MAX_ROUNDS");
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
        assertThat(activities.rounds)
                .containsExactly(3, 3, 3, 3, 3);
        assertThat(activities.finalConvergences)
                .containsExactly(true, true, true, true, true);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.finalConvergenceDoesNotRequestMoreEvidenceAfterThreeRounds()」。
    // 具体功能：「DisputeHearingWorkflowTest.finalConvergenceDoesNotRequestMoreEvidenceAfterThreeRounds()」：复现“核对完整业务行为（场景方法「finalConvergenceDoesNotRequestMoreEvidenceAfterThreeRounds」）”场景：驱动 「WorkflowClient.start」、「workflow.hearingRoundCompleted」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MAX_ROUNDS」、「DRAFT_final」、「C3_EVIDENCE_REQUEST」、「C4_EVIDENCE_CROSS_CHECK」。
    // 上游调用：「DisputeHearingWorkflowTest.finalConvergenceDoesNotRequestMoreEvidenceAfterThreeRounds()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.finalConvergenceDoesNotRequestMoreEvidenceAfterThreeRounds()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.finalConvergenceDoesNotRequestMoreEvidenceAfterThreeRounds()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「MAX_ROUNDS」、「DRAFT_final」、「C3_EVIDENCE_REQUEST」、「C4_EVIDENCE_CROSS_CHECK」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceDoesNotRequestMoreEvidenceAfterThreeRounds() {
        activities.alwaysRequireEvidence = true;
        DisputeHearingWorkflow workflow =
                workflow("WORKFLOW_shared_hearing_final_no_supplement");
        WorkflowClient.start(
                workflow::run,
                new HearingWorkflowCommand(
                        "CASE_shared_hearing_final_no_supplement",
                        "WORKFLOW_shared_hearing_final_no_supplement",
                        1,
                        Duration.ofHours(24),
                        2,
                        Duration.ofHours(3),
                        3));

        workflow.hearingRoundCompleted(1, false);
        workflow.hearingRoundCompleted(2, false);
        workflow.hearingRoundCompleted(3, false);

        HearingWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HearingWorkflowResult.class);

        assertThat(result.stopReason()).isEqualTo("MAX_ROUNDS");
        assertThat(result.manualRequired()).isTrue();
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
        assertThat(activities.stages)
                .doesNotContain("C3_EVIDENCE_REQUEST")
                .endsWith(
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION",
                        "C6_DRAFT_GENERATION");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.confirmedSettlementSkipsModelStagesAndCompletesDeterministically()」。
    // 具体功能：「DisputeHearingWorkflowTest.confirmedSettlementSkipsModelStagesAndCompletesDeterministically()」：复现“核对完整业务行为（场景方法「confirmedSettlementSkipsModelStagesAndCompletesDeterministically」）”场景：驱动 「WorkflowClient.start」、「workflow.settlementConfirmed」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_shared_hearing_settlement」、「SETTLEMENT_CONFIRMED」。
    // 上游调用：「DisputeHearingWorkflowTest.confirmedSettlementSkipsModelStagesAndCompletesDeterministically()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeHearingWorkflowTest.confirmedSettlementSkipsModelStagesAndCompletesDeterministically()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeHearingWorkflowTest.confirmedSettlementSkipsModelStagesAndCompletesDeterministically()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_shared_hearing_settlement」、「SETTLEMENT_CONFIRMED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void confirmedSettlementSkipsModelStagesAndCompletesDeterministically() {
        DisputeHearingWorkflow workflow =
                workflow("WORKFLOW_shared_hearing_settlement");
        WorkflowClient.start(
                workflow::run,
                new HearingWorkflowCommand(
                        "CASE_shared_hearing_settlement",
                        "WORKFLOW_shared_hearing_settlement",
                        1,
                        Duration.ofHours(24),
                        2,
                        Duration.ofHours(3),
                        3));

        workflow.settlementConfirmed(1);

        HearingWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HearingWorkflowResult.class);

        assertThat(result.stopReason()).isEqualTo("SETTLEMENT_CONFIRMED");
        assertThat(result.status()).isEqualTo("SETTLEMENT_CONFIRMED");
        assertThat(result.manualRequired()).isFalse();
        assertThat(result.draftId()).isNull();
        assertThat(activities.stages).isEmpty();
        assertThat(activities.traces).isEmpty();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.workflow(String)」。
    // 具体功能：「DisputeHearingWorkflowTest.workflow(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「workflow」）”组装或读取「client.newWorkflowStub」、「WorkflowOptions.newBuilder」、「setTaskQueue」、「WorkflowOptions.newBuilder().setWorkflowId」，供本测试类的场景方法复用。
    // 上游调用：「DisputeHearingWorkflowTest.workflow(String)」由本测试类中的 「DisputeHearingWorkflowTest.runsC1ToC6InOrderAndResumesOnEvidenceSignal」、「DisputeHearingWorkflowTest.evidenceTimerAndBoundedRoundsEndInManualReview」、「DisputeHearingWorkflowTest.invalidStructuredStageInterruptsBeforeLaterCognition」、「DisputeHearingWorkflowTest.initializesHearingStateBeforeWaitingForStatementRounds」 调用。
    // 下游影响：「DisputeHearingWorkflowTest.workflow(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeHearingWorkflowTest.workflow(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private DisputeHearingWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                DisputeHearingWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.command(String,String,Duration,int)」。
    // 具体功能：「DisputeHearingWorkflowTest.command(String,String,Duration,int)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「HearingWorkflowCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DisputeHearingWorkflowTest.command(String,String,Duration,int)」由本测试类中的 「DisputeHearingWorkflowTest.runsC1ToC6InOrderAndResumesOnEvidenceSignal」、「DisputeHearingWorkflowTest.evidenceTimerAndBoundedRoundsEndInManualReview」、「DisputeHearingWorkflowTest.invalidStructuredStageInterruptsBeforeLaterCognition」 调用。
    // 下游影响：「DisputeHearingWorkflowTest.command(String,String,Duration,int)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeHearingWorkflowTest.command(String,String,Duration,int)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static HearingWorkflowCommand command(
            String caseId,
            String workflowId,
            Duration wait,
            int maxRounds) {
        return new HearingWorkflowCommand(
                caseId, workflowId, 1, wait, maxRounds);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「RecordingActivities」。
    // 类型职责：定义Recording可由 Temporal 重试的 Activity 契约；本类型显式提供 「initialize」、「runStage」、「recordEvidence」、「persistStageTrace」、「complete」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class RecordingActivities
            implements DisputeHearingActivities {
        private final List<String> stages = new CopyOnWriteArrayList<>();
        private final List<Integer> rounds = new CopyOnWriteArrayList<>();
        private final List<Boolean> finalConvergences = new CopyOnWriteArrayList<>();
        private final List<Integer> maxHearingRounds = new CopyOnWriteArrayList<>();
        private final List<String> traces = new CopyOnWriteArrayList<>();
        private final List<String> initializedCases = new CopyOnWriteArrayList<>();
        private final List<String> recordedEvidence =
                new CopyOnWriteArrayList<>();
        private volatile boolean alwaysRequireEvidence;
        private volatile String invalidStage;
        private int c2Calls;
        private int completionCalls;

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.RecordingActivities.initialize(HearingWorkflowCommand)」。
        // 具体功能：「DisputeHearingWorkflowTest.RecordingActivities.initialize(HearingWorkflowCommand)」：作为「RecordingActivities」测试替身实现「initialize」：记录 「command.caseId」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DisputeHearingWorkflowTest.RecordingActivities.initialize(HearingWorkflowCommand)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeHearingWorkflowTest.RecordingActivities.initialize(HearingWorkflowCommand)」下游仅修改测试内存状态或返回桩值：记录 「command.caseId」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DisputeHearingWorkflowTest.RecordingActivities.initialize(HearingWorkflowCommand)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void initialize(HearingWorkflowCommand command) {
            initializedCases.add(command.caseId());
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.RecordingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」。
        // 具体功能：「DisputeHearingWorkflowTest.RecordingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」：作为「RecordingActivities」测试替身实现「runStage」：更新测试记录字段 「valid」、「requiresEvidence」、「c2Calls」、「c2Calls」；返回预设值 「newHearingStageActivityResult(stage,valid,req...」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DisputeHearingWorkflowTest.RecordingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeHearingWorkflowTest.RecordingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「valid」、「requiresEvidence」、「c2Calls」、「c2Calls」；返回预设值 「newHearingStageActivityResult(stage,valid,req...」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DisputeHearingWorkflowTest.RecordingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「C2_EVIDENCE_GAP」、「C6_DRAFT_GENERATION」、「DRAFT_final」、「OUTPUT_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public HearingStageActivityResult runStage(
                String caseId,
                String workflowId,
                String stage,
                int round,
                long dossierVersion,
                boolean evidenceTimedOut,
                boolean finalConvergence,
                int maxHearingRounds) {
            stages.add(stage);
            rounds.add(round);
            finalConvergences.add(finalConvergence);
            this.maxHearingRounds.add(maxHearingRounds);
            boolean valid = !stage.equals(invalidStage);
            boolean requiresEvidence = false;
            if ("C2_EVIDENCE_GAP".equals(stage)) {
                c2Calls++;
                requiresEvidence =
                        !evidenceTimedOut
                                && (alwaysRequireEvidence || c2Calls == 1);
            }
            return new HearingStageActivityResult(
                    stage,
                    valid,
                    requiresEvidence,
                    !valid,
                    "C6_DRAFT_GENERATION".equals(stage)
                            ? "DRAFT_final"
                            : null,
                    "OUTPUT_" + stages.size());
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.RecordingActivities.recordEvidence(EvidenceSubmissionSignal)」。
        // 具体功能：「DisputeHearingWorkflowTest.RecordingActivities.recordEvidence(EvidenceSubmissionSignal)」：作为「RecordingActivities」测试替身实现「recordEvidence」：返回预设值 「2」；记录 「signal.submissionId」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DisputeHearingWorkflowTest.RecordingActivities.recordEvidence(EvidenceSubmissionSignal)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeHearingWorkflowTest.RecordingActivities.recordEvidence(EvidenceSubmissionSignal)」下游仅修改测试内存状态或返回桩值：返回预设值 「2」；记录 「signal.submissionId」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DisputeHearingWorkflowTest.RecordingActivities.recordEvidence(EvidenceSubmissionSignal)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public long recordEvidence(EvidenceSubmissionSignal signal) {
            recordedEvidence.add(signal.submissionId());
            return 2;
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.RecordingActivities.persistStageTrace(String,String,String,int,long,String)」。
        // 具体功能：「DisputeHearingWorkflowTest.RecordingActivities.persistStageTrace(String,String,String,int,long,String)」：作为「RecordingActivities」测试替身实现「persistStageTrace」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DisputeHearingWorkflowTest.RecordingActivities.persistStageTrace(String,String,String,int,long,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeHearingWorkflowTest.RecordingActivities.persistStageTrace(String,String,String,int,long,String)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DisputeHearingWorkflowTest.RecordingActivities.persistStageTrace(String,String,String,int,long,String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void persistStageTrace(
                String caseId,
                String workflowId,
                String stage,
                int round,
                long dossierVersion,
                String outputVersion) {
            traces.add(stage);
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DisputeHearingWorkflowTest.RecordingActivities.complete(String,String,String,boolean,boolean,long,String)」。
        // 具体功能：「DisputeHearingWorkflowTest.RecordingActivities.complete(String,String,String,boolean,boolean,long,String)」：作为「RecordingActivities」测试替身实现「complete」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DisputeHearingWorkflowTest.RecordingActivities.complete(String,String,String,boolean,boolean,long,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeHearingWorkflowTest.RecordingActivities.complete(String,String,String,boolean,boolean,long,String)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DisputeHearingWorkflowTest.RecordingActivities.complete(String,String,String,boolean,boolean,long,String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void complete(
                String caseId,
                String workflowId,
                String status,
                boolean manualRequired,
                boolean evidenceTimedOut,
                long dossierVersion,
                String stopReason) {
            completionCalls++;
        }
    }
}

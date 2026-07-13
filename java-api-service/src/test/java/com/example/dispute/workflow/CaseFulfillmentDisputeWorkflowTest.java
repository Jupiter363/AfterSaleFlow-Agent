/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证案件履约争议，覆盖 「pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft」、「reviewerCanResumeWithAvailableEvidenceAndForceManualReview」、「regularAndRuleRoutesGoDirectlyThroughRemedyPlanning」、「reviewerCanRequestEvidenceThenApproveTheResumedWorkflow」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.CaseWorkflowResult;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeActivities;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeWorkflow;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeWorkflowImpl;
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

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「CaseFulfillmentDisputeWorkflowTest」。
// 类型职责：集中验证案件履约争议的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「tearDown」、「pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft」、「reviewerCanResumeWithAvailableEvidenceAndForceManualReview」、「regularAndRuleRoutesGoDirectlyThroughRemedyPlanning」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class CaseFulfillmentDisputeWorkflowTest {

    private static final String TASK_QUEUE = "case-workflow-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.setUp()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.setUp()」：在每个测试场景运行前创建「worker.registerWorkflowImplementationTypes」、「worker.registerActivitiesImplementations」、「TestWorkflowEnvironment.newInstance」、「environment.newWorker」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.setUp()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                CaseFulfillmentDisputeWorkflowImpl.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.tearDown()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.tearDown()」：作为测试辅助方法为“核对完整业务行为（场景方法「tearDown」）”组装或读取「environment.close」，供本测试类的场景方法复用。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.tearDown()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.tearDown()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.tearDown()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterEach
    void tearDown() {
        environment.close();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning()」：复现“核对完整业务行为（场景方法「pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」）”场景：驱动 「WorkflowClient.start」、「workflow.submitPartyEvidence」、「workflow.submitReviewerSignal」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_signal」、「CASE_signal」、「USER」、「SUBMISSION_signal」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_signal」、「CASE_signal」、「USER」、「SUBMISSION_signal」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning() {
        CaseFulfillmentDisputeWorkflow workflow = newWorkflow("WORKFLOW_signal");
        WorkflowClient.start(
                workflow::run,
                input("CASE_signal", "WORKFLOW_signal", Duration.ofHours(24)));

        workflow.submitPartyEvidence(
                new PartyEvidenceSignal(
                        "USER",
                        "SUBMISSION_signal",
                        List.of("EVIDENCE_signal")));
        workflow.submitReviewerSignal(
                new ReviewerWorkflowSignal(
                        "reviewer-1", "APPROVE", "reviewed"));
        CaseWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(CaseWorkflowResult.class);

        assertThat(result.workflowStatus()).isEqualTo("COMPLETED");
        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(result.remedyPlanId()).isEqualTo("REMEDY_test");
        assertThat(result.reviewTaskId()).isEqualTo("REVIEW_test");
        assertThat(result.evidenceTimedOut()).isFalse();
        assertThat(activities.analysisCalls()).isEqualTo(2);
        assertThat(activities.recordedSignals()).containsExactly("SUBMISSION_signal");
        assertThat(activities.executionCalls).isEqualTo(1);
        assertThat(activities.closureCalls).isEqualTo(1);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft()」：复现“核对完整业务行为（场景方法「evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft」）”场景：驱动 「workflow.run」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_timeout」、「CASE_timeout」、「COMPLETED」、「HUMAN_HANDOFF」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_timeout」、「CASE_timeout」、「COMPLETED」、「HUMAN_HANDOFF」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft() {
        activities.alwaysRequireEvidence = true;
        CaseFulfillmentDisputeWorkflow workflow = newWorkflow("WORKFLOW_timeout");
        long startedAt = environment.currentTimeMillis();

        CaseWorkflowResult result =
                workflow.run(
                        input(
                                "CASE_timeout",
                                "WORKFLOW_timeout",
                                Duration.ofHours(48)));

        assertThat(result.workflowStatus()).isEqualTo("COMPLETED");
        assertThat(result.nextStage()).isEqualTo("HUMAN_HANDOFF");
        assertThat(result.evidenceTimedOut()).isTrue();
        assertThat(result.manualRequired()).isTrue();
        assertThat(environment.currentTimeMillis())
                .isGreaterThanOrEqualTo(
                        startedAt + Duration.ofHours(48).toMillis());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.reviewerCanResumeWithAvailableEvidenceAndForceManualReview()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.reviewerCanResumeWithAvailableEvidenceAndForceManualReview()」：复现“核对完整业务行为（场景方法「reviewerCanResumeWithAvailableEvidenceAndForceManualReview」）”场景：驱动 「WorkflowClient.start」、「workflow.submitReviewerSignal」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_reviewer」、「CASE_reviewer」、「reviewer-1」、「ESCALATE_MANUAL」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.reviewerCanResumeWithAvailableEvidenceAndForceManualReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.reviewerCanResumeWithAvailableEvidenceAndForceManualReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.reviewerCanResumeWithAvailableEvidenceAndForceManualReview()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_reviewer」、「CASE_reviewer」、「reviewer-1」、「ESCALATE_MANUAL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewerCanResumeWithAvailableEvidenceAndForceManualReview() {
        CaseFulfillmentDisputeWorkflow workflow =
                newWorkflow("WORKFLOW_reviewer");
        WorkflowClient.start(
                workflow::run,
                input(
                        "CASE_reviewer",
                        "WORKFLOW_reviewer",
                        Duration.ofHours(24)));

        workflow.submitReviewerSignal(
                new ReviewerWorkflowSignal(
                        "reviewer-1",
                        "ESCALATE_MANUAL",
                        "conflicting delivery evidence"));
        CaseWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(CaseWorkflowResult.class);

        assertThat(result.workflowStatus()).isEqualTo("COMPLETED");
        assertThat(result.manualRequired()).isTrue();
        assertThat(activities.reviewerSignals).isEqualTo(1);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning()」：复现“核对完整业务行为（场景方法「regularAndRuleRoutesGoDirectlyThroughRemedyPlanning」）”场景：驱动 「WorkflowClient.start」、「workflow.submitReviewerSignal」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_」、「CASE_」、「reviewer-1」、「APPROVE」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_」、「CASE_」、「reviewer-1」、「APPROVE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void regularAndRuleRoutesGoDirectlyThroughRemedyPlanning() {
        for (RouteType route :
                List.of(
                        RouteType.TRANSFERRED,
                        RouteType.SIMPLE_HEARING)) {
            String suffix = route.name().toLowerCase();
            CaseFulfillmentDisputeWorkflow workflow =
                    newWorkflow("WORKFLOW_" + suffix);
            WorkflowClient.start(
                    workflow::run,
                    new CaseWorkflowInput(
                            "CASE_" + suffix,
                            "WORKFLOW_" + suffix,
                            route,
                            Duration.ofHours(24),
                            2));
            workflow.submitReviewerSignal(
                    new ReviewerWorkflowSignal(
                            "reviewer-1", "APPROVE", "reviewed"));
            CaseWorkflowResult result =
                    io.temporal.client.WorkflowStub.fromTyped(workflow)
                            .getResult(CaseWorkflowResult.class);

            assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
            assertThat(result.remedyPlanId()).isEqualTo("REMEDY_test");
        }
        assertThat(activities.remedyCalls).isEqualTo(2);
        assertThat(activities.analysisCalls()).isZero();
        assertThat(activities.executionCalls).isEqualTo(2);
        assertThat(activities.closureCalls).isEqualTo(2);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow()」：复现“核对完整业务行为（场景方法「reviewerCanRequestEvidenceThenApproveTheResumedWorkflow」）”场景：驱动 「WorkflowClient.start」、「workflow.submitReviewerSignal」、「workflow.submitPartyEvidence」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「WORKFLOW_review_evidence」、「CASE_review_evidence」、「reviewer-1」、「REQUEST_MORE_EVIDENCE」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WORKFLOW_review_evidence」、「CASE_review_evidence」、「reviewer-1」、「REQUEST_MORE_EVIDENCE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewerCanRequestEvidenceThenApproveTheResumedWorkflow() {
        CaseFulfillmentDisputeWorkflow workflow =
                newWorkflow("WORKFLOW_review_evidence");
        WorkflowClient.start(
                workflow::run,
                new CaseWorkflowInput(
                        "CASE_review_evidence",
                        "WORKFLOW_review_evidence",
                        RouteType.SIMPLE_HEARING,
                        Duration.ofHours(24),
                        2));
        workflow.submitReviewerSignal(
                new ReviewerWorkflowSignal(
                        "reviewer-1",
                        "REQUEST_MORE_EVIDENCE",
                        "need delivery photo"));
        workflow.submitPartyEvidence(
                new PartyEvidenceSignal(
                        "MERCHANT", "SUBMISSION_review", List.of("EVIDENCE_review")));
        workflow.submitReviewerSignal(
                new ReviewerWorkflowSignal(
                        "reviewer-1", "APPROVE", "evidence verified"));

        CaseWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(CaseWorkflowResult.class);

        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(activities.reviewCalls).isEqualTo(2);
        assertThat(activities.recordedSignals()).contains("SUBMISSION_review");
        assertThat(activities.executionCalls).isEqualTo(1);
        assertThat(activities.closureCalls).isEqualTo(1);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.newWorkflow(String)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.newWorkflow(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「newWorkflow」）”组装或读取「client.newWorkflowStub」、「WorkflowOptions.newBuilder」、「setTaskQueue」、「WorkflowOptions.newBuilder().setWorkflowId」，供本测试类的场景方法复用。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.newWorkflow(String)」由本测试类中的 「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft」、「CaseFulfillmentDisputeWorkflowTest.reviewerCanResumeWithAvailableEvidenceAndForceManualReview」、「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning」 调用。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.newWorkflow(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.newWorkflow(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private CaseFulfillmentDisputeWorkflow newWorkflow(String workflowId) {
        return client.newWorkflowStub(
                CaseFulfillmentDisputeWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.input(String,String,Duration)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowTest.input(String,String,Duration)」：作为测试辅助方法为“核对完整业务行为（场景方法「input」）”组装或读取「CaseWorkflowInput」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「CaseFulfillmentDisputeWorkflowTest.input(String,String,Duration)」由本测试类中的 「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft」、「CaseFulfillmentDisputeWorkflowTest.reviewerCanResumeWithAvailableEvidenceAndForceManualReview」 调用。
    // 下游影响：「CaseFulfillmentDisputeWorkflowTest.input(String,String,Duration)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseFulfillmentDisputeWorkflowTest.input(String,String,Duration)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseWorkflowInput input(
            String caseId, String workflowId, Duration timeout) {
        return new CaseWorkflowInput(
                caseId,
                workflowId,
                RouteType.FULL_HEARING,
                timeout,
                2);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「RecordingActivities」。
    // 类型职责：定义Recording可由 Temporal 重试的 Activity 契约；本类型显式提供 「initializeHearing」、「analyzeHearing」、「recordPartyEvidence」、「recordReviewerSignal」、「completeHearing」、「planRemedy」。
    // 协作关系：主要由 「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow」 使用。
    // 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class RecordingActivities
            implements CaseFulfillmentDisputeActivities {

        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.concurrent.CopyOnWriteArrayList<String> signals =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean alwaysRequireEvidence;
        private volatile int reviewerSignals;
        private volatile int remedyCalls;
        private volatile int reviewCalls;
        private volatile int executionCalls;
        private volatile int closureCalls;

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.initializeHearing(CaseWorkflowInput)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.initializeHearing(CaseWorkflowInput)」：作为「RecordingActivities」测试替身实现「initializeHearing」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.initializeHearing(CaseWorkflowInput)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.initializeHearing(CaseWorkflowInput)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.initializeHearing(CaseWorkflowInput)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void initializeHearing(CaseWorkflowInput input) {}

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analyzeHearing(HearingAnalysisActivityCommand)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analyzeHearing(HearingAnalysisActivityCommand)」：作为「RecordingActivities」测试替身实现「analyzeHearing」：更新测试记录字段 「call」；返回预设值 「newHearingAnalysisActivityResult(true,false,n...」、「newHearingAnalysisActivityResult(false,comman...」；记录 「command.evidenceTimedOut」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analyzeHearing(HearingAnalysisActivityCommand)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analyzeHearing(HearingAnalysisActivityCommand)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「call」；返回预设值 「newHearingAnalysisActivityResult(true,false,n...」、「newHearingAnalysisActivityResult(false,comman...」；记录 「command.evidenceTimedOut」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analyzeHearing(HearingAnalysisActivityCommand)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「WAITING_EVIDENCE」、「DRAFT_test」、「COMPLETED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public HearingAnalysisActivityResult analyzeHearing(
                HearingAnalysisActivityCommand command) {
            int call = calls.incrementAndGet();
            if (!command.evidenceTimedOut()
                    && (alwaysRequireEvidence || call == 1)) {
                return new HearingAnalysisActivityResult(
                        true, false, null, "WAITING_EVIDENCE");
            }
            return new HearingAnalysisActivityResult(
                    false,
                    command.evidenceTimedOut(),
                    "DRAFT_test",
                    "COMPLETED");
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordPartyEvidence(PartyEvidenceSignal)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordPartyEvidence(PartyEvidenceSignal)」：作为「RecordingActivities」测试替身实现「recordPartyEvidence」：记录 「signal.submissionId」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordPartyEvidence(PartyEvidenceSignal)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordPartyEvidence(PartyEvidenceSignal)」下游仅修改测试内存状态或返回桩值：记录 「signal.submissionId」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordPartyEvidence(PartyEvidenceSignal)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void recordPartyEvidence(PartyEvidenceSignal signal) {
            signals.add(signal.submissionId());
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordReviewerSignal(ReviewerWorkflowSignal)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordReviewerSignal(ReviewerWorkflowSignal)」：作为「RecordingActivities」测试替身实现「recordReviewerSignal」：更新测试记录字段 「reviewerSignals」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordReviewerSignal(ReviewerWorkflowSignal)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordReviewerSignal(ReviewerWorkflowSignal)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「reviewerSignals」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordReviewerSignal(ReviewerWorkflowSignal)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void recordReviewerSignal(
                ReviewerWorkflowSignal signal) {
            reviewerSignals++;
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.completeHearing(String,String,boolean,boolean)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.completeHearing(String,String,boolean,boolean)」：作为「RecordingActivities」测试替身实现「completeHearing」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.completeHearing(String,String,boolean,boolean)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.completeHearing(String,String,boolean,boolean)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.completeHearing(String,String,boolean,boolean)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void completeHearing(
                String caseId,
                String workflowId,
                boolean manualRequired,
                boolean evidenceTimedOut) {}

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.planRemedy(String,String)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.planRemedy(String,String)」：作为「RecordingActivities」测试替身实现「planRemedy」：更新测试记录字段 「remedyCalls」；返回预设值 「"REMEDY_test"」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.planRemedy(String,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.planRemedy(String,String)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「remedyCalls」；返回预设值 「"REMEDY_test"」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.planRemedy(String,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「REMEDY_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public String planRemedy(String caseId, String workflowId) {
            remedyCalls++;
            return "REMEDY_test";
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.createReviewTask(String,String)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.createReviewTask(String,String)」：作为「RecordingActivities」测试替身实现「createReviewTask」：更新测试记录字段 「reviewCalls」；返回预设值 「"REVIEW_test"」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.createReviewTask(String,String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.createReviewTask(String,String)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「reviewCalls」；返回预设值 「"REVIEW_test"」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.createReviewTask(String,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「REVIEW_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public String createReviewTask(String caseId, String remedyPlanId) {
            reviewCalls++;
            return "REVIEW_test";
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.executeApprovedPlan(String)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.executeApprovedPlan(String)」：作为「RecordingActivities」测试替身实现「executeApprovedPlan」：更新测试记录字段 「executionCalls」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.executeApprovedPlan(String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.executeApprovedPlan(String)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「executionCalls」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.executeApprovedPlan(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void executeApprovedPlan(String caseId) {
            executionCalls++;
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.closeCaseAndEvaluate(String)」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.closeCaseAndEvaluate(String)」：作为「RecordingActivities」测试替身实现「closeCaseAndEvaluate」：更新测试记录字段 「closureCalls」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.closeCaseAndEvaluate(String)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.closeCaseAndEvaluate(String)」下游仅修改测试内存状态或返回桩值：更新测试记录字段 「closureCalls」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.closeCaseAndEvaluate(String)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void closeCaseAndEvaluate(String caseId) {
            closureCalls++;
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analysisCalls()」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analysisCalls()」：作为测试辅助方法为“核对完整业务行为（场景方法「analysisCalls」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analysisCalls()」由本测试类中的 「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning」 调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analysisCalls()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.analysisCalls()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        int analysisCalls() {
            return calls.get();
        }

        // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordedSignals()」。
        // 具体功能：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordedSignals()」：作为测试辅助方法为“核对完整业务行为（场景方法「recordedSignals」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
        // 上游调用：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordedSignals()」由本测试类中的 「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow」 调用。
        // 下游影响：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordedSignals()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「CaseFulfillmentDisputeWorkflowTest.RecordingActivities.recordedSignals()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        List<String> recordedSignals() {
            return List.copyOf(signals);
        }
    }
}

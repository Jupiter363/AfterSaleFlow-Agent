/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：以可重放的 Temporal 代码实现旧版案件全生命周期持久化编排的等待、Signal 与阶段推进。
 * 业务链路：核心入口/契约为 「run」、「submitPartyEvidence」、「submitReviewerSignal」、「queryState」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.CaseWorkflowResult;
import com.example.dispute.workflow.domain.CaseWorkflowSnapshot;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「CaseFulfillmentDisputeWorkflowImpl」。
// 类型职责：以可重放的 Temporal 代码实现旧版案件全生命周期持久化编排的等待、Signal 与阶段推进；本类型显式提供 「run」、「drainSignals」、「awaitHumanReview」、「hasFinalReviewDecision」、「removeFinalReviewDecision」、「result」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class CaseFulfillmentDisputeWorkflowImpl
        implements CaseFulfillmentDisputeWorkflow {

    private final CaseFulfillmentDisputeActivities activities =
            Workflow.newActivityStub(
                    CaseFulfillmentDisputeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setInitialInterval(Duration.ofSeconds(1))
                                            .setMaximumInterval(Duration.ofSeconds(15))
                                            .setBackoffCoefficient(2)
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    private final Deque<PartyEvidenceSignal> partySignals = new ArrayDeque<>();
    private final Deque<ReviewerWorkflowSignal> reviewerSignals =
            new ArrayDeque<>();
    private String status = "PENDING";
    private int roundNo;
    private boolean waitingForEvidence;
    private boolean evidenceTimedOut;
    private boolean manualRequired;

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.run(CaseWorkflowInput)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.run(CaseWorkflowInput)」：兼容旧链路：非 FULL_HEARING 直接进入补救；完整听证循环调用分析 Activity，按补证 Signal、超时和最大轮次收敛草案，Activity 失败则提高 manualRequired 后继续进入人审，最终返回「CaseWorkflowResult」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.run(CaseWorkflowInput)」由使用「CaseFulfillmentDisputeWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.run(CaseWorkflowInput)」向下依次触达 「activities.planRemedy」、「activities.initializeHearing」、「activities.analyzeHearing」、「activities.completeHearing」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.run(CaseWorkflowInput)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public CaseWorkflowResult run(CaseWorkflowInput input) {
        status = "RUNNING";
        if (input.routeType() != RouteType.FULL_HEARING) {
            String planId =
                    activities.planRemedy(input.caseId(), input.workflowId());
            return awaitHumanReview(input, null, planId);
        }

        activities.initializeHearing(input);
        boolean evidenceReceived = false;
        while (true) {
            HearingAnalysisActivityResult analysis;
            try {
                analysis =
                        activities.analyzeHearing(
                                new HearingAnalysisActivityCommand(
                                        input.caseId(),
                                        input.workflowId(),
                                        roundNo,
                                        evidenceTimedOut,
                                        evidenceReceived));
            } catch (ActivityFailure failure) {
                manualRequired = true;
                activities.completeHearing(
                        input.caseId(), input.workflowId(), true, evidenceTimedOut);
                String planId =
                        activities.planRemedy(input.caseId(), input.workflowId());
                return awaitHumanReview(input, null, planId);
            }
            manualRequired = manualRequired || analysis.manualRequired();
            if (!analysis.requiresAdditionalEvidence()
                    || evidenceTimedOut
                    || roundNo >= input.maxEvidenceRounds()) {
                if (roundNo >= input.maxEvidenceRounds()
                        && analysis.requiresAdditionalEvidence()) {
                    manualRequired = true;
                }
                activities.completeHearing(
                        input.caseId(),
                        input.workflowId(),
                        manualRequired,
                        evidenceTimedOut);
                String planId =
                        activities.planRemedy(input.caseId(), input.workflowId());
                return awaitHumanReview(input, analysis.draftId(), planId);
            }

            status = "WAITING_EVIDENCE";
            waitingForEvidence = true;
            boolean signaled =
                    Workflow.await(
                            input.evidenceWaitTimeout(),
                            () -> !partySignals.isEmpty() || !reviewerSignals.isEmpty());
            waitingForEvidence = false;
            if (!signaled) {
                evidenceTimedOut = true;
                manualRequired = true;
                status = "RUNNING";
                continue;
            }

            evidenceReceived = drainSignals();
            roundNo += 1;
            status = "RUNNING";
        }
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.drainSignals()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.drainSignals()」：按 FIFO 持久化当事方补证与“继续现有证据/升级人工”控制信号，其他终审决定暂存回队列，避免听证等待阶段提前消费属于 ReviewGate 的决定，最终返回「boolean」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.drainSignals()」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.run」、「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.drainSignals()」向下依次触达 「activities.recordPartyEvidence」、「activities.recordReviewerSignal」、「partySignals.removeFirst」、「reviewerSignals.removeFirst」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.drainSignals()」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private boolean drainSignals() {
        boolean evidenceReceived = false;
        while (!partySignals.isEmpty()) {
            PartyEvidenceSignal signal = partySignals.removeFirst();
            activities.recordPartyEvidence(signal);
            evidenceReceived = true;
        }
        Deque<ReviewerWorkflowSignal> reviewDecisions = new ArrayDeque<>();
        while (!reviewerSignals.isEmpty()) {
            ReviewerWorkflowSignal signal = reviewerSignals.removeFirst();
            if (!"ESCALATE_MANUAL".equals(signal.decision())
                    && !"CONTINUE_WITH_AVAILABLE_EVIDENCE".equals(
                            signal.decision())) {
                reviewDecisions.addLast(signal);
                continue;
            }
            activities.recordReviewerSignal(signal);
            if ("ESCALATE_MANUAL".equals(signal.decision())) {
                manualRequired = true;
                evidenceReceived = true;
            }
            if ("CONTINUE_WITH_AVAILABLE_EVIDENCE".equals(signal.decision())) {
                evidenceReceived = true;
            }
        }
        reviewerSignals.addAll(reviewDecisions);
        return evidenceReceived;
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview(CaseWorkflowInput,String,String)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview(CaseWorkflowInput,String,String)」：创建审核任务并最多等待七天；补证决定会回到举证等待，批准决定才进入执行与结案，超时、拒绝或升级均返回 HUMAN_HANDOFF 而不会自动执行，最终返回「CaseWorkflowResult」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview(CaseWorkflowInput,String,String)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview(CaseWorkflowInput,String,String)」向下依次触达 「activities.createReviewTask」、「Workflow.await」、「activities.recordReviewerSignal」、「activities.executeApprovedPlan」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview(CaseWorkflowInput,String,String)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private CaseWorkflowResult awaitHumanReview(
            CaseWorkflowInput input, String draftId, String planId) {
        while (true) {
            String taskId = activities.createReviewTask(input.caseId(), planId);
            status = "WAITING_HUMAN_REVIEW";
            boolean reviewed =
                    Workflow.await(
                            Duration.ofDays(7),
                            () -> hasFinalReviewDecision());
            if (!reviewed) {
                manualRequired = true;
                status = "COMPLETED";
                return result(input, draftId, planId, taskId, "HUMAN_HANDOFF");
            }
            ReviewerWorkflowSignal decision = removeFinalReviewDecision();
            activities.recordReviewerSignal(decision);
            if ("REQUEST_MORE_EVIDENCE".equals(decision.decision())) {
                status = "WAITING_EVIDENCE";
                boolean supplied =
                        Workflow.await(
                                input.evidenceWaitTimeout(),
                                () -> !partySignals.isEmpty());
                if (supplied) {
                    drainSignals();
                } else {
                    evidenceTimedOut = true;
                    manualRequired = true;
                }
                status = "RUNNING";
                continue;
            }
            if ("APPROVE".equals(decision.decision())
                    || "MODIFY_AND_APPROVE".equals(decision.decision())) {
                status = "EXECUTING";
                activities.executeApprovedPlan(input.caseId());
                status = "CLOSING";
                activities.closeCaseAndEvaluate(input.caseId());
                status = "COMPLETED";
                return result(
                        input,
                        draftId,
                        planId,
                        taskId,
                        "EVALUATION_COMPLETE");
            }
            status = "COMPLETED";
            manualRequired = true;
            return result(input, draftId, planId, taskId, "HUMAN_HANDOFF");
        }
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.hasFinalReviewDecision()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.hasFinalReviewDecision()」：只判断队列中是否存在终审决定，排除 CONTINUE_WITH_AVAILABLE_EVIDENCE 和 ESCALATE_MANUAL 两类听证控制信号，最终返回「boolean」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.hasFinalReviewDecision()」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.hasFinalReviewDecision()」向下依次触达 「signal.decision」、「reviewerSignals.stream().anyMatch」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.hasFinalReviewDecision()」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private boolean hasFinalReviewDecision() {
        return reviewerSignals.stream()
                .anyMatch(
                        signal ->
                                !"CONTINUE_WITH_AVAILABLE_EVIDENCE"
                                                .equals(signal.decision())
                                        && !"ESCALATE_MANUAL"
                                                .equals(signal.decision()));
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.removeFinalReviewDecision()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.removeFinalReviewDecision()」：从审核 Signal 队列取出第一条终审决定，其余信号保持原顺序放回，保证重放和并发到达时选择结果稳定，最终返回「ReviewerWorkflowSignal」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.removeFinalReviewDecision()」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.removeFinalReviewDecision()」向下依次触达 「reviewerSignals.removeFirst」、「signal.decision」、「deferred.addLast」、「reviewerSignals.addAll」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.removeFinalReviewDecision()」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private ReviewerWorkflowSignal removeFinalReviewDecision() {
        Deque<ReviewerWorkflowSignal> deferred = new ArrayDeque<>();
        ReviewerWorkflowSignal selected = null;
        while (!reviewerSignals.isEmpty()) {
            ReviewerWorkflowSignal signal = reviewerSignals.removeFirst();
            if (selected == null
                    && !"CONTINUE_WITH_AVAILABLE_EVIDENCE"
                            .equals(signal.decision())
                    && !"ESCALATE_MANUAL".equals(signal.decision())) {
                selected = signal;
            } else {
                deferred.addLast(signal);
            }
        }
        reviewerSignals.addAll(deferred);
        return selected;
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.result(CaseWorkflowInput,String,String,String,String)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.result(CaseWorkflowInput,String,String,String,String)」：构建结果；实际协作者为 「input.caseId」、「input.workflowId」，最终返回「CaseWorkflowResult」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.result(CaseWorkflowInput,String,String,String,String)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.result(CaseWorkflowInput,String,String,String,String)」向下依次触达 「input.caseId」、「input.workflowId」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.result(CaseWorkflowInput,String,String,String,String)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private CaseWorkflowResult result(
            CaseWorkflowInput input,
            String draftId,
            String remedyPlanId,
            String reviewTaskId,
            String nextStage) {
        return new CaseWorkflowResult(
                input.caseId(),
                input.workflowId(),
                status,
                nextStage,
                manualRequired,
                evidenceTimedOut,
                draftId,
                remedyPlanId,
                reviewTaskId);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.submitPartyEvidence(PartyEvidenceSignal)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.submitPartyEvidence(PartyEvidenceSignal)」：把当事方补证 Signal 追加到 FIFO 队列，等待 run 主循环在 Activity 边界持久化，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.submitPartyEvidence(PartyEvidenceSignal)」由使用「CaseFulfillmentDisputeWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.submitPartyEvidence(PartyEvidenceSignal)」向下依次触达 「partySignals.addLast」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.submitPartyEvidence(PartyEvidenceSignal)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void submitPartyEvidence(PartyEvidenceSignal signal) {
        partySignals.addLast(signal);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.submitReviewerSignal(ReviewerWorkflowSignal)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.submitReviewerSignal(ReviewerWorkflowSignal)」：把审核员 Signal 追加到 FIFO 队列，由当前所处的听证等待或 ReviewGate 分支分类消费，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.submitReviewerSignal(ReviewerWorkflowSignal)」由使用「CaseFulfillmentDisputeWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.submitReviewerSignal(ReviewerWorkflowSignal)」向下依次触达 「reviewerSignals.addLast」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.submitReviewerSignal(ReviewerWorkflowSignal)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void submitReviewerSignal(ReviewerWorkflowSignal signal) {
        reviewerSignals.addLast(signal);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflowImpl.queryState()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflowImpl.queryState()」：返回 status、roundNo、是否等待补证、是否超时和 manualRequired 的内存快照；Query 不调 Activity 也不推进 history，最终返回「CaseWorkflowSnapshot」。
    // 上游调用：「CaseFulfillmentDisputeWorkflowImpl.queryState()」由使用「CaseFulfillmentDisputeWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseFulfillmentDisputeWorkflowImpl.queryState()」只产生当前对象的返回值或字段变化，不访问额外基础设施；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「CaseFulfillmentDisputeWorkflowImpl.queryState()」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public CaseWorkflowSnapshot queryState() {
        return new CaseWorkflowSnapshot(
                status,
                roundNo,
                waitingForEvidence,
                evidenceTimedOut,
                manualRequired);
    }
}

/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：以可重放的 Temporal 代码实现最终版案件父工作流与子工作流接力的等待、Signal 与阶段推进。
 * 业务链路：核心入口/契约为 「run」、「submitEvidence」、「submitReviewDecision」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import com.example.dispute.workflow.domain.FulfillmentDisputeCommand;
import com.example.dispute.workflow.domain.FulfillmentDisputeResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import com.example.dispute.workflow.domain.ReviewGateSnapshot;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Deterministic owner of final dispute control flow.
 *
 * <p>This workflow chooses routes, creates durable child workflows and forwards
 * signals. It never performs model inference, network calls or database access;
 * all open-ended cognition and side effects remain inside Activities.
 */
// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「FulfillmentDisputeWorkflowImpl」。
// 类型职责：以可重放的 Temporal 代码实现最终版案件父工作流与子工作流接力的等待、Signal 与阶段推进；本类型显式提供 「run」、「childOptions」、「result」、「submitEvidence」、「submitReviewDecision」、「flushEvidence」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class FulfillmentDisputeWorkflowImpl
        implements FulfillmentDisputeWorkflow {

    private final FulfillmentDisputeActivities activities =
            Workflow.newActivityStub(
                    FulfillmentDisputeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    private final Deque<EvidenceSubmissionSignal> pendingEvidence =
            new ArrayDeque<>();
    private final Deque<HumanReviewSignal> pendingReview = new ArrayDeque<>();
    private DisputeHearingWorkflow hearingWorkflow;
    private HumanReviewWorkflow reviewWorkflow;

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeWorkflowImpl.run(FulfillmentDisputeCommand)」。
    // 具体功能：「FulfillmentDisputeWorkflowImpl.run(FulfillmentDisputeCommand)」：作为最终父 Workflow 按路由选择听证或直接补救，按风险决定是否启动评议子 Workflow，随后串联补救规划、ReviewPacket、人审子 Workflow、执行子 Workflow和结案 Activity，最终返回「FulfillmentDisputeResult」。
    // 上游调用：「FulfillmentDisputeWorkflowImpl.run(FulfillmentDisputeCommand)」由使用「FulfillmentDisputeWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentDisputeWorkflowImpl.run(FulfillmentDisputeCommand)」向下依次触达 「activities.markTransferred」、「Workflow.newChildWorkflowStub」、「hearingWorkflow.run」、「activities.planRemedy」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「FulfillmentDisputeWorkflowImpl.run(FulfillmentDisputeCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public FulfillmentDisputeResult run(FulfillmentDisputeCommand command) {
        // TRANSFERRED 表示路由层已把案件交给平台专线，本父 Workflow 不再创建听证或执行子流程。
        if (command.routeType() == RouteType.TRANSFERRED) {
            activities.markTransferred(command.caseId(), command.workflowId());
            return result(
                    command,
                    "TRANSFERRED",
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        String draftId = null;
        String deliberationId = null;
        boolean manualRequired = false;
        if (command.routeType() == RouteType.FULL_HEARING) {
            // 子 Workflow ID 固定后，父流程重放、Worker 重启或 Signal 提前到达都仍关联同一场听证。
            hearingWorkflow =
                    Workflow.newChildWorkflowStub(
                            DisputeHearingWorkflow.class,
                            childOptions(command.workflowId() + "-hearing"));
            flushEvidence();
            HearingWorkflowResult hearing =
                    hearingWorkflow.run(
                            new HearingWorkflowCommand(
                                    command.caseId(),
                                    command.workflowId(),
                                    command.dossierVersion(),
                                    command.evidenceWaitTimeout(),
                                    command.maxEvidenceRounds(),
                                    Duration.ofHours(3),
                                    3));
            draftId = hearing.draftId();
            manualRequired = hearing.manualRequired();
            if (command.deliberationRequired()) {
                // 评议团只读取冻结的最终草案和卷宗；并行 critic 的反对意见只能提高人工关注，
                // 不能直接批准或执行补救动作。
                DeliberationPanelWorkflow panel =
                        Workflow.newChildWorkflowStub(
                                DeliberationPanelWorkflow.class,
                                childOptions(command.workflowId() + "-panel"));
                DeliberationPanelResult deliberation =
                        panel.run(
                                new DeliberationPanelCommand(
                                        command.caseId(),
                                        command.workflowId(),
                                        draftId,
                                        hearing.dossierVersion(),
                                        List.of(
                                                "EVIDENCE_CRITIC",
                                                "RULE_CRITIC",
                                                "RISK_CRITIC",
                                                "REMEDY_CRITIC",
                                                "FAIRNESS_CRITIC"),
                                        List.of(
                                                "RISK_LEVEL_"
                                                        + command.riskLevel(),
                                                "DELIBERATION_MODE_"
                                                        + command
                                                                .deliberationMode()
                                                                .name()),
                                        command.deliberationScoreThreshold(),
                                        command.deliberationMaxRegenerations()));
                deliberationId = deliberation.deliberationId();
                manualRequired =
                        manualRequired || deliberation.manualRequired();
            }
        }

        String remedyPlanId =
                activities.planRemedy(
                        command.caseId(),
                        command.workflowId(),
                        draftId,
                        deliberationId);
        ReviewGateSnapshot reviewGate =
                activities.createReviewPacket(
                        command.caseId(),
                        draftId,
                        deliberationId,
                        remedyPlanId);
        reviewWorkflow =
                Workflow.newChildWorkflowStub(
                        HumanReviewWorkflow.class,
                        childOptions(command.workflowId() + "-review"));
        flushReview();
        HumanReviewResult review =
                reviewWorkflow.run(
                        new HumanReviewCommand(
                                command.caseId(),
                                reviewGate.reviewPacketId(),
                                reviewGate.reviewPacketVersion(),
                                reviewGate.actionHash(),
                                reviewGate.expiresAtEpochMillis(),
                                command.reviewWaitTimeout(),
                                reviewGate.requiredRole()));
        if (!review.approved()) {
            // 未获明确人工批准时保留所有阶段 ID 并停止自动链路，交给平台人工续办。
            return result(
                    command,
                    "HUMAN_HANDOFF",
                    true,
                    true,
                    draftId,
                    deliberationId,
                    remedyPlanId,
                    review.reviewId(),
                    null);
        }

        ExecutionWorkflow executionWorkflow =
                Workflow.newChildWorkflowStub(
                        ExecutionWorkflow.class,
                        childOptions(command.workflowId() + "-execution"));
        ExecutionResult execution =
                executionWorkflow.run(
                        new ExecutionCommand(
                                command.caseId(),
                                review.reviewId(),
                                reviewGate.reviewPacketVersion(),
                                reviewGate.actionHash(),
                                true,
                                reviewGate.expiresAtEpochMillis(),
                                List.of(
                                        new com.example.dispute.workflow.domain
                                                .ExecutionAction(
                                                "ACTION_APPROVED_PLAN",
                                                "APPROVED_PLAN",
                                                "EXECUTE_"
                                                        + command.caseId()
                                                        + "_"
                                                        + review.reviewId(),
                                                List.of()))));
        if (!"SUCCEEDED".equals(execution.status())) {
            // 执行子流程可能部分成功；返回其状态而不在父流程中重试自由副作用。
            return result(
                    command,
                    "HUMAN_HANDOFF",
                    true,
                    true,
                    draftId,
                    deliberationId,
                    remedyPlanId,
                    review.reviewId(),
                    execution.status());
        }
        activities.closeCaseAndEvaluate(command.caseId());
        return result(
                command,
                "EVALUATION_COMPLETE",
                true,
                manualRequired,
                draftId,
                deliberationId,
                remedyPlanId,
                review.reviewId(),
                execution.status());
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeWorkflowImpl.childOptions(String)」。
    // 具体功能：「FulfillmentDisputeWorkflowImpl.childOptions(String)」：为每个子 Workflow 设置由父 workflowId 和阶段后缀组成的稳定 ID，父流程重放时会关联同一子实例，最终返回「ChildWorkflowOptions」。
    // 上游调用：「FulfillmentDisputeWorkflowImpl.childOptions(String)」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeWorkflowImpl.childOptions(String)」向下依次触达 「ChildWorkflowOptions.newBuilder」、「ChildWorkflowOptions.newBuilder().setWorkflowId」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「FulfillmentDisputeWorkflowImpl.childOptions(String)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private ChildWorkflowOptions childOptions(String workflowId) {
        return ChildWorkflowOptions.newBuilder().setWorkflowId(workflowId).build();
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeWorkflowImpl.result(FulfillmentDisputeCommand,String,boolean,boolean,String,String,String,String,String)」。
    // 具体功能：「FulfillmentDisputeWorkflowImpl.result(FulfillmentDisputeCommand,String,boolean,boolean,String,String,String,String,String)」：把听证、评议、补救、人审和执行各阶段 ID 与 manualRequired 标志收束为父 Workflow 的最终只读结果，最终返回「FulfillmentDisputeResult」。
    // 上游调用：「FulfillmentDisputeWorkflowImpl.result(FulfillmentDisputeCommand,String,boolean,boolean,String,String,String,String,String)」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeWorkflowImpl.result(FulfillmentDisputeCommand,String,boolean,boolean,String,String,String,String,String)」向下依次触达 「command.caseId」、「command.workflowId」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「FulfillmentDisputeWorkflowImpl.result(FulfillmentDisputeCommand,String,boolean,boolean,String,String,String,String,String)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private FulfillmentDisputeResult result(
            FulfillmentDisputeCommand command,
            String nextStage,
            boolean humanReviewRequired,
            boolean manualRequired,
            String draftId,
            String deliberationId,
            String remedyPlanId,
            String reviewId,
            String executionStatus) {
        return new FulfillmentDisputeResult(
                command.caseId(),
                command.workflowId(),
                "COMPLETED",
                nextStage,
                humanReviewRequired,
                manualRequired,
                draftId,
                deliberationId,
                remedyPlanId,
                reviewId,
                executionStatus);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」。
    // 具体功能：「FulfillmentDisputeWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」：听证子 Workflow 尚未创建时先缓存补证 Signal，创建后直接转发；保证父 Workflow 启动早期到达的 Signal 不丢失，最终返回「void」。
    // 上游调用：「FulfillmentDisputeWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」由使用「FulfillmentDisputeWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentDisputeWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」向下依次触达 「hearingWorkflow.submitEvidence」、「pendingEvidence.addLast」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「FulfillmentDisputeWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void submitEvidence(EvidenceSubmissionSignal signal) {
        if (hearingWorkflow == null) {
            pendingEvidence.addLast(signal);
        } else {
            hearingWorkflow.submitEvidence(signal);
        }
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeWorkflowImpl.submitReviewDecision(HumanReviewSignal)」。
    // 具体功能：「FulfillmentDisputeWorkflowImpl.submitReviewDecision(HumanReviewSignal)」：人审子 Workflow 尚未创建时先缓存决定 Signal，创建后直接转发；不在父回调中解释决定内容，最终返回「void」。
    // 上游调用：「FulfillmentDisputeWorkflowImpl.submitReviewDecision(HumanReviewSignal)」由使用「FulfillmentDisputeWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FulfillmentDisputeWorkflowImpl.submitReviewDecision(HumanReviewSignal)」向下依次触达 「reviewWorkflow.submitDecision」、「pendingReview.addLast」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「FulfillmentDisputeWorkflowImpl.submitReviewDecision(HumanReviewSignal)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void submitReviewDecision(HumanReviewSignal signal) {
        if (reviewWorkflow == null) {
            pendingReview.addLast(signal);
        } else {
            reviewWorkflow.submitDecision(signal);
        }
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeWorkflowImpl.flushEvidence()」。
    // 具体功能：「FulfillmentDisputeWorkflowImpl.flushEvidence()」：听证子 Workflow 句柄就绪后按 FIFO 顺序转发启动前积压的全部补证 Signal，最终返回「void」。
    // 上游调用：「FulfillmentDisputeWorkflowImpl.flushEvidence()」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeWorkflowImpl.flushEvidence()」向下依次触达 「hearingWorkflow.submitEvidence」、「pendingEvidence.removeFirst」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「FulfillmentDisputeWorkflowImpl.flushEvidence()」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private void flushEvidence() {
        while (!pendingEvidence.isEmpty()) {
            hearingWorkflow.submitEvidence(pendingEvidence.removeFirst());
        }
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeWorkflowImpl.flushReview()」。
    // 具体功能：「FulfillmentDisputeWorkflowImpl.flushReview()」：人审子 Workflow 句柄就绪后按 FIFO 顺序转发启动前积压的全部审核决定 Signal，最终返回「void」。
    // 上游调用：「FulfillmentDisputeWorkflowImpl.flushReview()」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeWorkflowImpl.flushReview()」向下依次触达 「reviewWorkflow.submitDecision」、「pendingReview.removeFirst」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「FulfillmentDisputeWorkflowImpl.flushReview()」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private void flushReview() {
        while (!pendingReview.isEmpty()) {
            reviewWorkflow.submitDecision(pendingReview.removeFirst());
        }
    }
}

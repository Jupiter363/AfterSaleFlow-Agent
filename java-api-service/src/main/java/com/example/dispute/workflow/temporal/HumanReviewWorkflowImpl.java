/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：以可重放的 Temporal 代码实现平台终审等待、过期与决定校验的等待、Signal 与阶段推进。
 * 业务链路：核心入口/契约为 「run」、「submitDecision」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Durable human gate for every remedy.
 *
 * <p>The workflow validates the exact frozen packet version and action hash
 * before accepting a reviewer decision. Invalid or stale signals are audited
 * and cannot advance execution.
 */
// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「HumanReviewWorkflowImpl」。
// 类型职责：以可重放的 Temporal 代码实现平台终审等待、过期与决定校验的等待、Signal 与阶段推进；本类型显式提供 「run」、「expiredOrTimedOut」、「validate」、「submitDecision」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class HumanReviewWorkflowImpl implements HumanReviewWorkflow {

    private static final Set<String> DECISIONS =
            Set.of(
                    "APPROVE",
                    "MODIFY_AND_APPROVE",
                    "RETURN_FOR_REVISION",
                    "REQUEST_MORE_EVIDENCE",
                    "REJECT",
                    "ESCALATE",
                    "ESCALATE_MANUAL");
    private static final Map<String, String> STATUSES =
            Map.of(
                    "APPROVE", "APPROVED",
                    "MODIFY_AND_APPROVE", "MODIFIED_AND_APPROVED",
                    "RETURN_FOR_REVISION", "RETURNED_FOR_REVISION",
                    "REQUEST_MORE_EVIDENCE", "MORE_EVIDENCE_REQUESTED",
                    "REJECT", "REJECTED",
                    "ESCALATE", "ESCALATED",
                    "ESCALATE_MANUAL", "ESCALATED");

    private final HumanReviewActivities activities =
            Workflow.newActivityStub(
                    HumanReviewActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(1))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());
    private final Deque<HumanReviewSignal> decisions = new ArrayDeque<>();

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「HumanReviewWorkflowImpl.run(HumanReviewCommand)」。
    // 具体功能：「HumanReviewWorkflowImpl.run(HumanReviewCommand)」：在 packet 过期时间与 waitTimeout 的较早者之前等待审核 Signal；逐条校验审核员、角色、packet 版本和 action hash，非法决定写审计后继续等待，合法决定持久化并返回是否允许执行，最终返回「HumanReviewResult」。
    // 上游调用：「HumanReviewWorkflowImpl.run(HumanReviewCommand)」由使用「HumanReviewWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HumanReviewWorkflowImpl.run(HumanReviewCommand)」向下依次触达 「Workflow.currentTimeMillis」、「Workflow.await」、「activities.recordInvalidDecision」、「activities.persistDecision」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「HumanReviewWorkflowImpl.run(HumanReviewCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public HumanReviewResult run(HumanReviewCommand command) {
        // ReviewPacket 自身的 expiresAt 与本次 Workflow waitTimeout 是两条独立边界；
        // 取较早者，防止已过期的动作快照在较长的人工等待期内重新获得执行资格。
        long waitDeadline =
                Workflow.currentTimeMillis() + command.waitTimeout().toMillis();
        long deadline = Math.min(waitDeadline, command.expiresAtEpochMillis());
        while (true) {
            long remaining = deadline - Workflow.currentTimeMillis();
            if (remaining <= 0) {
                return expiredOrTimedOut(command);
            }
            boolean received =
                    Workflow.await(
                            Duration.ofMillis(remaining),
                            () -> !decisions.isEmpty());
            if (!received) {
                return expiredOrTimedOut(command);
            }
            HumanReviewSignal signal = decisions.removeFirst();
            String invalidReason = validate(command, signal);
            if (invalidReason != null) {
                // 非法 Signal 不是 Workflow 技术失败：记录审计后继续等下一次人工决定，
                // 这样攻击性/陈旧请求不会终止整条可恢复流程。
                activities.recordInvalidDecision(
                        command, signal, invalidReason);
                continue;
            }
            String status = STATUSES.get(signal.decision());
            // 只有通过身份、角色、版本、哈希和有效期校验的决定才进入持久化 Activity。
            String reviewId =
                    activities.persistDecision(command, signal, status);
            return new HumanReviewResult(
                    reviewId,
                    status,
                    "APPROVE".equals(signal.decision())
                            || "MODIFY_AND_APPROVE".equals(
                                    signal.decision()),
                    "MODIFY_AND_APPROVE".equals(signal.decision()),
                    null);
        }
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「HumanReviewWorkflowImpl.expiredOrTimedOut(HumanReviewCommand)」。
    // 具体功能：「HumanReviewWorkflowImpl.expiredOrTimedOut(HumanReviewCommand)」：用 Temporal 当前时间区分 ReviewPacket 已过期与单次等待超时，分别返回 EXPIRED 或 TIMED_OUT，二者都禁止执行，最终返回「HumanReviewResult」。
    // 上游调用：「HumanReviewWorkflowImpl.expiredOrTimedOut(HumanReviewCommand)」的上游调用点包括 「HumanReviewWorkflowImpl.run」。
    // 下游影响：「HumanReviewWorkflowImpl.expiredOrTimedOut(HumanReviewCommand)」向下依次触达 「Workflow.currentTimeMillis」、「command.expiresAtEpochMillis」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「HumanReviewWorkflowImpl.expiredOrTimedOut(HumanReviewCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private HumanReviewResult expiredOrTimedOut(
            HumanReviewCommand command) {
        if (Workflow.currentTimeMillis() >= command.expiresAtEpochMillis()) {
            return new HumanReviewResult(
                    null,
                    "EXPIRED",
                    false,
                    false,
                    "REVIEW_PACKET_EXPIRED");
        }
        return new HumanReviewResult(
                null,
                "TIMED_OUT",
                false,
                false,
                "REVIEW_DECISION_TIMEOUT");
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「HumanReviewWorkflowImpl.validate(HumanReviewCommand,HumanReviewSignal)」。
    // 具体功能：「HumanReviewWorkflowImpl.validate(HumanReviewCommand,HumanReviewSignal)」：依次核对 reviewerId、requiredRole、reviewPacketId/version、actionSnapshotHash、expiresAt 和允许的决定枚举，返回稳定错误码而非抛异常，使非法尝试可以审计后继续等待下一次 Signal，最终返回「String」。
    // 上游调用：「HumanReviewWorkflowImpl.validate(HumanReviewCommand,HumanReviewSignal)」的上游调用点包括 「HumanReviewWorkflowImpl.run」。
    // 下游影响：「HumanReviewWorkflowImpl.validate(HumanReviewCommand,HumanReviewSignal)」向下依次触达 「Workflow.currentTimeMillis」、「signal.reviewerId」、「command.requiredRole」、「signal.reviewerRole」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「HumanReviewWorkflowImpl.validate(HumanReviewCommand,HumanReviewSignal)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private String validate(
            HumanReviewCommand command,
            HumanReviewSignal signal) {
        if (signal.reviewerId() == null || signal.reviewerId().isBlank()) {
            return "INVALID_REVIEWER";
        }
        if (!command.requiredRole().equals(signal.reviewerRole())) {
            return "UNAUTHORIZED_REVIEWER_ROLE";
        }
        if (command.reviewPacketVersion()
                != signal.reviewPacketVersion()) {
            return "STALE_REVIEW_PACKET";
        }
        if (!command.actionHash().equals(signal.actionHash())) {
            return "ACTION_HASH_MISMATCH";
        }
        if (!DECISIONS.contains(signal.decision())) {
            return "INVALID_REVIEW_DECISION";
        }
        if (signal.reason() == null || signal.reason().isBlank()) {
            return "REVIEW_REASON_REQUIRED";
        }
        if (Workflow.currentTimeMillis() >= command.expiresAtEpochMillis()) {
            return "REVIEW_PACKET_EXPIRED";
        }
        return null;
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「HumanReviewWorkflowImpl.submitDecision(HumanReviewSignal)」。
    // 具体功能：「HumanReviewWorkflowImpl.submitDecision(HumanReviewSignal)」：把人工决定 Signal 放入 FIFO 队列；真正的身份、版本和哈希校验由 run 在可重放主循环中完成，最终返回「void」。
    // 上游调用：「HumanReviewWorkflowImpl.submitDecision(HumanReviewSignal)」由使用「HumanReviewWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HumanReviewWorkflowImpl.submitDecision(HumanReviewSignal)」向下依次触达 「decisions.addLast」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「HumanReviewWorkflowImpl.submitDecision(HumanReviewSignal)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void submitDecision(HumanReviewSignal signal) {
        decisions.addLast(signal);
    }
}

/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：以可重放的 Temporal 代码实现两小时举证窗口的持久化计时与双方完成信号的等待、Signal 与阶段推进。
 * 业务链路：核心入口/契约为 「run」、「partyCompleted」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.domain.EvidenceWindowResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「EvidenceWindowWorkflowImpl」。
// 类型职责：以可重放的 Temporal 代码实现两小时举证窗口的持久化计时与双方完成信号的等待、Signal 与阶段推进；本类型显式提供 「run」、「completed」、「partyCompleted」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class EvidenceWindowWorkflowImpl implements EvidenceWindowWorkflow {

    private static final Duration WARNING_LEAD = Duration.ofMinutes(30);

    private final EvidenceWindowActivities activities =
            Workflow.newActivityStub(
                    EvidenceWindowActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(1))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    private final Set<String> completedRoles = new LinkedHashSet<>();

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「EvidenceWindowWorkflowImpl.run(EvidenceWindowCommand)」。
    // 具体功能：「EvidenceWindowWorkflowImpl.run(EvidenceWindowCommand)」：用 Temporal Timer 管理举证窗口：先等待到“截止前 15 分钟”或双方完成，未完成则调用 warn；随后等待剩余窗口，双方仍未完成时调用 expire 冻结现状，并返回完成角色与 DEADLINE_EXPIRED 原因，最终返回「EvidenceWindowResult」。
    // 上游调用：「EvidenceWindowWorkflowImpl.run(EvidenceWindowCommand)」由使用「EvidenceWindowWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceWindowWorkflowImpl.run(EvidenceWindowCommand)」向下依次触达 「Workflow.await」、「activities.warn」、「activities.expire」、「command.window」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「EvidenceWindowWorkflowImpl.run(EvidenceWindowCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public EvidenceWindowResult run(EvidenceWindowCommand command) {
        // 第一段 Timer 只等到“临期提醒点”。Signal 条件使用 Set 判断双方角色，
        // 所以重复的 partyCompleted 不会改变 history 中的业务结果。
        Duration beforeWarning = command.window().minus(WARNING_LEAD);
        if (beforeWarning.isNegative() || beforeWarning.isZero()) {
            beforeWarning = Duration.ZERO;
        }
        boolean completedEarly =
                Workflow.await(
                        beforeWarning,
                        () ->
                                completedRoles.contains("USER")
                                        && completedRoles.contains("MERCHANT"));
        if (completedEarly) {
            return completed(command.caseId());
        }
        // 提醒和到期写库都属于不可重放副作用，必须通过 Activity 执行。
        activities.warn(command.caseId());
        Duration remaining = command.window().minus(beforeWarning);
        completedEarly =
                Workflow.await(
                        remaining,
                        () ->
                                completedRoles.contains("USER")
                                        && completedRoles.contains("MERCHANT"));
        if (completedEarly) {
            return completed(command.caseId());
        }
        // 第二段 Timer 到期后以当前已提交材料封卷；缺席方不会让 Workflow 永久等待。
        activities.expire(command.caseId());
        return new EvidenceWindowResult(
                command.caseId(),
                "DEADLINE_EXPIRED",
                new ArrayList<>(completedRoles));
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「EvidenceWindowWorkflowImpl.completed(String)」。
    // 具体功能：「EvidenceWindowWorkflowImpl.completed(String)」：把当前 completedRoles 防御性复制为工作流结果，并写入 BOTH_PARTIES_COMPLETED 等停止原因，最终返回「EvidenceWindowResult」。
    // 上游调用：「EvidenceWindowWorkflowImpl.completed(String)」的上游调用点包括 「EvidenceWindowWorkflowImpl.run」。
    // 下游影响：「EvidenceWindowWorkflowImpl.completed(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「EvidenceWindowWorkflowImpl.completed(String)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private EvidenceWindowResult completed(String caseId) {
        return new EvidenceWindowResult(
                caseId,
                "BOTH_PARTIES_COMPLETED",
                new ArrayList<>(completedRoles));
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「EvidenceWindowWorkflowImpl.partyCompleted(String)」。
    // 具体功能：「EvidenceWindowWorkflowImpl.partyCompleted(String)」：接收当事方完成 Signal，仅接受 USER/MERCHANT 并写入 Set；重复信号天然幂等，不会重复推进阶段，最终返回「void」。
    // 上游调用：「EvidenceWindowWorkflowImpl.partyCompleted(String)」由使用「EvidenceWindowWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceWindowWorkflowImpl.partyCompleted(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「EvidenceWindowWorkflowImpl.partyCompleted(String)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void partyCompleted(String role) {
        if ("USER".equals(role) || "MERCHANT".equals(role)) {
            completedRoles.add(role);
        }
    }
}

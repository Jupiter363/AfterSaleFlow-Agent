/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：以可重放的 Temporal 代码实现审批后动作依赖排序和幂等执行的等待、Signal 与阶段推进。
 * 业务链路：核心入口/契约为 「run」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.ApprovalValidationResult;
import com.example.dispute.workflow.domain.ExecutionAction;
import com.example.dispute.workflow.domain.ExecutionActionActivityResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic executor for an approved immutable action snapshot.
 *
 * <p>Dependency ordering happens in workflow code. External writes and
 * unknown-result lookups use Activities with bounded retries and explicit
 * idempotency keys carried by each action.
 */
// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「ExecutionWorkflowImpl」。
// 类型职责：以可重放的 Temporal 代码实现审批后动作依赖排序和幂等执行的等待、Signal 与阶段推进；本类型显式提供 「run」、「dependencyOrder」、「manual」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class ExecutionWorkflowImpl implements ExecutionWorkflow {

    private final ExecutionActivities activities =
            Workflow.newActivityStub(
                    ExecutionActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setInitialInterval(Duration.ofSeconds(1))
                                            .setMaximumInterval(Duration.ofSeconds(15))
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「ExecutionWorkflowImpl.run(ExecutionCommand)」。
    // 具体功能：「ExecutionWorkflowImpl.run(ExecutionCommand)」：先检查人工批准标志与审批有效期，再由 Activity 复核审批快照；按依赖拓扑逐个查询或执行动作，任何未知、失败或依赖环都会停止自动执行并返回 MANUAL_HANDOFF，最终返回「ExecutionResult」。
    // 上游调用：「ExecutionWorkflowImpl.run(ExecutionCommand)」由使用「ExecutionWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ExecutionWorkflowImpl.run(ExecutionCommand)」向下依次触达 「Workflow.currentTimeMillis」、「activities.validateApproval」、「activities.executeAction」、「activities.lookupAction」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「ExecutionWorkflowImpl.run(ExecutionCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public ExecutionResult run(ExecutionCommand command) {
        // Workflow 先做便宜的确定性检查，Activity 再以数据库事实复核审批。
        // 任一层失败都转人工，不尝试“猜测”审核员意图。
        if (!command.approved()
                || Workflow.currentTimeMillis()
                        >= command.approvalExpiresAtEpochMillis()) {
            return manual(List.of());
        }
        ApprovalValidationResult approval =
                activities.validateApproval(command);
        if (!approval.valid()) {
            return manual(List.of());
        }
        List<ExecutionAction> ordered = dependencyOrder(command.actions());
        if (ordered == null) {
            return manual(List.of());
        }

        List<String> completed = new ArrayList<>();
        try {
            // 动作必须串行执行：每一步的 SUCCEEDED 事实可能是后续 dependsOn 的前置条件。
            // UNKNOWN 时先查幂等记录，避免 Activity 超时后把已经成功的外部动作再执行一次。
            for (ExecutionAction action : ordered) {
                if (action.idempotencyKey() == null
                        || action.idempotencyKey().isBlank()) {
                    return manual(completed);
                }
                ExecutionActionActivityResult result =
                        activities.executeAction(command.caseId(), action);
                if ("UNKNOWN".equals(result.status())) {
                    result =
                            activities.lookupAction(
                                    command.caseId(), action);
                }
                if (!"SUCCEEDED".equals(result.status())) {
                    return manual(completed);
                }
                completed.add(action.actionId());
            }
        } catch (ActivityFailure failure) {
            return manual(completed);
        }
        return new ExecutionResult("SUCCEEDED", false, completed);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「ExecutionWorkflowImpl.dependencyOrder(List)」。
    // 具体功能：「ExecutionWorkflowImpl.dependencyOrder(List)」：用已完成集合做确定性的拓扑排序：只有 dependsOn 全部满足的动作才进入结果；重复 actionId、未知依赖或无可推进节点都会返回空列表表示需要人工处理，最终返回「List<ExecutionAction>」。
    // 上游调用：「ExecutionWorkflowImpl.dependencyOrder(List)」的上游调用点包括 「ExecutionWorkflowImpl.run」。
    // 下游影响：「ExecutionWorkflowImpl.dependencyOrder(List)」向下依次触达 「action.actionId」、「remaining.values」、「completed.containsAll」、「action.dependsOn」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「ExecutionWorkflowImpl.dependencyOrder(List)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private List<ExecutionAction> dependencyOrder(
            List<ExecutionAction> actions) {
        Map<String, ExecutionAction> remaining = new LinkedHashMap<>();
        for (ExecutionAction action : actions) {
            if (action.actionId() == null
                    || action.actionId().isBlank()
                    || remaining.put(action.actionId(), action) != null) {
                return null;
            }
        }
        Set<String> completed = new LinkedHashSet<>();
        List<ExecutionAction> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            ExecutionAction next =
                    remaining.values().stream()
                            .filter(
                                    action ->
                                            completed.containsAll(
                                                    action.dependsOn()))
                            .findFirst()
                            .orElse(null);
            if (next == null) {
                return null;
            }
            remaining.remove(next.actionId());
            ordered.add(next);
            completed.add(next.actionId());
        }
        return ordered;
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「ExecutionWorkflowImpl.manual(List)」。
    // 具体功能：「ExecutionWorkflowImpl.manual(List)」：把已完成动作 ID 防御性复制并生成 MANUAL_HANDOFF 结果，保留部分成功事实供人工续办，避免自动重放已执行动作，最终返回「ExecutionResult」。
    // 上游调用：「ExecutionWorkflowImpl.manual(List)」的上游调用点包括 「ExecutionWorkflowImpl.run」。
    // 下游影响：「ExecutionWorkflowImpl.manual(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「ExecutionWorkflowImpl.manual(List)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private ExecutionResult manual(List<String> completed) {
        return new ExecutionResult(
                "MANUAL_HANDOFF",
                true,
                List.copyOf(completed));
    }
}

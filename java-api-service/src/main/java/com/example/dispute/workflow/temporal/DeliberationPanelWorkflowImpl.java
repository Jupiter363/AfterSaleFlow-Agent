/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：以可重放的 Temporal 代码实现高风险案件多评审角色并行评议的等待、Signal 与阶段推进。
 * 业务链路：核心入口/契约为 「run」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.CriticActivityResult;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import com.example.dispute.workflow.domain.FrozenDeliberationSnapshot;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs risk-selected critics in parallel over one immutable snapshot.
 *
 * <p>Failures and minority BLOCKER opinions are retained as explicit review
 * risks; the aggregator never treats an unavailable critic as agreement.
 */
// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「DeliberationPanelWorkflowImpl」。
// 类型职责：以可重放的 Temporal 代码实现高风险案件多评审角色并行评议的等待、Signal 与阶段推进；本类型显式提供 「run」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class DeliberationPanelWorkflowImpl
        implements DeliberationPanelWorkflow {

    private final DeliberationPanelActivities activities =
            Workflow.newActivityStub(
                    DeliberationPanelActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(1))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(1)
                                            .build())
                            .build());

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DeliberationPanelWorkflowImpl.run(DeliberationPanelCommand)」。
    // 具体功能：「DeliberationPanelWorkflowImpl.run(DeliberationPanelCommand)」：先冻结同一份评议快照，再用 Async.function 并行运行所选 critic；汇总不可用角色、重大反对意见和分数，任何 BLOCKER/HIGH 异议或低于阈值都会进入报告，不能被平均分掩盖，最终返回「DeliberationPanelResult」。
    // 上游调用：「DeliberationPanelWorkflowImpl.run(DeliberationPanelCommand)」由使用「DeliberationPanelWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DeliberationPanelWorkflowImpl.run(DeliberationPanelCommand)」向下依次触达 「activities.freeze」、「activities.persistReport」、「Async.function」、「command.selectedCritics」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「DeliberationPanelWorkflowImpl.run(DeliberationPanelCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public DeliberationPanelResult run(DeliberationPanelCommand command) {
        FrozenDeliberationSnapshot snapshot = activities.freeze(command);
        List<Promise<CriticActivityResult>> promises = new ArrayList<>();
        for (String critic : command.selectedCritics()) {
            promises.add(
                    Async.function(
                            activities::runCritic,
                            snapshot,
                            critic));
        }

        List<CriticActivityResult> reports = new ArrayList<>();
        Set<String> unavailable = new LinkedHashSet<>();
        Set<String> majorObjections = new LinkedHashSet<>();
        boolean blocker = false;
        boolean scoreBelowThreshold = false;
        for (int index = 0; index < promises.size(); index++) {
            String critic = command.selectedCritics().get(index);
            CriticActivityResult report;
            try {
                report = promises.get(index).get();
            } catch (ActivityFailure failure) {
                unavailable.add(critic);
                continue;
            }
            reports.add(report);
            boolean sameFrozenInput =
                    snapshot.fingerprint()
                            .equals(report.frozenInputFingerprint());
            if (!sameFrozenInput
                    || !"COMPLETED".equals(report.status())) {
                unavailable.add(critic);
                continue;
            }
            boolean explicitMajorSeverity =
                    "BLOCKER".equals(report.severity())
                            || "HIGH".equals(report.severity());
            if (!explicitMajorSeverity
                    && report.score() < command.scoreThreshold()) {
                scoreBelowThreshold = true;
                majorObjections.add(
                        critic + "_SCORE_BELOW_THRESHOLD_" + report.score());
            }
            if ("BLOCKER".equals(report.severity())) {
                blocker = true;
                majorObjections.addAll(report.blockingIssues());
            } else if ("HIGH".equals(report.severity())) {
                majorObjections.addAll(report.blockingIssues());
            }
        }

        String panelResult;
        if (!unavailable.isEmpty()) {
            panelResult = "MANUAL_REVIEW_REQUIRED";
        } else if (blocker || scoreBelowThreshold) {
            panelResult = "REVISION_REQUIRED";
        } else {
            panelResult = "NO_MAJOR_OBJECTION";
        }
        String deliberationId =
                activities.persistReport(
                        command,
                        snapshot,
                        List.copyOf(reports),
                        panelResult);
        return new DeliberationPanelResult(
                deliberationId,
                panelResult,
                blocker || scoreBelowThreshold || !unavailable.isEmpty(),
                !unavailable.isEmpty(),
                List.copyOf(majorObjections),
                List.copyOf(unavailable));
    }
}

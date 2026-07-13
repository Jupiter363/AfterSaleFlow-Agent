/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：以可重放的 Temporal 代码实现三小时共享庭审与最多三轮收敛的等待、Signal 与阶段推进。
 * 业务链路：核心入口/契约为 「run」、「submitEvidence」、「hearingRoundCompleted」、「settlementConfirmed」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.HearingStageActivityResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Durable C1-C6 hearing controller.
 *
 * <p>The workflow only orders validated stages and waits on durable
 * Signal/Timer state. Model, network and persistence work is delegated to
 * Activities so replay remains deterministic.
 */
// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「DisputeHearingWorkflowImpl」。
// 类型职责：以可重放的 Temporal 代码实现三小时共享庭审与最多三轮收敛的等待、Signal 与阶段推进；本类型显式提供 「run」、「runStage」、「interrupt」、「submitEvidence」、「hearingRoundCompleted」、「settlementConfirmed」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class DisputeHearingWorkflowImpl
        implements DisputeHearingWorkflow {

    private final DisputeHearingActivities activities =
            Workflow.newActivityStub(
                    DisputeHearingActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(7))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setInitialInterval(Duration.ofSeconds(1))
                                            .setMaximumInterval(Duration.ofSeconds(10))
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    private final Deque<EvidenceSubmissionSignal> evidenceSignals =
            new ArrayDeque<>();
    private int completedHearingRounds;
    private int confirmedSettlementVersion;

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflowImpl.run(HearingWorkflowCommand)」。
    // 具体功能：「DisputeHearingWorkflowImpl.run(HearingWorkflowCommand)」：按 C1 争点梳理、C2 证据缺口、C3 当事方沟通、C4 交叉核验、C5 规则适用、C6 草案生成顺序推进；期间消费补证 Signal、轮次完成和同版本和解确认，并在三小时截止、三轮耗尽或和解确认时强制终局收敛，最终返回「HearingWorkflowResult」。
    // 上游调用：「DisputeHearingWorkflowImpl.run(HearingWorkflowCommand)」由使用「DisputeHearingWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DisputeHearingWorkflowImpl.run(HearingWorkflowCommand)」向下依次触达 「activities.initialize」、「activities.complete」、「Workflow.await」、「activities.recordEvidence」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「DisputeHearingWorkflowImpl.run(HearingWorkflowCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public HearingWorkflowResult run(HearingWorkflowCommand command) {
        long dossierVersion = command.dossierVersion();
        int round = 0;
        boolean manualRequired = false;
        boolean evidenceTimedOut = false;
        String draftId = null;
        // initialize 是 Activity：它负责数据库事实；下面的 Workflow 变量只保存可重放控制状态。
        activities.initialize(command);
        boolean failClosedOnStageFailure =
                Workflow.getVersion(
                                        "hearing-stage-failure-fail-closed",
                                        Workflow.DEFAULT_VERSION,
                                        1)
                                == 1;
        String stopReason = awaitSharedHearing(command);
        if ("DEADLINE_EXPIRED".equals(stopReason)) {
            evidenceTimedOut = true;
            manualRequired = true;
        } else if ("MAX_ROUNDS".equals(stopReason)) {
            manualRequired = true;
        }
        if ("SETTLEMENT_CONFIRMED".equals(stopReason)) {
            // 双方确认同一和解版本后不再运行 C1-C6，但仍只形成待平台终审的流程结果。
            activities.complete(
                    command.caseId(),
                    command.workflowId(),
                    "SETTLEMENT_CONFIRMED",
                    false,
                    false,
                    dossierVersion,
                    stopReason);
            return new HearingWorkflowResult(
                    null,
                    false,
                    false,
                    dossierVersion,
                    "SETTLEMENT_CONFIRMED",
                    stopReason);
        }
        round = completedHearingRounds;
        try {
            while (true) {
                // C1/C2 可以循环：每次补证都可能生成新 dossierVersion，
                // 后续阶段必须显式携带该版本，不能读取“当前可变证据集合”。
                HearingStageActivityResult c1 =
                        runStage(
                                command,
                                "C1_ISSUE_FRAMING",
                                round,
                                dossierVersion,
                                evidenceTimedOut,
                                stopReason != null);
                manualRequired = manualRequired || c1.manualRequired();
                if (!c1.valid()) {
                    return interrupt(
                            command,
                            dossierVersion,
                            evidenceTimedOut,
                            "VALIDATION_INTERRUPTED",
                            failClosedOnStageFailure);
                }
                HearingStageActivityResult c2 =
                        runStage(
                                command,
                                "C2_EVIDENCE_GAP",
                                round,
                                dossierVersion,
                                evidenceTimedOut,
                                stopReason != null);
                manualRequired = manualRequired || c2.manualRequired();
                if (!c2.valid()) {
                    return interrupt(
                            command,
                            dossierVersion,
                            evidenceTimedOut,
                            "VALIDATION_INTERRUPTED",
                            failClosedOnStageFailure);
                }
                if (!c2.requiresAdditionalEvidence()
                        || evidenceTimedOut
                        || stopReason != null) {
                    break;
                }
                if (round >= command.maxEvidenceRounds()) {
                    manualRequired = true;
                    break;
                }
                HearingStageActivityResult c3 =
                        runStage(
                                command,
                                "C3_EVIDENCE_REQUEST",
                                round,
                                dossierVersion,
                                false,
                                false);
                manualRequired = manualRequired || c3.manualRequired();
                if (!c3.valid()) {
                    return interrupt(
                            command,
                            dossierVersion,
                            evidenceTimedOut,
                            "VALIDATION_INTERRUPTED",
                            failClosedOnStageFailure);
                }
                boolean received =
                        Workflow.await(
                                command.evidenceWaitTimeout(),
                                () -> !evidenceSignals.isEmpty());
                if (!received) {
                    evidenceTimedOut = true;
                    manualRequired = true;
                    break;
                }
                while (!evidenceSignals.isEmpty()) {
                    // Signal 回调只入队；真正的数据库写入集中在 Activity 中，
                    // 既保持 Workflow 确定性，也让每份补证具备幂等事务边界。
                    long nextVersion =
                            activities.recordEvidence(
                                    evidenceSignals.removeFirst());
                    if (nextVersion > dossierVersion) {
                        dossierVersion = nextVersion;
                    }
                }
                round++;
            }

            // 一旦不再等待补证，C4-C6 按固定顺序一次执行，确保草案引用的是最终冻结版本。
            for (String stage :
                    new String[] {
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION",
                        "C6_DRAFT_GENERATION"
                    }) {
                HearingStageActivityResult result =
                        runStage(
                                command,
                                stage,
                                round,
                                dossierVersion,
                                evidenceTimedOut,
                                stopReason != null);
                manualRequired = manualRequired || result.manualRequired();
                if (!result.valid()) {
                    return interrupt(
                            command,
                            dossierVersion,
                            evidenceTimedOut,
                            "VALIDATION_INTERRUPTED",
                            failClosedOnStageFailure);
                }
                if ("C6_DRAFT_GENERATION".equals(stage)) {
                    draftId = result.draftId();
                }
            }
        } catch (ActivityFailure failure) {
            if (failClosedOnStageFailure) {
                throw failure;
            }
            return interrupt(
                    command,
                    dossierVersion,
                    evidenceTimedOut,
                    "ACTIVITY_INTERRUPTED",
                    false);
        }
        String status = manualRequired ? "MANUAL_REVIEW_REQUIRED" : "COMPLETED";
        activities.complete(
                command.caseId(),
                command.workflowId(),
                status,
                manualRequired,
                evidenceTimedOut,
                dossierVersion,
                stopReason);
        return new HearingWorkflowResult(
                draftId,
                manualRequired,
                evidenceTimedOut,
                dossierVersion,
                status,
                stopReason);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflowImpl.runStage(HearingWorkflowCommand,String,int,long,boolean,boolean)」。
    // 具体功能：「DisputeHearingWorkflowImpl.runStage(HearingWorkflowCommand,String,int,long,boolean,boolean)」：调用 Activity 执行指定庭审阶段，并立即把 stage、round、dossierVersion 与 outputVersion 写入阶段轨迹；Workflow 只保存确定性控制变量，不直接解析模型结果，最终返回「HearingStageActivityResult」。
    // 上游调用：「DisputeHearingWorkflowImpl.runStage(HearingWorkflowCommand,String,int,long,boolean,boolean)」的上游调用点包括 「DisputeHearingWorkflowImpl.run」。
    // 下游影响：「DisputeHearingWorkflowImpl.runStage(HearingWorkflowCommand,String,int,long,boolean,boolean)」向下依次触达 「activities.runStage」、「activities.persistStageTrace」、「command.caseId」、「command.workflowId」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「DisputeHearingWorkflowImpl.runStage(HearingWorkflowCommand,String,int,long,boolean,boolean)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private HearingStageActivityResult runStage(
            HearingWorkflowCommand command,
            String stage,
            int round,
            long dossierVersion,
            boolean evidenceTimedOut,
            boolean finalConvergence) {
        HearingStageActivityResult result =
                activities.runStage(
                        command.caseId(),
                        command.workflowId(),
                        stage,
                        round,
                        dossierVersion,
                        evidenceTimedOut,
                        finalConvergence,
                        command.maxHearingRounds());
        activities.persistStageTrace(
                command.caseId(),
                command.workflowId(),
                stage,
                round,
                dossierVersion,
                result.outputVersion());
        return result;
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflowImpl.interrupt(HearingWorkflowCommand,long,boolean,String)」。
    // 具体功能：「DisputeHearingWorkflowImpl.interrupt(HearingWorkflowCommand,long,boolean,String)」：把 Activity 中断或不可恢复分支标记为 manualRequired，持久化当前卷宗版本与超时状态后返回人工接管结果，最终返回「HearingWorkflowResult」。
    // 上游调用：「DisputeHearingWorkflowImpl.interrupt(HearingWorkflowCommand,long,boolean,String)」的上游调用点包括 「DisputeHearingWorkflowImpl.run」。
    // 下游影响：「DisputeHearingWorkflowImpl.interrupt(HearingWorkflowCommand,long,boolean,String)」向下依次触达 「activities.complete」、「command.caseId」、「command.workflowId」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「DisputeHearingWorkflowImpl.interrupt(HearingWorkflowCommand,long,boolean,String)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private HearingWorkflowResult interrupt(
            HearingWorkflowCommand command,
            long dossierVersion,
            boolean evidenceTimedOut,
            String status,
            boolean failClosed) {
        if (failClosed) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "hearing stage did not produce a validated draft",
                    "HEARING_STAGE_VALIDATION_FAILED");
        }
        activities.complete(
                command.caseId(),
                command.workflowId(),
                status,
                true,
                evidenceTimedOut,
                dossierVersion,
                null);
        return new HearingWorkflowResult(
                null,
                true,
                evidenceTimedOut,
                dossierVersion,
                status,
                null);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」。
    // 具体功能：「DisputeHearingWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」：把补证 Signal 追加到 FIFO 队列；run 主循环在确定性位置统一记录并升级 dossierVersion，避免 Signal 回调直接做 I/O，最终返回「void」。
    // 上游调用：「DisputeHearingWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」由使用「DisputeHearingWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DisputeHearingWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」向下依次触达 「evidenceSignals.addLast」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「DisputeHearingWorkflowImpl.submitEvidence(EvidenceSubmissionSignal)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void submitEvidence(EvidenceSubmissionSignal signal) {
        evidenceSignals.addLast(signal);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflowImpl.hearingRoundCompleted(int,boolean)」。
    // 具体功能：「DisputeHearingWorkflowImpl.hearingRoundCompleted(int,boolean)」：以 Math.max 合并轮次完成 Signal，重复或乱序的较小 roundNo 不会让工作流进度倒退；finalRound 用于触发 C6 收敛，最终返回「void」。
    // 上游调用：「DisputeHearingWorkflowImpl.hearingRoundCompleted(int,boolean)」由使用「DisputeHearingWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DisputeHearingWorkflowImpl.hearingRoundCompleted(int,boolean)」向下依次触达 「Math.max」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「DisputeHearingWorkflowImpl.hearingRoundCompleted(int,boolean)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void hearingRoundCompleted(int roundNo, boolean factsSufficient) {
        completedHearingRounds = Math.max(completedHearingRounds, roundNo);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflowImpl.settlementConfirmed(int)」。
    // 具体功能：「DisputeHearingWorkflowImpl.settlementConfirmed(int)」：记录双方已确认的最高和解版本；只有同一版本的确认由上游服务汇总后才发送此 Signal，最终返回「void」。
    // 上游调用：「DisputeHearingWorkflowImpl.settlementConfirmed(int)」由使用「DisputeHearingWorkflowImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DisputeHearingWorkflowImpl.settlementConfirmed(int)」向下依次触达 「Math.max」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「DisputeHearingWorkflowImpl.settlementConfirmed(int)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    @Override
    public void settlementConfirmed(int settlementVersion) {
        confirmedSettlementVersion =
                Math.max(confirmedSettlementVersion, settlementVersion);
    }

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflowImpl.awaitSharedHearing(HearingWorkflowCommand)」。
    // 具体功能：「DisputeHearingWorkflowImpl.awaitSharedHearing(HearingWorkflowCommand)」：在剩余庭审时限内等待三种确定事件：同版本和解确认、达到最大轮次或截止超时，并返回 SETTLEMENT_CONFIRMED、MAX_ROUNDS 或 DEADLINE_EXPIRED 作为唯一停止原因，最终返回「String」。
    // 上游调用：「DisputeHearingWorkflowImpl.awaitSharedHearing(HearingWorkflowCommand)」的上游调用点包括 「DisputeHearingWorkflowImpl.run」。
    // 下游影响：「DisputeHearingWorkflowImpl.awaitSharedHearing(HearingWorkflowCommand)」向下依次触达 「Workflow.await」、「command.hearingWaitTimeout」、「command.maxHearingRounds」、「command.hearingWaitTimeout().isZero」；Activity 调度、Timer 和 Signal 条件会写入 Temporal history。
    // 系统意义：「DisputeHearingWorkflowImpl.awaitSharedHearing(HearingWorkflowCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：Temporal 的 Workflow.await/Timer 不是阻塞操作系统线程，而是把等待条件记录进可恢复 history。
    private String awaitSharedHearing(HearingWorkflowCommand command) {
        if (command.hearingWaitTimeout().isZero()
                || command.maxHearingRounds() < 1) {
            return null;
        }
        boolean stopped =
                Workflow.await(
                        command.hearingWaitTimeout(),
                        () ->
                                confirmedSettlementVersion > 0
                                        || completedHearingRounds
                                                >= command.maxHearingRounds());
        if (!stopped) return "DEADLINE_EXPIRED";
        if (confirmedSettlementVersion > 0) return "SETTLEMENT_CONFIRMED";
        return "MAX_ROUNDS";
    }
}

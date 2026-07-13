/*
 * 所属模块：共享小法庭。
 * 文件职责：协调庭审的事务提交、异步信号和阶段交接。
 * 业务链路：核心入口/契约为 「startAfterCommit」、「roundCompletedAfterCommit」、「roundCompletedNow」、「settlementConfirmedAfterCommit」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingWorkflowCoordinator」。
// 类型职责：协调庭审的事务提交、异步信号和阶段交接；本类型显式提供 「HearingWorkflowCoordinator」、「startAfterCommit」、「roundCompletedAfterCommit」、「roundCompletedNow」、「settlementConfirmedAfterCommit」、「signalAfterCommit」。
// 协作关系：主要由 「EvidenceCompletionService.complete」、「EvidenceCompletionService.expire」、「HearingCourtOrchestrator.finalizeResult」、「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class HearingWorkflowCoordinator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingWorkflowCoordinator.class);
    private final WorkflowClient workflowClient;
    private final AppProperties properties;
    private final DisputeProperties disputeProperties;
    private final PostCommitSideEffectExecutor postCommit;

    // 所属模块：【共享小法庭 / 应用编排层】「HearingWorkflowCoordinator.HearingWorkflowCoordinator(WorkflowClient,AppProperties,DisputeProperties,PostCommitSideEffectExecutor)」。
    // 具体功能：「HearingWorkflowCoordinator.HearingWorkflowCoordinator(WorkflowClient,AppProperties,DisputeProperties,PostCommitSideEffectExecutor)」：通过构造器接收 「workflowClient」(WorkflowClient)、「properties」(AppProperties)、「disputeProperties」(DisputeProperties)、「postCommit」(PostCommitSideEffectExecutor) 并保存为「HearingWorkflowCoordinator」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingWorkflowCoordinator.HearingWorkflowCoordinator(WorkflowClient,AppProperties,DisputeProperties,PostCommitSideEffectExecutor)」的上游创建点包括 「HearingWorkflowCoordinatorTest.coordinator」。
    // 下游影响：「HearingWorkflowCoordinator.HearingWorkflowCoordinator(WorkflowClient,AppProperties,DisputeProperties,PostCommitSideEffectExecutor)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingWorkflowCoordinator.HearingWorkflowCoordinator(WorkflowClient,AppProperties,DisputeProperties,PostCommitSideEffectExecutor)」负责主链路中的“庭审工作流协调器”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingWorkflowCoordinator(
            WorkflowClient workflowClient,
            AppProperties properties,
            DisputeProperties disputeProperties,
            PostCommitSideEffectExecutor postCommit) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.disputeProperties = disputeProperties;
        this.postCommit = postCommit;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingWorkflowCoordinator.startAfterCommit(String,int)」。
    // 具体功能：「HearingWorkflowCoordinator.startAfterCommit(String,int)」：启动之后提交：先按稳定 Workflow ID 获取或创建 Temporal 句柄；实际协作者为 「workflowClient.newWorkflowStub」、「WorkflowClient.start」、「WorkflowOptions.newBuilder」、「postCommit.execute」；处理的关键状态/协议值包括 「hearing-workflow-start」、「case_id」、「workflow_id」、「dossier_version」，最终返回「void」。
    // 上游调用：「HearingWorkflowCoordinator.startAfterCommit(String,int)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionService.expire」。
    // 下游影响：「HearingWorkflowCoordinator.startAfterCommit(String,int)」向下依次触达 「workflowClient.newWorkflowStub」、「WorkflowClient.start」、「WorkflowOptions.newBuilder」、「postCommit.execute」。
    // 系统意义：「HearingWorkflowCoordinator.startAfterCommit(String,int)」负责主链路中的“之后提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void startAfterCommit(String caseId, int dossierVersion) {
        postCommit.execute(
                "hearing-workflow-start",
                Map.of(
                        "case_id", caseId,
                        "workflow_id", workflowId(caseId),
                        "dossier_version", dossierVersion),
                () -> {
                    DisputeHearingWorkflow workflow =
                            workflowClient.newWorkflowStub(
                                    DisputeHearingWorkflow.class,
                                    WorkflowOptions.newBuilder()
                                            .setWorkflowId(workflowId(caseId))
                                            .setTaskQueue(properties.temporal().taskQueue())
                                            .build());
                    WorkflowClient.start(
                            workflow::run,
                            new HearingWorkflowCommand(
                                    caseId,
                                    workflowId(caseId),
                                    dossierVersion,
                                    disputeProperties.hearingWindow(),
                                    2,
                                    disputeProperties.hearingWindow(),
                                    disputeProperties.maxHearingRounds()));
                });
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingWorkflowCoordinator.roundCompletedAfterCommit(String,int,boolean)」。
    // 具体功能：「HearingWorkflowCoordinator.roundCompletedAfterCommit(String,int,boolean)」：执行轮次完成之后提交；实际协作者为 「workflow.hearingRoundCompleted」、「signalAfterCommit」，最终返回「void」。
    // 上游调用：「HearingWorkflowCoordinator.roundCompletedAfterCommit(String,int,boolean)」的上游调用点包括 「HearingCourtOrchestrator.finalizeResult」、「HearingRoundService.dispatchRoundClosedAfterCommit」。
    // 下游影响：「HearingWorkflowCoordinator.roundCompletedAfterCommit(String,int,boolean)」向下依次触达 「workflow.hearingRoundCompleted」、「signalAfterCommit」。
    // 系统意义：「HearingWorkflowCoordinator.roundCompletedAfterCommit(String,int,boolean)」负责主链路中的“轮次完成之后提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void roundCompletedAfterCommit(
            String caseId, int roundNo, boolean factsSufficient) {
        signalAfterCommit(
                caseId,
                workflow ->
                        workflow.hearingRoundCompleted(
                                roundNo, factsSufficient));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingWorkflowCoordinator.roundCompletedNow(String,int,boolean)」。
    // 具体功能：「HearingWorkflowCoordinator.roundCompletedNow(String,int,boolean)」：判断轮次完成Now；实际协作者为 「workflow.hearingRoundCompleted」、「signalNow」，最终返回「boolean」。
    // 上游调用：「HearingWorkflowCoordinator.roundCompletedNow(String,int,boolean)」的上游调用点包括 「HearingFinalRoundRecoveryService.recoverFinalRoundsWithoutDraft」、「HearingFinalRoundRecoveryServiceTest.repairsFormalJuryReportBeforeResignalingFinalRound」、「HearingFinalRoundRecoveryServiceTest.rotatesTheKeysetCursorSoAPermanentFailureCannotStarveTheNextCandidate」。
    // 下游影响：「HearingWorkflowCoordinator.roundCompletedNow(String,int,boolean)」向下依次触达 「workflow.hearingRoundCompleted」、「signalNow」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingWorkflowCoordinator.roundCompletedNow(String,int,boolean)」负责主链路中的“轮次完成Now”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public boolean roundCompletedNow(
            String caseId, int roundNo, boolean factsSufficient) {
        return signalNow(
                caseId,
                workflow ->
                        workflow.hearingRoundCompleted(
                                roundNo, factsSufficient));
    }

    /** Starts a new execution for a sealed final round after the previous execution terminated. */
    public boolean restartFinalConvergenceNow(
            String caseId, int dossierVersion, int finalRoundNo) {
        String workflowId = workflowId(caseId);
        DisputeHearingWorkflow startCandidate =
                workflowClient.newWorkflowStub(
                        DisputeHearingWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId)
                                .setWorkflowIdReusePolicy(
                                        WorkflowIdReusePolicy
                                                .WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                                .setTaskQueue(properties.temporal().taskQueue())
                                .build());
        DisputeHearingWorkflow workflow;
        try {
            WorkflowClient.start(
                    startCandidate::run,
                    new HearingWorkflowCommand(
                            caseId,
                            workflowId,
                            dossierVersion,
                            disputeProperties.hearingWindow(),
                            2,
                            disputeProperties.hearingWindow(),
                            disputeProperties.maxHearingRounds()));
            workflow = startCandidate;
        } catch (WorkflowExecutionAlreadyStarted active) {
            workflow =
                    workflowClient.newWorkflowStub(
                            DisputeHearingWorkflow.class, workflowId);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Failed to restart final hearing convergence: case_id={}, round_no={}",
                    caseId,
                    finalRoundNo,
                    failure);
            return false;
        }
        try {
            workflow.hearingRoundCompleted(finalRoundNo, false);
            return true;
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Failed to signal restarted final hearing convergence: case_id={}, round_no={}",
                    caseId,
                    finalRoundNo,
                    failure);
            return false;
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingWorkflowCoordinator.settlementConfirmedAfterCommit(String,int)」。
    // 具体功能：「HearingWorkflowCoordinator.settlementConfirmedAfterCommit(String,int)」：执行和解Confirmed之后提交；实际协作者为 「workflow.settlementConfirmed」、「signalAfterCommit」，最终返回「void」。
    // 上游调用：「HearingWorkflowCoordinator.settlementConfirmedAfterCommit(String,int)」的上游调用点包括 「SettlementService.confirm」。
    // 下游影响：「HearingWorkflowCoordinator.settlementConfirmedAfterCommit(String,int)」向下依次触达 「workflow.settlementConfirmed」、「signalAfterCommit」。
    // 系统意义：「HearingWorkflowCoordinator.settlementConfirmedAfterCommit(String,int)」负责主链路中的“和解Confirmed之后提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void settlementConfirmedAfterCommit(String caseId, int version) {
        signalAfterCommit(
                caseId, workflow -> workflow.settlementConfirmed(version));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingWorkflowCoordinator.signalAfterCommit(String,Consumer)」。
    // 具体功能：「HearingWorkflowCoordinator.signalAfterCommit(String,Consumer)」：发送信号之后提交；实际协作者为 「postCommit.execute」、「workflowId」、「signalNow」；处理的关键状态/协议值包括 「hearing-workflow-signal」、「case_id」、「workflow_id」，最终返回「void」。
    // 上游调用：「HearingWorkflowCoordinator.signalAfterCommit(String,Consumer)」的上游调用点包括 「HearingWorkflowCoordinator.roundCompletedAfterCommit」、「HearingWorkflowCoordinator.settlementConfirmedAfterCommit」。
    // 下游影响：「HearingWorkflowCoordinator.signalAfterCommit(String,Consumer)」向下依次触达 「postCommit.execute」、「workflowId」、「signalNow」。
    // 系统意义：「HearingWorkflowCoordinator.signalAfterCommit(String,Consumer)」负责主链路中的“之后提交”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void signalAfterCommit(
            String caseId,
            java.util.function.Consumer<DisputeHearingWorkflow> signal) {
        postCommit.execute(
                "hearing-workflow-signal",
                Map.of("case_id", caseId, "workflow_id", workflowId(caseId)),
                () -> signalNow(caseId, signal));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingWorkflowCoordinator.signalNow(String,Consumer)」。
    // 具体功能：「HearingWorkflowCoordinator.signalNow(String,Consumer)」：发送信号Now：先按稳定 Workflow ID 获取或创建 Temporal 句柄；实际协作者为 「workflowClient.newWorkflowStub」、「LOGGER.warn」、「signal.accept」、「workflowId」，最终返回「boolean」。
    // 上游调用：「HearingWorkflowCoordinator.signalNow(String,Consumer)」的上游调用点包括 「HearingWorkflowCoordinator.roundCompletedNow」、「HearingWorkflowCoordinator.signalAfterCommit」。
    // 下游影响：「HearingWorkflowCoordinator.signalNow(String,Consumer)」向下依次触达 「workflowClient.newWorkflowStub」、「LOGGER.warn」、「signal.accept」、「workflowId」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingWorkflowCoordinator.signalNow(String,Consumer)」负责主链路中的“Now”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private boolean signalNow(
            String caseId,
            java.util.function.Consumer<DisputeHearingWorkflow> signal) {
        try {
            signal.accept(
                    workflowClient.newWorkflowStub(
                            DisputeHearingWorkflow.class,
                            workflowId(caseId)));
            return true;
        } catch (WorkflowNotFoundException missing) {
            LOGGER.warn(
                    "Hearing workflow is not running: case_id={}",
                    caseId);
            return false;
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingWorkflowCoordinator.workflowId(String)」。
    // 具体功能：「HearingWorkflowCoordinator.workflowId(String)」：构建工作流标识；处理的关键状态/协议值包括 「hearing-window-」，最终返回「String」。
    // 上游调用：「HearingWorkflowCoordinator.workflowId(String)」的上游调用点包括 「HearingWorkflowCoordinator.startAfterCommit」、「HearingWorkflowCoordinator.signalAfterCommit」、「HearingWorkflowCoordinator.signalNow」。
    // 下游影响：「HearingWorkflowCoordinator.workflowId(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingWorkflowCoordinator.workflowId(String)」负责主链路中的“工作流标识”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String workflowId(String caseId) {
        return "hearing-window-" + caseId;
    }
}

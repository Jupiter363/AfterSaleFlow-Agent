/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：协调证据Window的事务提交、异步信号和阶段交接。
 * 业务链路：核心入口/契约为 「startAfterCommit」、「signalPartyCompletedAfterCommit」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.application;

import com.example.dispute.config.AppProperties;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

// 所属模块：【Temporal 持久化编排 / 应用编排层】类型「EvidenceWindowCoordinator」。
// 类型职责：协调证据Window的事务提交、异步信号和阶段交接；本类型显式提供 「EvidenceWindowCoordinator」、「startAfterCommit」、「signalPartyCompletedAfterCommit」、「workflowId」。
// 协作关系：主要由 「EvidenceCompletionService.complete」、「IntakeRoomService.confirm」、「EvidenceWindowCoordinatorTest.partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class EvidenceWindowCoordinator {

    private final WorkflowClient workflowClient;
    private final AppProperties properties;
    private final PostCommitSideEffectExecutor postCommit;

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「EvidenceWindowCoordinator.EvidenceWindowCoordinator(WorkflowClient,AppProperties,PostCommitSideEffectExecutor)」。
    // 具体功能：「EvidenceWindowCoordinator.EvidenceWindowCoordinator(WorkflowClient,AppProperties,PostCommitSideEffectExecutor)」：通过构造器接收 「workflowClient」(WorkflowClient)、「properties」(AppProperties)、「postCommit」(PostCommitSideEffectExecutor) 并保存为「EvidenceWindowCoordinator」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceWindowCoordinator.EvidenceWindowCoordinator(WorkflowClient,AppProperties,PostCommitSideEffectExecutor)」的上游创建点包括 「EvidenceWindowCoordinatorTest.partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest」。
    // 下游影响：「EvidenceWindowCoordinator.EvidenceWindowCoordinator(WorkflowClient,AppProperties,PostCommitSideEffectExecutor)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceWindowCoordinator.EvidenceWindowCoordinator(WorkflowClient,AppProperties,PostCommitSideEffectExecutor)」负责主链路中的“证据Window协调器”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceWindowCoordinator(
            WorkflowClient workflowClient,
            AppProperties properties,
            PostCommitSideEffectExecutor postCommit) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.postCommit = postCommit;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「EvidenceWindowCoordinator.startAfterCommit(String,Duration)」。
    // 具体功能：「EvidenceWindowCoordinator.startAfterCommit(String,Duration)」：在开放证据室的数据库事务提交后，以 evidence-window-{caseId} 作为稳定 Workflow ID 启动举证计时；页面重试或重复事件会命中同一实例而不是创建第二只时钟，最终返回「void」。
    // 上游调用：「EvidenceWindowCoordinator.startAfterCommit(String,Duration)」的上游调用点包括 「IntakeRoomService.confirm」。
    // 下游影响：「EvidenceWindowCoordinator.startAfterCommit(String,Duration)」向下依次触达 「workflowClient.newWorkflowStub」、「WorkflowClient.start」、「WorkflowOptions.newBuilder」、「postCommit.execute」。
    // 系统意义：「EvidenceWindowCoordinator.startAfterCommit(String,Duration)」负责主链路中的“之后提交”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public void startAfterCommit(String caseId, Duration window) {
        postCommit.execute(
                "evidence-window-start",
                Map.of("case_id", caseId, "workflow_id", workflowId(caseId)),
                () -> {
                    EvidenceWindowWorkflow workflow =
                            workflowClient.newWorkflowStub(
                                    EvidenceWindowWorkflow.class,
                                    WorkflowOptions.newBuilder()
                                            .setWorkflowId(workflowId(caseId))
                                            .setTaskQueue(properties.temporal().taskQueue())
                                            .build());
                    WorkflowClient.start(
                            workflow::run, new EvidenceWindowCommand(caseId, window));
                });
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「EvidenceWindowCoordinator.signalPartyCompletedAfterCommit(String,String)」。
    // 具体功能：「EvidenceWindowCoordinator.signalPartyCompletedAfterCommit(String,String)」：在完成确认落库后向稳定 Workflow ID 发送 partyCompleted Signal，避免 Temporal 先看到信号而查询不到数据库事实，最终返回「void」。
    // 上游调用：「EvidenceWindowCoordinator.signalPartyCompletedAfterCommit(String,String)」的上游调用点包括 「EvidenceCompletionService.complete」。
    // 下游影响：「EvidenceWindowCoordinator.signalPartyCompletedAfterCommit(String,String)」向下依次触达 「workflowClient.newWorkflowStub」、「postCommit.execute」、「workflowId」、「partyCompleted」。
    // 系统意义：「EvidenceWindowCoordinator.signalPartyCompletedAfterCommit(String,String)」负责主链路中的“当事方完成之后提交”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public void signalPartyCompletedAfterCommit(String caseId, String role) {
        postCommit.execute(
                "evidence-window-party-completed",
                Map.of(
                        "case_id", caseId,
                        "workflow_id", workflowId(caseId),
                        "role", role),
                () ->
                        workflowClient
                                .newWorkflowStub(
                                        EvidenceWindowWorkflow.class,
                                        workflowId(caseId))
                                .partyCompleted(role));
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「EvidenceWindowCoordinator.workflowId(String)」。
    // 具体功能：「EvidenceWindowCoordinator.workflowId(String)」：构建工作流标识；处理的关键状态/协议值包括 「evidence-window-」，最终返回「String」。
    // 上游调用：「EvidenceWindowCoordinator.workflowId(String)」的上游调用点包括 「EvidenceWindowCoordinator.startAfterCommit」、「EvidenceWindowCoordinator.signalPartyCompletedAfterCommit」。
    // 下游影响：「EvidenceWindowCoordinator.workflowId(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceWindowCoordinator.workflowId(String)」负责主链路中的“工作流标识”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static String workflowId(String caseId) {
        return "evidence-window-" + caseId;
    }
}

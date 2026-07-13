/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义庭审跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import java.time.Duration;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「HearingWorkflowCommand」。
// 类型职责：定义庭审跨层传递时使用的不可变数据契约；本类型显式提供 「HearingWorkflowCommand」。
// 协作关系：主要由 「FulfillmentDisputeWorkflowImpl.run」、「HearingWorkflowCoordinator.startAfterCommit」、「DisputeHearingWorkflowTest.command」、「DisputeHearingWorkflowTest.confirmedSettlementSkipsModelStagesAndCompletesDeterministically」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record HearingWorkflowCommand(
        String caseId,
        String workflowId,
        long dossierVersion,
        Duration evidenceWaitTimeout,
        int maxEvidenceRounds,
        Duration hearingWaitTimeout,
        int maxHearingRounds) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「HearingWorkflowCommand.HearingWorkflowCommand(String,String,long,Duration,int)」。
    // 具体功能：「HearingWorkflowCommand.HearingWorkflowCommand(String,String,long,Duration,int)」：使用 「caseId」(String)、「workflowId」(String)、「dossierVersion」(long)、「evidenceWaitTimeout」(Duration)、「maxEvidenceRounds」(int) 初始化「HearingWorkflowCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「HearingWorkflowCommand.HearingWorkflowCommand(String,String,long,Duration,int)」的上游创建点包括 「HearingWorkflowCoordinator.startAfterCommit」、「FulfillmentDisputeWorkflowImpl.run」、「DisputeHearingWorkflowTest.initializesHearingStateBeforeWaitingForStatementRounds」、「DisputeHearingWorkflowTest.threeHourDeadlineAlwaysConvergesThroughC6」。
    // 下游影响：「HearingWorkflowCommand.HearingWorkflowCommand(String,String,long,Duration,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingWorkflowCommand.HearingWorkflowCommand(String,String,long,Duration,int)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingWorkflowCommand(
            String caseId,
            String workflowId,
            long dossierVersion,
            Duration evidenceWaitTimeout,
            int maxEvidenceRounds) {
        this(
                caseId,
                workflowId,
                dossierVersion,
                evidenceWaitTimeout,
                maxEvidenceRounds,
                Duration.ZERO,
                0);
    }
}

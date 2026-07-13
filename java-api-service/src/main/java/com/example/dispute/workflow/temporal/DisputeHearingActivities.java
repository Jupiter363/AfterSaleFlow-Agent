/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义争议庭审可由 Temporal 重试的 Activity 契约。
 * 业务链路：核心入口/契约为 「initialize」、「runStage」、「recordEvidence」、「persistStageTrace」、「complete」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.HearingStageActivityResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「DisputeHearingActivities」。
// 类型职责：定义争议庭审可由 Temporal 重试的 Activity 契约；本类型显式提供 「initialize」、「runStage」、「recordEvidence」、「persistStageTrace」、「complete」。
// 协作关系：主要由 「DisputeHearingWorkflowImpl.interrupt」、「DisputeHearingWorkflowImpl.run」、「DisputeHearingWorkflowImpl.runStage」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@ActivityInterface
public interface DisputeHearingActivities {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingActivities.initialize(HearingWorkflowCommand)」。
    // 具体功能：「DisputeHearingActivities.initialize(HearingWorkflowCommand)」：定义「DisputeHearingActivities」端口方法：接收 「command」(HearingWorkflowCommand)，返回「void」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「DisputeHearingActivities.initialize(HearingWorkflowCommand)」的上游调用点包括 「DisputeHearingWorkflowImpl.run」。
    // 下游影响：「DisputeHearingActivities.initialize(HearingWorkflowCommand)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingActivities.initialize(HearingWorkflowCommand)」负责主链路中的“争议庭审”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void initialize(HearingWorkflowCommand command);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」。
    // 具体功能：「DisputeHearingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」：定义「DisputeHearingActivities」端口方法：接收 「caseId」(String)、「workflowId」(String)、「stage」(String)、「round」(int)、「dossierVersion」(long)、「evidenceTimedOut」(boolean)、「finalConvergence」(boolean)、「maxHearingRounds」(int)，返回「HearingStageActivityResult」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「DisputeHearingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」的上游调用点包括 「DisputeHearingWorkflowImpl.runStage」。
    // 下游影响：「DisputeHearingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingActivities.runStage(String,String,String,int,long,boolean,boolean,int)」负责主链路中的“Stage”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    HearingStageActivityResult runStage(
            String caseId,
            String workflowId,
            String stage,
            int round,
            long dossierVersion,
            boolean evidenceTimedOut,
            boolean finalConvergence,
            int maxHearingRounds);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingActivities.recordEvidence(EvidenceSubmissionSignal)」。
    // 具体功能：「DisputeHearingActivities.recordEvidence(EvidenceSubmissionSignal)」：定义「DisputeHearingActivities」端口方法：接收 「signal」(EvidenceSubmissionSignal)，返回「long」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「DisputeHearingActivities.recordEvidence(EvidenceSubmissionSignal)」的上游调用点包括 「DisputeHearingWorkflowImpl.run」。
    // 下游影响：「DisputeHearingActivities.recordEvidence(EvidenceSubmissionSignal)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingActivities.recordEvidence(EvidenceSubmissionSignal)」负责主链路中的“证据”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    long recordEvidence(EvidenceSubmissionSignal signal);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingActivities.persistStageTrace(String,String,String,int,long,String)」。
    // 具体功能：「DisputeHearingActivities.persistStageTrace(String,String,String,int,long,String)」：定义「DisputeHearingActivities」端口方法：接收 「caseId」(String)、「workflowId」(String)、「stage」(String)、「round」(int)、「dossierVersion」(long)、「outputVersion」(String)，返回「void」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「DisputeHearingActivities.persistStageTrace(String,String,String,int,long,String)」的上游调用点包括 「DisputeHearingWorkflowImpl.runStage」。
    // 下游影响：「DisputeHearingActivities.persistStageTrace(String,String,String,int,long,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingActivities.persistStageTrace(String,String,String,int,long,String)」负责主链路中的“Stage链路标识”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void persistStageTrace(
            String caseId,
            String workflowId,
            String stage,
            int round,
            long dossierVersion,
            String outputVersion);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingActivities.complete(String,String,String,boolean,boolean,long,String)」。
    // 具体功能：「DisputeHearingActivities.complete(String,String,String,boolean,boolean,long,String)」：定义「DisputeHearingActivities」端口方法：接收 「caseId」(String)、「workflowId」(String)、「status」(String)、「manualRequired」(boolean)、「evidenceTimedOut」(boolean)、「dossierVersion」(long)、「stopReason」(String)，返回「void」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「DisputeHearingActivities.complete(String,String,String,boolean,boolean,long,String)」的上游调用点包括 「DisputeHearingWorkflowImpl.run」、「DisputeHearingWorkflowImpl.interrupt」。
    // 下游影响：「DisputeHearingActivities.complete(String,String,String,boolean,boolean,long,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingActivities.complete(String,String,String,boolean,boolean,long,String)」负责主链路中的“争议庭审”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void complete(
            String caseId,
            String workflowId,
            String status,
            boolean manualRequired,
            boolean evidenceTimedOut,
            long dossierVersion,
            String stopReason);
}

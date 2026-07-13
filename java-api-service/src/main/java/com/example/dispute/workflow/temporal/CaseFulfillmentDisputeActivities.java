/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义案件履约争议可由 Temporal 重试的 Activity 契约。
 * 业务链路：核心入口/契约为 「initializeHearing」、「analyzeHearing」、「recordPartyEvidence」、「recordReviewerSignal」、「completeHearing」、「planRemedy」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「CaseFulfillmentDisputeActivities」。
// 类型职责：定义案件履约争议可由 Temporal 重试的 Activity 契约；本类型显式提供 「initializeHearing」、「analyzeHearing」、「recordPartyEvidence」、「recordReviewerSignal」、「completeHearing」、「planRemedy」。
// 协作关系：主要由 「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」、「CaseFulfillmentDisputeWorkflowImpl.drainSignals」、「CaseFulfillmentDisputeWorkflowImpl.run」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@ActivityInterface
public interface CaseFulfillmentDisputeActivities {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.initializeHearing(CaseWorkflowInput)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.initializeHearing(CaseWorkflowInput)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「input」(CaseWorkflowInput)，返回「void」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.initializeHearing(CaseWorkflowInput)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「CaseFulfillmentDisputeActivities.initializeHearing(CaseWorkflowInput)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.initializeHearing(CaseWorkflowInput)」负责主链路中的“庭审”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void initializeHearing(CaseWorkflowInput input);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.analyzeHearing(HearingAnalysisActivityCommand)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.analyzeHearing(HearingAnalysisActivityCommand)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「command」(HearingAnalysisActivityCommand)，返回「HearingAnalysisActivityResult」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.analyzeHearing(HearingAnalysisActivityCommand)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「CaseFulfillmentDisputeActivities.analyzeHearing(HearingAnalysisActivityCommand)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.analyzeHearing(HearingAnalysisActivityCommand)」负责主链路中的“庭审”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    HearingAnalysisActivityResult analyzeHearing(
            HearingAnalysisActivityCommand command);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.recordPartyEvidence(PartyEvidenceSignal)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.recordPartyEvidence(PartyEvidenceSignal)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「signal」(PartyEvidenceSignal)，返回「void」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.recordPartyEvidence(PartyEvidenceSignal)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.drainSignals」。
    // 下游影响：「CaseFulfillmentDisputeActivities.recordPartyEvidence(PartyEvidenceSignal)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.recordPartyEvidence(PartyEvidenceSignal)」负责主链路中的“当事方证据”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void recordPartyEvidence(PartyEvidenceSignal signal);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.recordReviewerSignal(ReviewerWorkflowSignal)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.recordReviewerSignal(ReviewerWorkflowSignal)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「signal」(ReviewerWorkflowSignal)，返回「void」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.recordReviewerSignal(ReviewerWorkflowSignal)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.drainSignals」、「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」。
    // 下游影响：「CaseFulfillmentDisputeActivities.recordReviewerSignal(ReviewerWorkflowSignal)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.recordReviewerSignal(ReviewerWorkflowSignal)」负责主链路中的“审核员信号”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void recordReviewerSignal(ReviewerWorkflowSignal signal);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.completeHearing(String,String,boolean,boolean)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.completeHearing(String,String,boolean,boolean)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「caseId」(String)、「workflowId」(String)、「manualRequired」(boolean)、「evidenceTimedOut」(boolean)，返回「void」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.completeHearing(String,String,boolean,boolean)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「CaseFulfillmentDisputeActivities.completeHearing(String,String,boolean,boolean)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.completeHearing(String,String,boolean,boolean)」负责主链路中的“庭审”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void completeHearing(
            String caseId,
            String workflowId,
            boolean manualRequired,
            boolean evidenceTimedOut);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.planRemedy(String,String)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.planRemedy(String,String)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「caseId」(String)、「workflowId」(String)，返回「String」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.planRemedy(String,String)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「CaseFulfillmentDisputeActivities.planRemedy(String,String)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.planRemedy(String,String)」负责主链路中的“补救”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    String planRemedy(String caseId, String workflowId);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.createReviewTask(String,String)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.createReviewTask(String,String)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「caseId」(String)、「remedyPlanId」(String)，返回「String」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.createReviewTask(String,String)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」。
    // 下游影响：「CaseFulfillmentDisputeActivities.createReviewTask(String,String)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.createReviewTask(String,String)」负责主链路中的“审核任务”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    String createReviewTask(String caseId, String remedyPlanId);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.executeApprovedPlan(String)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.executeApprovedPlan(String)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「caseId」(String)，返回「void」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.executeApprovedPlan(String)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」。
    // 下游影响：「CaseFulfillmentDisputeActivities.executeApprovedPlan(String)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.executeApprovedPlan(String)」负责主链路中的“已审批方案”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void executeApprovedPlan(String caseId);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeActivities.closeCaseAndEvaluate(String)」。
    // 具体功能：「CaseFulfillmentDisputeActivities.closeCaseAndEvaluate(String)」：定义「CaseFulfillmentDisputeActivities」端口方法：接收 「caseId」(String)，返回「void」；具体副作用由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 承担。
    // 上游调用：「CaseFulfillmentDisputeActivities.closeCaseAndEvaluate(String)」的上游调用点包括 「CaseFulfillmentDisputeWorkflowImpl.awaitHumanReview」。
    // 下游影响：「CaseFulfillmentDisputeActivities.closeCaseAndEvaluate(String)」的下游由 「CaseFulfillmentDisputeActivitiesImpl」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeActivities.closeCaseAndEvaluate(String)」负责主链路中的“案件并且Evaluate”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void closeCaseAndEvaluate(String caseId);
}

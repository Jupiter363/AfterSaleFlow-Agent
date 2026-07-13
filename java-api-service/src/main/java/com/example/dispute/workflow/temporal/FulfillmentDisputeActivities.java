/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义履约争议可由 Temporal 重试的 Activity 契约。
 * 业务链路：核心入口/契约为 「markTransferred」、「planRemedy」、「createReviewPacket」、「closeCaseAndEvaluate」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import com.example.dispute.workflow.domain.ReviewGateSnapshot;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「FulfillmentDisputeActivities」。
// 类型职责：定义履约争议可由 Temporal 重试的 Activity 契约；本类型显式提供 「markTransferred」、「planRemedy」、「createReviewPacket」、「closeCaseAndEvaluate」。
// 协作关系：主要由 「FulfillmentDisputeWorkflowImpl.run」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@ActivityInterface(namePrefix = "FinalFulfillment_")
public interface FulfillmentDisputeActivities {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeActivities.markTransferred(String,String)」。
    // 具体功能：「FulfillmentDisputeActivities.markTransferred(String,String)」：定义「FulfillmentDisputeActivities」端口方法：接收 「caseId」(String)、「workflowId」(String)，返回「void」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「FulfillmentDisputeActivities.markTransferred(String,String)」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeActivities.markTransferred(String,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「FulfillmentDisputeActivities.markTransferred(String,String)」负责主链路中的“Transferred”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void markTransferred(String caseId, String workflowId);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeActivities.planRemedy(String,String,String,String)」。
    // 具体功能：「FulfillmentDisputeActivities.planRemedy(String,String,String,String)」：定义「FulfillmentDisputeActivities」端口方法：接收 「caseId」(String)、「workflowId」(String)、「draftId」(String)、「deliberationId」(String)，返回「String」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「FulfillmentDisputeActivities.planRemedy(String,String,String,String)」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeActivities.planRemedy(String,String,String,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「FulfillmentDisputeActivities.planRemedy(String,String,String,String)」负责主链路中的“补救”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    String planRemedy(
            String caseId,
            String workflowId,
            String draftId,
            String deliberationId);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeActivities.createReviewPacket(String,String,String,String)」。
    // 具体功能：「FulfillmentDisputeActivities.createReviewPacket(String,String,String,String)」：定义「FulfillmentDisputeActivities」端口方法：接收 「caseId」(String)、「draftId」(String)、「deliberationId」(String)、「remedyPlanId」(String)，返回「ReviewGateSnapshot」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「FulfillmentDisputeActivities.createReviewPacket(String,String,String,String)」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeActivities.createReviewPacket(String,String,String,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「FulfillmentDisputeActivities.createReviewPacket(String,String,String,String)」负责主链路中的“审核审核包”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    ReviewGateSnapshot createReviewPacket(
            String caseId,
            String draftId,
            String deliberationId,
            String remedyPlanId);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「FulfillmentDisputeActivities.closeCaseAndEvaluate(String)」。
    // 具体功能：「FulfillmentDisputeActivities.closeCaseAndEvaluate(String)」：定义「FulfillmentDisputeActivities」端口方法：接收 「caseId」(String)，返回「void」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「FulfillmentDisputeActivities.closeCaseAndEvaluate(String)」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeActivities.closeCaseAndEvaluate(String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「FulfillmentDisputeActivities.closeCaseAndEvaluate(String)」负责主链路中的“案件并且Evaluate”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void closeCaseAndEvaluate(String caseId);
}

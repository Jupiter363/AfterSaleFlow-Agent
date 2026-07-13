/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义案件输入跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import com.example.dispute.domain.model.RouteType;
import java.time.Duration;
import java.util.Objects;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「CaseWorkflowInput」。
// 类型职责：定义案件输入跨层传递时使用的不可变数据契约；本类型显式提供 「CaseWorkflowInput」。
// 协作关系：主要由 「FinalWorkflowActivitiesAdapter.initialize」、「CaseFulfillmentDisputeWorkflowTest.input」、「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record CaseWorkflowInput(
        String caseId,
        String workflowId,
        RouteType routeType,
        Duration evidenceWaitTimeout,
        int maxEvidenceRounds) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「CaseWorkflowInput.CaseWorkflowInput(String,String,RouteType,Duration,int)」。
    // 具体功能：「CaseWorkflowInput.CaseWorkflowInput(String,String,RouteType,Duration,int)」：在不可变「CaseWorkflowInput」写入组件前校验 「caseId」(String)、「workflowId」(String)、「routeType」(RouteType)、「evidenceWaitTimeout」(Duration)、「maxEvidenceRounds」(int)，非法输入会抛出 「IllegalArgumentException」；并通过 「Objects.requireNonNull」、「evidenceWaitTimeout.isNegative」、「evidenceWaitTimeout.isZero」 做标准化或防御性复制。
    // 上游调用：「CaseWorkflowInput.CaseWorkflowInput(String,String,RouteType,Duration,int)」的上游创建点包括 「FinalWorkflowActivitiesAdapter.initialize」、「CaseFulfillmentDisputeWorkflowTest.regularAndRuleRoutesGoDirectlyThroughRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow」、「CaseFulfillmentDisputeWorkflowTest.input」。
    // 下游影响：「CaseWorkflowInput.CaseWorkflowInput(String,String,RouteType,Duration,int)」向下依次触达 「Objects.requireNonNull」、「evidenceWaitTimeout.isNegative」、「evidenceWaitTimeout.isZero」。
    // 系统意义：「CaseWorkflowInput.CaseWorkflowInput(String,String,RouteType,Duration,int)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public CaseWorkflowInput {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        Objects.requireNonNull(routeType, "routeType must not be null");
        Objects.requireNonNull(
                evidenceWaitTimeout, "evidenceWaitTimeout must not be null");
        if (evidenceWaitTimeout.isNegative() || evidenceWaitTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "evidenceWaitTimeout must be positive");
        }
        if (maxEvidenceRounds < 1 || maxEvidenceRounds > 5) {
            throw new IllegalArgumentException(
                    "maxEvidenceRounds must be between 1 and 5");
        }
    }
}

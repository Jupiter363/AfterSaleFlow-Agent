/*
 * 所属模块：确定性补救规划。
 * 文件职责：定义补救方案草案跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.List;

// 所属模块：【确定性补救规划 / 领域模型层】类型「RemedyPlanDraft」。
// 类型职责：定义补救方案草案跨层传递时使用的不可变数据契约；本类型显式提供 「RemedyPlanDraft」。
// 协作关系：主要由 「RemedyPlanner.plan」 使用。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RemedyPlanDraft(
        String sourceConclusionCode,
        String sourceDraftId,
        RiskLevel riskLevel,
        List<PlannedRemedyAction> actions,
        List<String> preconditions,
        List<String> notificationPlan,
        boolean requiresHumanReview) {

    // 所属模块：【确定性补救规划 / 领域模型层】「RemedyPlanDraft.RemedyPlanDraft(String,String,RiskLevel,List,List,List,boolean)」。
    // 具体功能：「RemedyPlanDraft.RemedyPlanDraft(String,String,RiskLevel,List,List,List,boolean)」：在不可变「RemedyPlanDraft」写入组件前校验 「sourceConclusionCode」(String)、「sourceDraftId」(String)、「riskLevel」(RiskLevel)、「actions」(List)、「preconditions」(List)、「notificationPlan」(List)、「requiresHumanReview」(boolean)，并统一规范 record 组件值。
    // 上游调用：「RemedyPlanDraft.RemedyPlanDraft(String,String,RiskLevel,List,List,List,boolean)」的上游创建点包括 「RemedyPlanner.plan」。
    // 下游影响：「RemedyPlanDraft.RemedyPlanDraft(String,String,RiskLevel,List,List,List,boolean)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RemedyPlanDraft.RemedyPlanDraft(String,String,RiskLevel,List,List,List,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public RemedyPlanDraft {
        actions = List.copyOf(actions);
        preconditions = List.copyOf(preconditions);
        notificationPlan = List.copyOf(notificationPlan);
    }
}

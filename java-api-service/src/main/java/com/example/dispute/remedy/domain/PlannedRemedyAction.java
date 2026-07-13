/*
 * 所属模块：确定性补救规划。
 * 文件职责：定义Planned补救动作跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.List;
import java.util.Map;

// 所属模块：【确定性补救规划 / 领域模型层】类型「PlannedRemedyAction」。
// 类型职责：定义Planned补救动作跨层传递时使用的不可变数据契约；本类型显式提供 「PlannedRemedyAction」。
// 协作关系：主要由 「RemedyPlanner.plan」、「RemedyControllerTest.returnsApprovalGatedPlanDto」 使用。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record PlannedRemedyAction(
        String actionType,
        Map<String, Object> parameters,
        String idempotencyKey,
        List<String> preconditions,
        RiskLevel riskLevel,
        boolean requiresApproval) {

    // 所属模块：【确定性补救规划 / 领域模型层】「PlannedRemedyAction.PlannedRemedyAction(String,Map,String,List,RiskLevel,boolean)」。
    // 具体功能：「PlannedRemedyAction.PlannedRemedyAction(String,Map,String,List,RiskLevel,boolean)」：在不可变「PlannedRemedyAction」写入组件前校验 「actionType」(String)、「parameters」(Map)、「idempotencyKey」(String)、「preconditions」(List)、「riskLevel」(RiskLevel)、「requiresApproval」(boolean)，并统一规范 record 组件值。
    // 上游调用：「PlannedRemedyAction.PlannedRemedyAction(String,Map,String,List,RiskLevel,boolean)」的上游创建点包括 「RemedyPlanner.plan」、「RemedyControllerTest.returnsApprovalGatedPlanDto」。
    // 下游影响：「PlannedRemedyAction.PlannedRemedyAction(String,Map,String,List,RiskLevel,boolean)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PlannedRemedyAction.PlannedRemedyAction(String,Map,String,List,RiskLevel,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public PlannedRemedyAction {
        parameters = Map.copyOf(parameters);
        preconditions = List.copyOf(preconditions);
    }
}

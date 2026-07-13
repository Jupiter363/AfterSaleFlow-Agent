/*
 * 所属模块：确定性补救规划。
 * 文件职责：定义补救Planning来源跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy.domain;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.util.List;
import java.util.Objects;

// 所属模块：【确定性补救规划 / 领域模型层】类型「RemedyPlanningSource」。
// 类型职责：定义补救Planning来源跨层传递时使用的不可变数据契约；本类型显式提供 「RemedyPlanningSource」。
// 协作关系：主要由 「RemedyApplicationService.generate」、「RemedyPlannerTest.mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction」、「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource」、「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating」 使用。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RemedyPlanningSource(
        String caseId,
        RouteType sourceRoute,
        RiskLevel caseRiskLevel,
        String sourceConclusionCode,
        List<String> recommendedActions,
        String draftId,
        String draftRecommendation,
        int planVersion) {

    // 所属模块：【确定性补救规划 / 领域模型层】「RemedyPlanningSource.RemedyPlanningSource(String,RouteType,RiskLevel,String,List,String,String,int)」。
    // 具体功能：「RemedyPlanningSource.RemedyPlanningSource(String,RouteType,RiskLevel,String,List,String,String,int)」：在不可变「RemedyPlanningSource」写入组件前校验 「caseId」(String)、「sourceRoute」(RouteType)、「caseRiskLevel」(RiskLevel)、「sourceConclusionCode」(String)、「recommendedActions」(List)、「draftId」(String)、「draftRecommendation」(String)、「planVersion」(int)，非法输入会抛出 「IllegalArgumentException」；并通过 「Objects.requireNonNull」 做标准化或防御性复制。
    // 上游调用：「RemedyPlanningSource.RemedyPlanningSource(String,RouteType,RiskLevel,String,List,String,String,int)」的上游创建点包括 「RemedyApplicationService.generate」、「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating」、「RemedyPlannerTest.mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions」、「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource」。
    // 下游影响：「RemedyPlanningSource.RemedyPlanningSource(String,RouteType,RiskLevel,String,List,String,String,int)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「RemedyPlanningSource.RemedyPlanningSource(String,RouteType,RiskLevel,String,List,String,String,int)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public RemedyPlanningSource {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        Objects.requireNonNull(sourceRoute, "sourceRoute must not be null");
        Objects.requireNonNull(caseRiskLevel, "caseRiskLevel must not be null");
        if (sourceConclusionCode == null || sourceConclusionCode.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceConclusionCode must not be blank");
        }
        recommendedActions =
                recommendedActions == null
                        ? List.of()
                        : List.copyOf(recommendedActions);
        if (planVersion < 1) {
            throw new IllegalArgumentException("planVersion must be positive");
        }
        if (sourceRoute == RouteType.FULL_HEARING
                && (draftId == null
                        || draftId.isBlank()
                        || draftRecommendation == null
                        || draftRecommendation.isBlank())) {
            throw new IllegalArgumentException(
                    "hearing source requires a non-final adjudication draft");
        }
    }
}

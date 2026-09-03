/*
 * 所属模块：确定性补救规划。
 * 文件职责：承载补救Planner在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「plan」；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy.domain;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 所属模块：【确定性补救规划 / 领域模型层】类型「RemedyPlanner」。
// 类型职责：承载补救Planner在当前业务模块中的规则与协作边界；本类型显式提供 「plan」、「fromDraft」、「normalize」、「actionRisk」、「preconditions」、「max」。
// 协作关系：主要由 「RemedyApplicationService.generate」、「RemedyPlannerTest.mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction」、「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource」、「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating」 使用。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class RemedyPlanner {

    private static final Set<String> HIGH_RISK_ACTIONS =
            Set.of(
                    "REFUND",
                    "RESHIP",
                    "REPLACE",
                    "CANCEL_ORDER",
                    "REJECT_AFTER_SALE",
                    "CLOSE_AFTER_SALE");

    // 所属模块：【确定性补救规划 / 领域模型层】「RemedyPlanner.plan(RemedyPlanningSource)」。
    // 具体功能：「RemedyPlanner.plan(RemedyPlanningSource)」：规划补救方案草案；实际协作者为 「source.sourceRoute」、「source.draftRecommendation」、「source.recommendedActions」、「source.caseRiskLevel」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「REMEDY:」、「:」、「CASE_NOT_CLOSED」、「PLAN_VERSION_CURRENT」，最终返回「RemedyPlanDraft」。
    // 上游调用：「RemedyPlanner.plan(RemedyPlanningSource)」的上游调用点包括 「RemedyApplicationService.generate」、「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating」、「RemedyPlannerTest.mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions」、「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource」。
    // 下游影响：「RemedyPlanner.plan(RemedyPlanningSource)」向下依次触达 「source.sourceRoute」、「source.draftRecommendation」、「source.recommendedActions」、「source.caseRiskLevel」；计算结果以「RemedyPlanDraft」交给调用方。
    // 系统意义：「RemedyPlanner.plan(RemedyPlanningSource)」负责主链路中的“补救方案草案”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public RemedyPlanDraft plan(RemedyPlanningSource source) {
        List<ActionSeed> seeds =
                source.sourceRoute() == RouteType.FULL_HEARING
                        ? List.of(fromDraft(source.draftRecommendation()))
                        : source.recommendedActions().stream()
                                .map(action -> new ActionSeed(action, Map.of()))
                                .toList();
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException(
                    "upstream conclusion has no recommended action");
        }
        List<PlannedRemedyAction> actions = new ArrayList<>();
        RiskLevel planRisk = source.caseRiskLevel();
        for (int index = 0; index < seeds.size(); index++) {
            ActionSeed seed = seeds.get(index);
            String actionType = normalize(seed.actionType());
            RiskLevel actionRisk = actionRisk(actionType);
            planRisk = max(planRisk, actionRisk);
            actions.add(
                    new PlannedRemedyAction(
                            actionType,
                            seed.parameters(),
                            "REMEDY:"
                                    + source.caseId()
                                    + ":"
                                    + source.planVersion()
                                    + ":"
                                    + index
                                    + ":"
                                    + actionType,
                            preconditions(actionType),
                            actionRisk,
                            true));
        }
        return new RemedyPlanDraft(
                source.sourceConclusionCode(),
                source.draftId(),
                planRisk,
                actions,
                List.of(
                        "CASE_NOT_CLOSED",
                        "PLAN_VERSION_CURRENT",
                        "PLATFORM_REVIEW_APPROVED"),
                List.of(
                        "NOTIFY_USER_AFTER_EXECUTION",
                        "NOTIFY_MERCHANT_AFTER_EXECUTION",
                        "AUDIT_EXECUTION_RESULT"),
                true);
    }

    // 所属模块：【确定性补救规划 / 领域模型层】「RemedyPlanner.fromDraft(String)」。
    // 具体功能：「RemedyPlanner.fromDraft(String)」：转换草案；处理的关键状态/协议值包括 「REFUND」、「RESHIP」、「RESEND」、「REPLACE」，最终返回「ActionSeed」。
    // 上游调用：「RemedyPlanner.fromDraft(String)」的上游调用点包括 「RemedyPlanner.plan」。
    // 下游影响：「RemedyPlanner.fromDraft(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActionSeed」交给调用方。
    // 系统意义：「RemedyPlanner.fromDraft(String)」统一“草案”的跨层表示，避免不同入口产生不兼容字段；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private static ActionSeed fromDraft(String recommendation) {
        String normalized = recommendation.toUpperCase(Locale.ROOT);
        String actionType;
        if (normalized.contains("REFUND")) {
            actionType = "REFUND";
        } else if (normalized.contains("RESHIP")
                || normalized.contains("RESEND")) {
            actionType = "RESHIP";
        } else if (normalized.contains("REPLACE")
                || normalized.contains("EXCHANGE")) {
            actionType = "REPLACE";
        } else if (recommendation.contains("退款")
                || recommendation.contains("退费")
                || recommendation.contains("返款")) {
            actionType = "REFUND";
        } else if (recommendation.contains("补发")
                || recommendation.contains("重发")
                || recommendation.contains("重新发")
                || recommendation.contains("再次发")) {
            actionType = "RESHIP";
        } else if (recommendation.contains("换货")
                || recommendation.contains("更换")
                || recommendation.contains("调换")) {
            actionType = "REPLACE";
        } else if (normalized.contains("REJECT")
                || normalized.contains("DENY")) {
            actionType = "REJECT_AFTER_SALE";
        } else {
            actionType = "CREATE_MANUAL_REVIEW_TICKET";
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("source_recommendation", recommendation);
        parameters.put("source_is_final_decision", false);
        return new ActionSeed(actionType, parameters);
    }

    // 所属模块：【确定性补救规划 / 领域模型层】「RemedyPlanner.normalize(String)」。
    // 具体功能：「RemedyPlanner.normalize(String)」：规范化字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「RemedyPlanner.normalize(String)」的上游调用点包括 「RemedyPlanner.plan」。
    // 下游影响：「RemedyPlanner.normalize(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RemedyPlanner.normalize(String)」负责主链路中的“字符串”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "recommended action must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    // 所属模块：【确定性补救规划 / 领域模型层】「RemedyPlanner.actionRisk(String)」。
    // 具体功能：「RemedyPlanner.actionRisk(String)」：构建动作风险；处理的关键状态/协议值包括 「CREATE_FULFILLMENT_REMINDER」，最终返回「RiskLevel」。
    // 上游调用：「RemedyPlanner.actionRisk(String)」的上游调用点包括 「RemedyPlanner.plan」。
    // 下游影响：「RemedyPlanner.actionRisk(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RiskLevel」交给调用方。
    // 系统意义：「RemedyPlanner.actionRisk(String)」负责主链路中的“动作风险”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private static RiskLevel actionRisk(String actionType) {
        if (HIGH_RISK_ACTIONS.contains(actionType)) {
            return RiskLevel.HIGH;
        }
        if ("CREATE_FULFILLMENT_REMINDER".equals(actionType)) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    // 所属模块：【确定性补救规划 / 领域模型层】「RemedyPlanner.preconditions(String)」。
    // 具体功能：「RemedyPlanner.preconditions(String)」：构建执行前置条件；处理的关键状态/协议值包括 「CASE_NOT_CLOSED」、「PLAN_VERSION_CURRENT」、「PLATFORM_REVIEW_APPROVED」、「REFUND」，最终返回「List<String>」。
    // 上游调用：「RemedyPlanner.preconditions(String)」的上游调用点包括 「RemedyPlanner.plan」。
    // 下游影响：「RemedyPlanner.preconditions(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「List<String>」交给调用方。
    // 系统意义：「RemedyPlanner.preconditions(String)」负责主链路中的“执行前置条件”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private static List<String> preconditions(String actionType) {
        List<String> conditions =
                new ArrayList<>(
                        List.of(
                                "CASE_NOT_CLOSED",
                                "PLAN_VERSION_CURRENT",
                                "PLATFORM_REVIEW_APPROVED"));
        switch (actionType) {
            case "REFUND" -> {
                conditions.add("PAYMENT_ELIGIBLE");
                conditions.add("REFUND_AMOUNT_RESOLVED");
            }
            case "CANCEL_ORDER" -> conditions.add("ORDER_CANCELLABLE");
            case "RESHIP", "REPLACE" -> conditions.add("INVENTORY_AVAILABLE");
            case "REJECT_AFTER_SALE", "CLOSE_AFTER_SALE" ->
                    conditions.add("REVIEW_DECISION_RECORDED");
            default -> conditions.add("TARGET_RESOURCE_AVAILABLE");
        }
        return List.copyOf(conditions);
    }

    // 所属模块：【确定性补救规划 / 领域模型层】「RemedyPlanner.max(RiskLevel,RiskLevel)」。
    // 具体功能：「RemedyPlanner.max(RiskLevel,RiskLevel)」：构建较高风险等级；实际协作者为 「left.ordinal」、「right.ordinal」，最终返回「RiskLevel」。
    // 上游调用：「RemedyPlanner.max(RiskLevel,RiskLevel)」的上游调用点包括 「RemedyPlanner.plan」。
    // 下游影响：「RemedyPlanner.max(RiskLevel,RiskLevel)」向下依次触达 「left.ordinal」、「right.ordinal」；计算结果以「RiskLevel」交给调用方。
    // 系统意义：「RemedyPlanner.max(RiskLevel,RiskLevel)」负责主链路中的“较高风险等级”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private static RiskLevel max(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    // 所属模块：【确定性补救规划 / 领域模型层】类型「ActionSeed」。
    // 类型职责：定义动作种子数据跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record ActionSeed(String actionType, Map<String, Object> parameters) {}
}

/*
 * 所属模块：确定性补救规划。
 * 文件职责：定义补救方案跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy.application;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.remedy.domain.PlannedRemedyAction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

// 所属模块：【确定性补救规划 / 应用编排层】类型「RemedyPlanView」。
// 类型职责：定义补救方案跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RemedyPlanView(
        String id,
        String caseId,
        String adjudicationDraftId,
        int planVersion,
        RouteType sourceRoute,
        String planStatus,
        RiskLevel riskLevel,
        BigDecimal totalAmount,
        String currency,
        List<PlannedRemedyAction> actions,
        List<String> preconditions,
        List<String> notificationPlan,
        boolean requiresHumanReview,
        OffsetDateTime createdAt) {}

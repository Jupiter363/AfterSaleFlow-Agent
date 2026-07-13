/*
 * 所属模块：争议路由应用层。
 * 文件职责：定义FlowConclusion跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；汇集案件、证据和规则上下文并选择常规、规则或听证路线。
 * 关键边界：路由只决定下一条处理路径，不拥有终审或工具执行权限
 */
package com.example.dispute.router.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.List;

// 所属模块：【争议路由应用层 / 应用编排层】类型「FlowConclusionView」。
// 类型职责：定义FlowConclusion跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record FlowConclusionView(
        String conclusionType,
        String conclusionStatus,
        String conclusionCode,
        String summary,
        List<String> recommendedActions,
        String policyRuleId,
        Integer policyVersion,
        RiskLevel riskLevel,
        boolean requiresRemedyPlanning,
        boolean requiresHumanReview) {}

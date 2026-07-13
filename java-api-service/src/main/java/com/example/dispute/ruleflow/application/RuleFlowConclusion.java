/*
 * 所属模块：规则明确路径。
 * 文件职责：定义规则FlowConclusion跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；处理可由明确政策条款覆盖的争议并输出带规则引用的阶段结论。
 * 关键边界：规则命中不等于自动批准，后续仍经过补救规划和人工终审
 */
package com.example.dispute.ruleflow.application;

import java.util.List;

// 所属模块：【规则明确路径 / 应用编排层】类型「RuleFlowConclusion」。
// 类型职责：定义规则FlowConclusion跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：规则命中不等于自动批准，后续仍经过补救规划和人工终审
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RuleFlowConclusion(
        String conclusionCode, String summary, List<String> recommendedActions) {}

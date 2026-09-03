/*
 * 所属模块：规则检索。
 * 文件职责：定义规则跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；读取适用政策规则并向路由、庭审和审核阶段提供可引用依据。
 * 关键边界：规则引用需要版本化，不能用模型生成文本替代正式规则事实
 */
package com.example.dispute.policy.application;

import java.time.OffsetDateTime;
import java.util.Map;

// 所属模块：【规则检索 / 应用编排层】类型「PolicyRuleView」。
// 类型职责：定义规则跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：规则引用需要版本化，不能用模型生成文本替代正式规则事实
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record PolicyRuleView(
        String id,
        String ruleCode,
        int ruleVersion,
        String ruleName,
        String ruleScope,
        String ruleStatus,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        int priority,
        Map<String, Object> conditions,
        Map<String, Object> outcome,
        Map<String, Object> sourceDocument) {}

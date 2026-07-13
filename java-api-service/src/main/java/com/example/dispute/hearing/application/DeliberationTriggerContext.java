/*
 * 所属模块：共享小法庭。
 * 文件职责：定义评议Trigger上下文跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.domain.model.RiskLevel;

// 所属模块：【共享小法庭 / 应用编排层】类型「DeliberationTriggerContext」。
// 类型职责：定义评议Trigger上下文跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record DeliberationTriggerContext(
        RiskLevel riskLevel,
        boolean settlementConfirmed,
        double confidence,
        boolean majorEvidenceConflict,
        boolean ruleUncertain) {}

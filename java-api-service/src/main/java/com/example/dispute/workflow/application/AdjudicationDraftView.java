/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义Adjudication草案跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.application;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;

// 所属模块：【Temporal 持久化编排 / 应用编排层】类型「AdjudicationDraftView」。
// 类型职责：定义Adjudication草案跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record AdjudicationDraftView(
        String draftId,
        String caseId,
        int draftVersion,
        String recommendedDecision,
        BigDecimal confidence,
        String draftText,
        JsonNode factFindings,
        JsonNode evidenceAssessment,
        JsonNode policyApplication,
        JsonNode reviewerAttention,
        String draftStatus) {}

/*
 * 所属模块：结案与离线评估。
 * 文件职责：定义评估Report跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；关闭已执行案件并调用评估 Agent 生成质量指标和离线报告。
 * 关键边界：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
 */
package com.example.dispute.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

// 所属模块：【结案与离线评估 / 应用编排层】类型「EvaluationReportView」。
// 类型职责：定义评估Report跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvaluationReportView(
        String evaluationTraceId,
        String caseId,
        int evaluationVersion,
        String evaluationStatus,
        String evaluatorModel,
        String promptVersion,
        JsonNode metricScores,
        JsonNode findings,
        JsonNode report,
        Long latencyMs,
        Integer tokenUsage,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt) {}

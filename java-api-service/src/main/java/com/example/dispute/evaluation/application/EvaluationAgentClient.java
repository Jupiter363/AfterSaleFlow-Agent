/*
 * 所属模块：结案与离线评估。
 * 文件职责：定义评估Agent外部调用端口，使应用层不依赖具体 HTTP 实现。
 * 业务链路：核心入口/契约为 「analyze」；关闭已执行案件并调用评估 Agent 生成质量指标和离线报告。
 * 关键边界：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
 */
package com.example.dispute.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;

// 所属模块：【结案与离线评估 / 应用编排层】类型「EvaluationAgentClient」。
// 类型职责：定义评估Agent外部调用端口，使应用层不依赖具体 HTTP 实现；本类型显式提供 「analyze」。
// 协作关系：主要由 「CaseClosureService.close」、「CaseClosureServiceIntegrationTest.configureAgent」 使用。
// 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvaluationAgentClient {

    // 所属模块：【结案与离线评估 / 应用编排层】「EvaluationAgentClient.analyze(JsonNode,String,String)」。
    // 具体功能：「EvaluationAgentClient.analyze(JsonNode,String,String)」：定义「EvaluationAgentClient」端口方法：接收 「closedCaseSnapshot」(JsonNode)、「traceId」(String)、「requestId」(String)，返回「EvaluationAgentResult」；具体副作用由 「RestClientEvaluationAgentClient」 承担。
    // 上游调用：「EvaluationAgentClient.analyze(JsonNode,String,String)」的上游调用点包括 「CaseClosureService.close」、「CaseClosureServiceIntegrationTest.configureAgent」。
    // 下游影响：「EvaluationAgentClient.analyze(JsonNode,String,String)」的下游由 「RestClientEvaluationAgentClient」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvaluationAgentClient.analyze(JsonNode,String,String)」负责主链路中的“评估Agent”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    EvaluationAgentResult analyze(
            JsonNode closedCaseSnapshot, String traceId, String requestId);
}

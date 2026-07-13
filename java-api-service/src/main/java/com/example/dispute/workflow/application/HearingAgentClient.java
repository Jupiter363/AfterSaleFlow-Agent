/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义庭审Agent外部调用端口，使应用层不依赖具体 HTTP 实现。
 * 业务链路：核心入口/契约为 「analyze」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;

// 所属模块：【Temporal 持久化编排 / 应用编排层】类型「HearingAgentClient」。
// 类型职责：定义庭审Agent外部调用端口，使应用层不依赖具体 HTTP 实现；本类型显式提供 「analyze」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface HearingAgentClient {
    // 所属模块：【Temporal 持久化编排 / 应用编排层】「HearingAgentClient.analyze(JsonNode,String,String)」。
    // 具体功能：「HearingAgentClient.analyze(JsonNode,String,String)」：定义「HearingAgentClient」端口方法：接收 「request」(JsonNode)、「traceId」(String)、「requestId」(String)，返回「HearingAgentResult」；具体副作用由 「RestClientHearingAgentClient」 承担。
    // 上游调用：「HearingAgentClient.analyze(JsonNode,String,String)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」。
    // 下游影响：「HearingAgentClient.analyze(JsonNode,String,String)」的下游由 「RestClientHearingAgentClient」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingAgentClient.analyze(JsonNode,String,String)」负责主链路中的“庭审Agent”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    HearingAgentResult analyze(
            JsonNode request, String traceId, String requestId);
}

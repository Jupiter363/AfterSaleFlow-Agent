/*
 * 所属模块：Agent 流式运行。
 * 文件职责：定义Agent流外部调用端口，使应用层不依赖具体 HTTP 实现。
 * 业务链路：核心入口/契约为 「stream」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import java.util.function.Consumer;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentStreamClient」。
// 类型职责：定义Agent流外部调用端口，使应用层不依赖具体 HTTP 实现；本类型显式提供 「stream」。
// 协作关系：主要由 「AgentRunWorker.execute」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface AgentStreamClient {

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」。
    // 具体功能：「AgentStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」：定义「AgentStreamClient」端口方法：接收 「run」(AgentRunExecutionDescriptor)、「sink」(Consumer)，返回「void」；具体副作用由 「AgentNdjsonStreamClient」 承担。
    // 上游调用：「AgentStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」的上游调用点包括 「AgentRunWorker.execute」。
    // 下游影响：「AgentStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」的下游由 「AgentNdjsonStreamClient」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「AgentStreamClient.stream(AgentRunExecutionDescriptor,Consumer)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    void stream(AgentRunExecutionDescriptor run, Consumer<AgentStreamFrame> sink);
}

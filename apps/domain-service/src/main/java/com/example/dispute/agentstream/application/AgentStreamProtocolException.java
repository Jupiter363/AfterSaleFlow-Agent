/*
 * 所属模块：Agent 流式运行。
 * 文件职责：表达Agent流协议失败语义，使上层能够区分业务拒绝、协议错误和可重试故障。
 * 业务链路：该文件主要提供类型或包级契约；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentStreamProtocolException」。
// 类型职责：表达Agent流协议失败语义，使上层能够区分业务拒绝、协议错误和可重试故障；本类型显式提供 「AgentStreamProtocolException」、「AgentStreamProtocolException」。
// 协作关系：主要由 「AgentNdjsonStreamClient.errorFrame」、「AgentNdjsonStreamClient.finalFrame」、「AgentNdjsonStreamClient.parse」、「AgentNdjsonStreamClient.requireText」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class AgentStreamProtocolException extends RuntimeException {

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamProtocolException.AgentStreamProtocolException(String)」。
    // 具体功能：「AgentStreamProtocolException.AgentStreamProtocolException(String)」：把 「message」(String) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「AgentStreamProtocolException.AgentStreamProtocolException(String)」的上游创建点包括 「AgentRunLifecycleService.recordNonTerminalFrame」、「AgentRunResultPolicy.validate」、「AgentRunResultPolicy.requireText」、「AgentRunResultPolicy.requireObject」。
    // 下游影响：「AgentStreamProtocolException.AgentStreamProtocolException(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentStreamProtocolException.AgentStreamProtocolException(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentStreamProtocolException(String message) {
        super(message);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamProtocolException.AgentStreamProtocolException(String,Throwable)」。
    // 具体功能：「AgentStreamProtocolException.AgentStreamProtocolException(String,Throwable)」：把 「message」(String)、「cause」(Throwable) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「AgentStreamProtocolException.AgentStreamProtocolException(String,Throwable)」的上游创建点包括 「AgentRunLifecycleService.recordNonTerminalFrame」、「AgentRunResultPolicy.validate」、「AgentRunResultPolicy.requireText」、「AgentRunResultPolicy.requireObject」。
    // 下游影响：「AgentStreamProtocolException.AgentStreamProtocolException(String,Throwable)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentStreamProtocolException.AgentStreamProtocolException(String,Throwable)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentStreamProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

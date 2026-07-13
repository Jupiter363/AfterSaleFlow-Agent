/*
 * 所属模块：Agent 流式运行。
 * 文件职责：表达Agent流传输失败语义，使上层能够区分业务拒绝、协议错误和可重试故障。
 * 业务链路：核心入口/契约为 「retryable」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentStreamTransportException」。
// 类型职责：表达Agent流传输失败语义，使上层能够区分业务拒绝、协议错误和可重试故障；本类型显式提供 「AgentStreamTransportException」、「AgentStreamTransportException」、「retryable」。
// 协作关系：主要由 「AgentNdjsonStreamClient.stream」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public class AgentStreamTransportException extends RuntimeException {

    private final boolean retryable;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamTransportException.AgentStreamTransportException(String,boolean)」。
    // 具体功能：「AgentStreamTransportException.AgentStreamTransportException(String,boolean)」：把 「message」(String)、「retryable」(boolean) 交给父异常保存错误链，同时保留「retryable」供后台任务决定是否允许重试；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「AgentStreamTransportException.AgentStreamTransportException(String,boolean)」的上游创建点包括 「AgentNdjsonStreamClient.stream」。
    // 下游影响：「AgentStreamTransportException.AgentStreamTransportException(String,boolean)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentStreamTransportException.AgentStreamTransportException(String,boolean)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentStreamTransportException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamTransportException.AgentStreamTransportException(String,boolean,Throwable)」。
    // 具体功能：「AgentStreamTransportException.AgentStreamTransportException(String,boolean,Throwable)」：把 「message」(String)、「retryable」(boolean)、「cause」(Throwable) 交给父异常保存错误链，同时保留「retryable」供后台任务决定是否允许重试；构造过程不执行日志、重试或业务补偿。
    // 上游调用：「AgentStreamTransportException.AgentStreamTransportException(String,boolean,Throwable)」的上游创建点包括 「AgentNdjsonStreamClient.stream」。
    // 下游影响：「AgentStreamTransportException.AgentStreamTransportException(String,boolean,Throwable)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentStreamTransportException.AgentStreamTransportException(String,boolean,Throwable)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentStreamTransportException(
            String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamTransportException.retryable()」。
    // 具体功能：「AgentStreamTransportException.retryable()」：重试可重试标志，最终返回「boolean」。
    // 上游调用：「AgentStreamTransportException.retryable()」由使用「AgentStreamTransportException」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentStreamTransportException.retryable()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「AgentStreamTransportException.retryable()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public boolean retryable() {
        return retryable;
    }
}

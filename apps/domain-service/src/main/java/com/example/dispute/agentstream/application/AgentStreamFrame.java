/*
 * 所属模块：Agent 流式运行。
 * 文件职责：定义Agent流帧跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.fasterxml.jackson.databind.JsonNode;

/** Strictly validated event received from Python. */
// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentStreamFrame」。
// 类型职责：定义Agent流帧跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record AgentStreamFrame(
        long sequence,
        String event,
        String nodeName,
        String fieldPath,
        String delta,
        JsonNode usage,
        String model,
        Long latencyMs,
        JsonNode result,
        StreamError error) {

    // 所属模块：【Agent 流式运行 / 应用编排层】类型「StreamError」。
    // 类型职责：定义流错误跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record StreamError(
            String code,
            String message,
            boolean retryable,
            boolean visibleOutputEmitted) {}
}

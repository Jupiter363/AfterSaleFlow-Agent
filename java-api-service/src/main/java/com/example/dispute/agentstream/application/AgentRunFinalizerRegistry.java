/*
 * 所属模块：Agent 流式运行。
 * 文件职责：维护Agent运行终态处理器白名单并按稳定键解析实现。
 * 业务链路：核心入口/契约为 「finalizeResult」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.stereotype.Component;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunFinalizerRegistry」。
// 类型职责：维护Agent运行终态处理器白名单并按稳定键解析实现；本类型显式提供 「AgentRunFinalizerRegistry」、「finalizeResult」。
// 协作关系：主要由 「AgentRunLifecycleService.complete」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class AgentRunFinalizerRegistry {

    private final List<AgentRunFinalizer> finalizers;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunFinalizerRegistry.AgentRunFinalizerRegistry(List)」。
    // 具体功能：「AgentRunFinalizerRegistry.AgentRunFinalizerRegistry(List)」：通过构造器接收 「finalizers」(List) 并保存为「AgentRunFinalizerRegistry」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunFinalizerRegistry.AgentRunFinalizerRegistry(List)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「AgentRunFinalizerRegistry.AgentRunFinalizerRegistry(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunFinalizerRegistry.AgentRunFinalizerRegistry(List)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunFinalizerRegistry(List<AgentRunFinalizer> finalizers) {
        this.finalizers = List.copyOf(finalizers);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunFinalizerRegistry.finalizeResult(AgentRunFinalizationContext,JsonNode)」。
    // 具体功能：「AgentRunFinalizerRegistry.finalizeResult(AgentRunFinalizationContext,JsonNode)」：执行finalize结果；实际协作者为 「finalizer.supports」、「context.operation」、「matches.getFirst」、「matches.getFirst().finalizeResult」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「AgentRunFinalizerRegistry.finalizeResult(AgentRunFinalizationContext,JsonNode)」的上游调用点包括 「AgentRunLifecycleService.complete」。
    // 下游影响：「AgentRunFinalizerRegistry.finalizeResult(AgentRunFinalizationContext,JsonNode)」向下依次触达 「finalizer.supports」、「context.operation」、「matches.getFirst」、「matches.getFirst().finalizeResult」。
    // 系统意义：「AgentRunFinalizerRegistry.finalizeResult(AgentRunFinalizationContext,JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public void finalizeResult(
            AgentRunFinalizationContext context, JsonNode result) {
        List<AgentRunFinalizer> matches =
                finalizers.stream()
                        .filter(finalizer -> finalizer.supports(context.operation()))
                        .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "multiple AgentRunFinalizers registered for " + context.operation());
        }
        if (!matches.isEmpty()) {
            matches.getFirst().finalizeResult(context, result);
        }
    }
}

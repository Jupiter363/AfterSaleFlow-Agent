/*
 * 所属模块：执行工具目录。
 * 文件职责：维护工具白名单并按稳定键解析实现。
 * 业务链路：核心入口/契约为 「definitions」、「execute」；注册可调用工具、暴露内部只读目录并提供本地模拟执行适配器。
 * 关键边界：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
 */
package com.example.dispute.tool.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// 所属模块：【执行工具目录 / 应用编排层】类型「ToolRegistry」。
// 类型职责：维护工具白名单并按稳定键解析实现；本类型显式提供 「ToolRegistry」、「definitions」、「execute」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.executionToolDeclarations」、「InternalToolCatalogController.executionTools」、「ToolExecutorService.executeAction」、「ActiveCourtroomContextAssemblerTest.setUp」 使用。
// 边界意义：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class ToolRegistry {

    private final List<ToolAdapter> adapters;

    // 所属模块：【执行工具目录 / 应用编排层】「ToolRegistry.ToolRegistry(List)」。
    // 具体功能：「ToolRegistry.ToolRegistry(List)」：通过构造器接收 「adapters」(List) 并保存为「ToolRegistry」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ToolRegistry.ToolRegistry(List)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「HearingPersistenceIntegrationTest.activeContextAssembler」 显式创建。
    // 下游影响：「ToolRegistry.ToolRegistry(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ToolRegistry.ToolRegistry(List)」负责主链路中的“工具注册表”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ToolRegistry(List<ToolAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    // 所属模块：【执行工具目录 / 应用编排层】「ToolRegistry.definitions()」。
    // 具体功能：「ToolRegistry.definitions()」：构建定义列表；实际协作者为 「adapter.definitions」，最终返回「List<ToolDefinition>」。
    // 上游调用：「ToolRegistry.definitions()」的上游调用点包括 「ActiveCourtroomContextAssembler.executionToolDeclarations」、「InternalToolCatalogController.executionTools」、「ToolRegistryTest.exposesAgentFacingToolDefinitionsWithoutGrantingExecutionAuthority」、「ActiveCourtroomContextAssemblerTest.setUp」。
    // 下游影响：「ToolRegistry.definitions()」向下依次触达 「adapter.definitions」；计算结果以「List<ToolDefinition>」交给调用方。
    // 系统意义：「ToolRegistry.definitions()」负责主链路中的“定义列表”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public List<ToolDefinition> definitions() {
        return adapters.stream()
                .flatMap(adapter -> adapter.definitions().stream())
                .toList();
    }

    // 所属模块：【执行工具目录 / 应用编排层】「ToolRegistry.execute(ExecutableAction)」。
    // 具体功能：「ToolRegistry.execute(ExecutableAction)」：执行工具执行：先把 Optional 空值转换为明确业务异常；实际协作者为 「adapter.supports」、「action.actionType」、「execute」、「findFirst」；处理的关键状态/协议值包括 「action_type」，最终返回「ToolExecutionResult」。
    // 上游调用：「ToolRegistry.execute(ExecutableAction)」的上游调用点包括 「ToolExecutorService.executeAction」、「ToolRegistryTest.routesApprovedActionsThroughTheMatchingToolAdapter」、「ToolRegistryTest.rejectsActionsWithoutARegisteredAdapter」。
    // 下游影响：「ToolRegistry.execute(ExecutableAction)」向下依次触达 「adapter.supports」、「action.actionType」、「execute」、「findFirst」；计算结果以「ToolExecutionResult」交给调用方。
    // 系统意义：「ToolRegistry.execute(ExecutableAction)」负责主链路中的“工具执行”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public ToolExecutionResult execute(ExecutableAction action) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(action.actionType()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new ToolExecutionException(
                                        ErrorCode.TOOL_EXECUTION_DENIED,
                                        "no registered tool adapter for approved action",
                                        Map.of("action_type", action.actionType())))
                .execute(action);
    }
}

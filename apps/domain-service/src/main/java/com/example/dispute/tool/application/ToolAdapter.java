/*
 * 所属模块：执行工具目录。
 * 文件职责：定义工具的模块端口，隔离调用方与具体实现。
 * 业务链路：核心入口/契约为 「definitions」、「supports」、「execute」；注册可调用工具、暴露内部只读目录并提供本地模拟执行适配器。
 * 关键边界：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
 */
package com.example.dispute.tool.application;

import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;
import java.util.List;

// 所属模块：【执行工具目录 / 应用编排层】类型「ToolAdapter」。
// 类型职责：定义工具的模块端口，隔离调用方与具体实现；本类型显式提供 「definitions」、「supports」、「execute」。
// 协作关系：由 「SimulatedExecutionTool」 实现。
// 边界意义：工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface ToolAdapter {

    // 所属模块：【执行工具目录 / 应用编排层】「ToolAdapter.definitions()」。
    // 具体功能：「ToolAdapter.definitions()」：定义「ToolAdapter」端口方法：接收 无显式入参，返回「List<ToolDefinition>」；具体副作用由 「SimulatedExecutionTool」 承担。
    // 上游调用：「ToolAdapter.definitions()」由使用「ToolAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ToolAdapter.definitions()」的下游由 「SimulatedExecutionTool」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ToolAdapter.definitions()」负责主链路中的“定义列表”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<ToolDefinition> definitions();

    // 所属模块：【执行工具目录 / 应用编排层】「ToolAdapter.supports(String)」。
    // 具体功能：「ToolAdapter.supports(String)」：定义「ToolAdapter」端口方法：接收 「actionType」(String)，返回「boolean」；具体副作用由 「SimulatedExecutionTool」 承担。
    // 上游调用：「ToolAdapter.supports(String)」由使用「ToolAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ToolAdapter.supports(String)」的下游由 「SimulatedExecutionTool」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ToolAdapter.supports(String)」负责主链路中的“”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    boolean supports(String actionType);

    // 所属模块：【执行工具目录 / 应用编排层】「ToolAdapter.execute(ExecutableAction)」。
    // 具体功能：「ToolAdapter.execute(ExecutableAction)」：定义「ToolAdapter」端口方法：接收 「action」(ExecutableAction)，返回「ToolExecutionResult」；具体副作用由 「SimulatedExecutionTool」 承担。
    // 上游调用：「ToolAdapter.execute(ExecutableAction)」由使用「ToolAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ToolAdapter.execute(ExecutableAction)」的下游由 「SimulatedExecutionTool」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ToolAdapter.execute(ExecutableAction)」负责主链路中的“工具执行”；工具必须白名单注册且参数受 Schema 约束，不能执行任意模型文本
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    ToolExecutionResult execute(ExecutableAction action);
}

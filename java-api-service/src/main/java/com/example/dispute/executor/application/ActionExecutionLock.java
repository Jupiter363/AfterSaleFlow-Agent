/*
 * 所属模块：确定性工具执行。
 * 文件职责：定义动作执行Lock的模块端口，隔离调用方与具体实现。
 * 业务链路：核心入口/契约为 「acquire」、「release」；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor.application;

// 所属模块：【确定性工具执行 / 应用编排层】类型「ActionExecutionLock」。
// 类型职责：定义动作执行Lock的模块端口，隔离调用方与具体实现；本类型显式提供 「acquire」、「release」。
// 协作关系：主要由 「ToolExecutorService.executeApprovedActions」、「ToolExecutorServiceIntegrationTest.configureExecutionLock」 使用。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface ActionExecutionLock {

    // 所属模块：【确定性工具执行 / 应用编排层】「ActionExecutionLock.acquire(String)」。
    // 具体功能：「ActionExecutionLock.acquire(String)」：定义「ActionExecutionLock」端口方法：接收 「idempotencyKey」(String)，返回「String」；具体副作用由 「RedisActionExecutionLock」 承担。
    // 上游调用：「ActionExecutionLock.acquire(String)」的上游调用点包括 「ToolExecutorService.executeApprovedActions」、「ToolExecutorServiceIntegrationTest.configureExecutionLock」。
    // 下游影响：「ActionExecutionLock.acquire(String)」的下游由 「RedisActionExecutionLock」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ActionExecutionLock.acquire(String)」负责主链路中的“字符串”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    String acquire(String idempotencyKey);

    // 所属模块：【确定性工具执行 / 应用编排层】「ActionExecutionLock.release(String,String)」。
    // 具体功能：「ActionExecutionLock.release(String,String)」：定义「ActionExecutionLock」端口方法：接收 「idempotencyKey」(String)、「ownerToken」(String)，返回「void」；具体副作用由 「RedisActionExecutionLock」 承担。
    // 上游调用：「ActionExecutionLock.release(String,String)」的上游调用点包括 「ToolExecutorService.executeApprovedActions」。
    // 下游影响：「ActionExecutionLock.release(String,String)」的下游由 「RedisActionExecutionLock」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ActionExecutionLock.release(String,String)」负责主链路中的“动作执行Lock”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    void release(String idempotencyKey, String ownerToken);
}

/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义外部争议模拟外部调用端口，使应用层不依赖具体 HTTP 实现。
 * 业务链路：核心入口/契约为 「simulate」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import java.util.List;

// 所属模块：【案件核心与导入 / 应用编排层】类型「ExternalDisputeSimulationClient」。
// 类型职责：定义外部争议模拟外部调用端口，使应用层不依赖具体 HTTP 实现；本类型显式提供 「simulate」。
// 协作关系：由 「RestClientExternalDisputeSimulationClient」 实现。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface ExternalDisputeSimulationClient {

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalDisputeSimulationClient.simulate(SimulateExternalImportCommand,String,String)」。
    // 具体功能：「ExternalDisputeSimulationClient.simulate(SimulateExternalImportCommand,String,String)」：定义「ExternalDisputeSimulationClient」端口方法：接收 「command」(SimulateExternalImportCommand)、「traceId」(String)、「requestId」(String)，返回「List<SimulatedExternalDispute>」；具体副作用由 「RestClientExternalDisputeSimulationClient」 承担。
    // 上游调用：「ExternalDisputeSimulationClient.simulate(SimulateExternalImportCommand,String,String)」由使用「ExternalDisputeSimulationClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ExternalDisputeSimulationClient.simulate(SimulateExternalImportCommand,String,String)」的下游由 「RestClientExternalDisputeSimulationClient」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ExternalDisputeSimulationClient.simulate(SimulateExternalImportCommand,String,String)」负责主链路中的“列表”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<SimulatedExternalDispute> simulate(
            SimulateExternalImportCommand command,
            String traceId,
            String requestId);
}

/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义演示案件案件清理Store的模块端口，隔离调用方与具体实现。
 * 业务链路：核心入口/契约为 「purge」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

// 所属模块：【案件核心与导入 / 应用编排层】类型「DemoCasePurgeStore」。
// 类型职责：定义演示案件案件清理Store的模块端口，隔离调用方与具体实现；本类型显式提供 「purge」。
// 协作关系：主要由 「DemoCasePurgeService.purge」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface DemoCasePurgeStore {

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoCasePurgeStore.purge(String,String,String)」。
    // 具体功能：「DemoCasePurgeStore.purge(String,String,String)」：定义「DemoCasePurgeStore」端口方法：接收 「caseId」(String)、「reviewerId」(String)、「reviewerRole」(String)，返回「void」；具体副作用由 「JdbcDemoCasePurgeStore」 承担。
    // 上游调用：「DemoCasePurgeStore.purge(String,String,String)」的上游调用点包括 「DemoCasePurgeService.purge」。
    // 下游影响：「DemoCasePurgeStore.purge(String,String,String)」的下游由 「JdbcDemoCasePurgeStore」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DemoCasePurgeStore.purge(String,String,String)」负责主链路中的“演示案件案件清理Store”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    void purge(String caseId, String reviewerId, String reviewerRole);
}

/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义模拟导入跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import java.util.List;

// 所属模块：【案件核心与导入 / 应用编排层】类型「SimulatedImportResultView」。
// 类型职责：定义模拟导入跨层传递时使用的不可变数据契约；本类型显式提供 「SimulatedImportResultView」。
// 协作关系：主要由 「ExternalCaseImportTransactionService.simulateExternalImport」、「DisputeControllerTest.simulatesExternalImportFromThePublicDemoExperience」、「DisputeControllerTest.simulatesExternalImportThroughTheInternalServiceBoundary」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record SimulatedImportResultView(List<ImportedDisputeView> items) {

    // 所属模块：【案件核心与导入 / 应用编排层】「SimulatedImportResultView.SimulatedImportResultView(List)」。
    // 具体功能：「SimulatedImportResultView.SimulatedImportResultView(List)」：在不可变「SimulatedImportResultView」写入组件前校验 「items」(List)，并统一规范 record 组件值。
    // 上游调用：「SimulatedImportResultView.SimulatedImportResultView(List)」的上游创建点包括 「ExternalCaseImportTransactionService.simulateExternalImport」、「DisputeControllerTest.simulatesExternalImportThroughTheInternalServiceBoundary」、「DisputeControllerTest.simulatesExternalImportFromThePublicDemoExperience」。
    // 下游影响：「SimulatedImportResultView.SimulatedImportResultView(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SimulatedImportResultView.SimulatedImportResultView(List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public SimulatedImportResultView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}

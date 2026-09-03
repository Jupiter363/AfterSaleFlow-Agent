/*
 * 所属模块：确定性工具执行。
 * 文件职责：定义执行批次跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor.application;

import java.util.List;

// 所属模块：【确定性工具执行 / 应用编排层】类型「ExecutionBatchView」。
// 类型职责：定义执行批次跨层传递时使用的不可变数据契约；本类型显式提供 「ExecutionBatchView」。
// 协作关系：主要由 「ToolExecutorService.executeApprovedActions」、「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords」 使用。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ExecutionBatchView(
        String caseId,
        String planId,
        String approvalRecordId,
        boolean allSucceeded,
        List<ActionRecordView> actions) {

    // 所属模块：【确定性工具执行 / 应用编排层】「ExecutionBatchView.ExecutionBatchView(String,String,String,boolean,List)」。
    // 具体功能：「ExecutionBatchView.ExecutionBatchView(String,String,String,boolean,List)」：在不可变「ExecutionBatchView」写入组件前校验 「caseId」(String)、「planId」(String)、「approvalRecordId」(String)、「allSucceeded」(boolean)、「actions」(List)，并统一规范 record 组件值。
    // 上游调用：「ExecutionBatchView.ExecutionBatchView(String,String,String,boolean,List)」的上游创建点包括 「ToolExecutorService.executeApprovedActions」、「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords」。
    // 下游影响：「ExecutionBatchView.ExecutionBatchView(String,String,String,boolean,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ExecutionBatchView.ExecutionBatchView(String,String,String,boolean,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ExecutionBatchView {
        actions = List.copyOf(actions);
    }
}

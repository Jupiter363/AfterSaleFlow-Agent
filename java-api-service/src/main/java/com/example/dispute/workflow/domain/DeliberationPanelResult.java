/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义评议Panel跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import java.util.List;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「DeliberationPanelResult」。
// 类型职责：定义评议Panel跨层传递时使用的不可变数据契约；本类型显式提供 「DeliberationPanelResult」。
// 协作关系：主要由 「DeliberationPanelWorkflowImpl.run」、「StubPanelWorkflow.run」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record DeliberationPanelResult(
        String deliberationId,
        String panelResult,
        boolean revisionRequired,
        boolean manualRequired,
        List<String> majorObjections,
        List<String> unavailableCritics) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPanelResult.DeliberationPanelResult(String,String,boolean,boolean,List,List)」。
    // 具体功能：「DeliberationPanelResult.DeliberationPanelResult(String,String,boolean,boolean,List,List)」：在不可变「DeliberationPanelResult」写入组件前校验 「deliberationId」(String)、「panelResult」(String)、「revisionRequired」(boolean)、「manualRequired」(boolean)、「majorObjections」(List)、「unavailableCritics」(List)，并统一规范 record 组件值。
    // 上游调用：「DeliberationPanelResult.DeliberationPanelResult(String,String,boolean,boolean,List,List)」的上游创建点包括 「DeliberationPanelWorkflowImpl.run」、「StubPanelWorkflow.run」。
    // 下游影响：「DeliberationPanelResult.DeliberationPanelResult(String,String,boolean,boolean,List,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DeliberationPanelResult.DeliberationPanelResult(String,String,boolean,boolean,List,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public DeliberationPanelResult {
        majorObjections =
                majorObjections == null ? List.of() : List.copyOf(majorObjections);
        unavailableCritics =
                unavailableCritics == null
                        ? List.of()
                        : List.copyOf(unavailableCritics);
    }
}

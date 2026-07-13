/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：限定评议InterventionMode允许出现的状态值。
 * 业务链路：核心入口/契约为 「from」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import java.util.Locale;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「DeliberationInterventionMode」。
// 类型职责：限定评议InterventionMode允许出现的状态值；本类型显式提供 「from」。
// 协作关系：主要由 「WorkflowApplicationService.WorkflowApplicationService」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum DeliberationInterventionMode {
    DISABLED,
    FINAL_ONLY,
    EVERY_ROUND;

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationInterventionMode.from(String)」。
    // 具体功能：「DeliberationInterventionMode.from(String)」：转换评议InterventionMode；实际协作者为 「value.trim().toUpperCase(Locale.ROOT).replace」，最终返回「DeliberationInterventionMode」。
    // 上游调用：「DeliberationInterventionMode.from(String)」的上游调用点包括 「WorkflowApplicationService.WorkflowApplicationService」。
    // 下游影响：「DeliberationInterventionMode.from(String)」向下依次触达 「value.trim().toUpperCase(Locale.ROOT).replace」；计算结果以「DeliberationInterventionMode」交给调用方。
    // 系统意义：「DeliberationInterventionMode.from(String)」统一“评议InterventionMode”的跨层表示，避免不同入口产生不兼容字段；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public static DeliberationInterventionMode from(String value) {
        if (value == null || value.isBlank()) {
            return FINAL_ONLY;
        }
        return DeliberationInterventionMode.valueOf(
                value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}

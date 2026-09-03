/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义证据Window跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import java.time.Duration;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「EvidenceWindowCommand」。
// 类型职责：定义证据Window跨层传递时使用的不可变数据契约；本类型显式提供 「EvidenceWindowCommand」。
// 协作关系：主要由 「EvidenceWindowCoordinator.startAfterCommit」、「EvidenceWindowWorkflowTest.oneAbsentPartyCausesExpiryAfterTwoVirtualHours」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvidenceWindowCommand(String caseId, Duration window) {
    // 所属模块：【Temporal 持久化编排 / 领域模型层】「EvidenceWindowCommand.EvidenceWindowCommand(String,Duration)」。
    // 具体功能：「EvidenceWindowCommand.EvidenceWindowCommand(String,Duration)」：在不可变「EvidenceWindowCommand」写入组件前校验 「caseId」(String)、「window」(Duration)，非法输入会抛出 「IllegalArgumentException」；并通过 「window.isZero」、「window.isNegative」 做标准化或防御性复制。
    // 上游调用：「EvidenceWindowCommand.EvidenceWindowCommand(String,Duration)」的上游创建点包括 「EvidenceWindowCoordinator.startAfterCommit」、「EvidenceWindowWorkflowTest.oneAbsentPartyCausesExpiryAfterTwoVirtualHours」。
    // 下游影响：「EvidenceWindowCommand.EvidenceWindowCommand(String,Duration)」向下依次触达 「window.isZero」、「window.isNegative」。
    // 系统意义：「EvidenceWindowCommand.EvidenceWindowCommand(String,Duration)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public EvidenceWindowCommand {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }
}

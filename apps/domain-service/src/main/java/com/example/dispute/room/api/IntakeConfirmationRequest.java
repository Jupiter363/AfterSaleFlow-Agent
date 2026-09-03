/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义接待Confirmation跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.api;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 所属模块：【房间协作与权限 / HTTP 接口层】类型「IntakeConfirmationRequest」。
// 类型职责：定义接待Confirmation跨层传递时使用的不可变数据契约；本类型显式提供 「toCommand」。
// 协作关系：主要由 「IntakeRoomController.confirm」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record IntakeConfirmationRequest(
        boolean admissible,
        @NotBlank String disputeType,
        @NotNull RiskLevel riskLevel,
        String confirmationNote) {

    // 所属模块：【房间协作与权限 / HTTP 接口层】「IntakeConfirmationRequest.toCommand()」。
    // 具体功能：「IntakeConfirmationRequest.toCommand()」：转换命令，最终返回「IntakeConfirmationCommand」。
    // 上游调用：「IntakeConfirmationRequest.toCommand()」的上游调用点包括 「IntakeRoomController.confirm」。
    // 下游影响：「IntakeConfirmationRequest.toCommand()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「IntakeConfirmationCommand」交给调用方。
    // 系统意义：「IntakeConfirmationRequest.toCommand()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    IntakeConfirmationCommand toCommand() {
        return new IntakeConfirmationCommand(
                admissible, disputeType, riskLevel, confirmationNote);
    }
}

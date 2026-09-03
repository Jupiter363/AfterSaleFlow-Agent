/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义房间消息跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.api;

import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.domain.MessageType;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// 所属模块：【房间协作与权限 / HTTP 接口层】类型「RoomMessageRequest」。
// 类型职责：定义房间消息跨层传递时使用的不可变数据契约；本类型显式提供 「toCommand」。
// 协作关系：主要由 「RoomController.post」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RoomMessageRequest(
        @NotNull MessageType messageType,
        String text,
        List<String> attachmentRefs) {

    // 所属模块：【房间协作与权限 / HTTP 接口层】「RoomMessageRequest.toCommand()」。
    // 具体功能：「RoomMessageRequest.toCommand()」：转换命令，最终返回「RoomMessageCommand」。
    // 上游调用：「RoomMessageRequest.toCommand()」的上游调用点包括 「RoomController.post」。
    // 下游影响：「RoomMessageRequest.toCommand()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RoomMessageCommand」交给调用方。
    // 系统意义：「RoomMessageRequest.toCommand()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    RoomMessageCommand toCommand() {
        return new RoomMessageCommand(messageType, text, attachmentRefs);
    }
}

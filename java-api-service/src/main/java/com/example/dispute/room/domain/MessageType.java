/*
 * 所属模块：房间协作与权限。
 * 文件职责：限定消息类型允许出现的状态值。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.domain;

// 所属模块：【房间协作与权限 / 领域模型层】类型「MessageType」。
// 类型职责：限定消息类型允许出现的状态值；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum MessageType {
    PARTY_TEXT,
    PARTY_EVIDENCE_REFERENCE,
    PARTY_CONFIRMATION,
    AGENT_MESSAGE,
    JURY_REVIEW_REPORT,
    SYSTEM_EVENT,
    REVIEWER_NOTE
}

/*
 * 所属模块：案件生命周期通知。
 * 文件职责：限定通知类型允许出现的状态值。
 * 业务链路：该文件主要提供类型或包级契约；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.domain;

// 所属模块：【案件生命周期通知 / 领域模型层】类型「NotificationType」。
// 类型职责：限定通知类型允许出现的状态值；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum NotificationType {
    INTAKE_ACCEPTED,
    DISPUTE_SUMMONS,
    EVIDENCE_ROOM_OPENED,
    EVIDENCE_DEADLINE_WARNING,
    HEARING_OPENED,
    SUPPLEMENT_REQUESTED,
    SETTLEMENT_CONFIRMATION_REQUIRED,
    REVIEW_PENDING,
    FINAL_DECISION,
    EXECUTION_COMPLETED,
    MANUAL_HANDOFF
}

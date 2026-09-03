/*
 * 所属模块：案件生命周期通知。
 * 文件职责：定义通知跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.notification.domain.NotificationType;
import java.util.Objects;

// 所属模块：【案件生命周期通知 / 应用编排层】类型「NotificationCommand」。
// 类型职责：定义通知跨层传递时使用的不可变数据契约；本类型显式提供 「NotificationCommand」、「requireText」。
// 协作关系：主要由 「CaseLifecycleNotificationService.send」、「EvidenceCompletionService.sendHearingNotice」、「IntakeRoomService.sendSummonsTo」、「SettlementService.sendConfirmationNotice」 使用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record NotificationCommand(
        String caseId,
        String businessEventKey,
        String recipientId,
        ActorRole recipientRole,
        NotificationType notificationType,
        String title,
        String body,
        String deepLink,
        String payloadJson) {

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationCommand.NotificationCommand(String,String,String,ActorRole,NotificationType,String,String,String,String)」。
    // 具体功能：「NotificationCommand.NotificationCommand(String,String,String,ActorRole,NotificationType,String,String,String,String)」：在不可变「NotificationCommand」写入组件前校验 「caseId」(String)、「businessEventKey」(String)、「recipientId」(String)、「recipientRole」(ActorRole)、「notificationType」(NotificationType)、「title」(String)、「body」(String)、「deepLink」(String)、「payloadJson」(String)，并通过 「Objects.requireNonNull」、「requireText」 做标准化或防御性复制。
    // 上游调用：「NotificationCommand.NotificationCommand(String,String,String,ActorRole,NotificationType,String,String,String,String)」的上游创建点包括 「EvidenceCompletionService.sendHearingNotice」、「SettlementService.sendConfirmationNotice」、「CaseLifecycleNotificationService.send」、「IntakeRoomService.sendSummonsTo」。
    // 下游影响：「NotificationCommand.NotificationCommand(String,String,String,ActorRole,NotificationType,String,String,String,String)」向下依次触达 「Objects.requireNonNull」、「requireText」。
    // 系统意义：「NotificationCommand.NotificationCommand(String,String,String,ActorRole,NotificationType,String,String,String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public NotificationCommand {
        requireText(caseId, "caseId");
        requireText(businessEventKey, "businessEventKey");
        requireText(recipientId, "recipientId");
        Objects.requireNonNull(recipientRole, "recipientRole must not be null");
        Objects.requireNonNull(notificationType, "notificationType must not be null");
        requireText(title, "title");
        requireText(body, "body");
        requireText(deepLink, "deepLink");
        requireText(payloadJson, "payloadJson");
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationCommand.requireText(String,String)」。
    // 具体功能：「NotificationCommand.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「NotificationCommand.requireText(String,String)」的上游调用点包括 「NotificationCommand.NotificationCommand」。
    // 下游影响：「NotificationCommand.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「NotificationCommand.requireText(String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

/*
 * 所属模块：案件生命周期通知。
 * 文件职责：定义通知跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.notification.domain.NotificationType;
import java.time.Instant;

// 所属模块：【案件生命周期通知 / 应用编排层】类型「NotificationView」。
// 类型职责：定义通知跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record NotificationView(
        String id,
        String caseId,
        String recipientId,
        ActorRole recipientRole,
        NotificationType notificationType,
        String title,
        String body,
        String deepLink,
        boolean read,
        Instant createdAt,
        Instant readAt) {}

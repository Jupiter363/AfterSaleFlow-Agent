/*
 * 所属模块：案件生命周期通知。
 * 文件职责：映射通知数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「markRead」、「dismiss」、「getCaseId」、「getBusinessEventKey」、「getRecipientId」；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.notification.domain.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【案件生命周期通知 / JPA 实体层】类型「NotificationEntity」。
// 类型职责：映射通知数据库记录并保存可审计状态；本类型显式提供 「NotificationEntity」、「NotificationEntity」、「create」、「markRead」、「dismiss」、「getCaseId」。
// 协作关系：主要由 「NotificationService.create」、「NotificationService.view」、「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead」、「NotificationServiceTest.notification」 使用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "notification")
public class NotificationEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "business_event_key", length = 128, nullable = false)
    private String businessEventKey;

    @Column(name = "recipient_id", length = 128, nullable = false)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_role", length = 32, nullable = false)
    private ActorRole recipientRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", length = 64, nullable = false)
    private NotificationType notificationType;

    @Column(name = "title", length = 256, nullable = false)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "deep_link", length = 512, nullable = false)
    private String deepLink;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.NotificationEntity()」。
    // 具体功能：「NotificationEntity.NotificationEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「NotificationEntity.NotificationEntity()」的上游创建点包括 「NotificationEntity.create」。
    // 下游影响：「NotificationEntity.NotificationEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「NotificationEntity.NotificationEntity()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected NotificationEntity() {}

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.NotificationEntity(String)」。
    // 具体功能：「NotificationEntity.NotificationEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「NotificationEntity.NotificationEntity(String)」的上游创建点包括 「NotificationEntity.create」。
    // 下游影响：「NotificationEntity.NotificationEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「NotificationEntity.NotificationEntity(String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private NotificationEntity(String id) {
        super(id);
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.create(String,String,String,String,ActorRole,NotificationType,String,String,String,String,Instant)」。
    // 具体功能：「NotificationEntity.create(String,String,String,String,ActorRole,NotificationType,String,String,String,String,Instant)」：创建通知：先更新内部状态 「caseId」、「businessEventKey」、「recipientId」、「recipientRole」；实际协作者为 「Objects.requireNonNull」、「required」；处理的关键状态/协议值包括 「id」、「caseId」、「businessEventKey」、「recipientId」，最终返回「NotificationEntity」。
    // 上游调用：「NotificationEntity.create(String,String,String,String,ActorRole,NotificationType,String,String,String,String,Instant)」的上游调用点包括 「NotificationService.create」、「NotificationServiceTest.replayingTheSameBusinessEventReturnsTheExistingInboxMessage」、「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead」、「NotificationServiceTest.notification」。
    // 下游影响：「NotificationEntity.create(String,String,String,String,ActorRole,NotificationType,String,String,String,String,Instant)」向下依次触达 「Objects.requireNonNull」、「required」；计算结果以「NotificationEntity」交给调用方。
    // 系统意义：「NotificationEntity.create(String,String,String,String,ActorRole,NotificationType,String,String,String,String,Instant)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public static NotificationEntity create(
            String id,
            String caseId,
            String businessEventKey,
            String recipientId,
            ActorRole recipientRole,
            NotificationType notificationType,
            String title,
            String body,
            String deepLink,
            String payloadJson,
            Instant createdAt) {
        NotificationEntity entity = new NotificationEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.businessEventKey = required(businessEventKey, "businessEventKey");
        entity.recipientId = required(recipientId, "recipientId");
        entity.recipientRole =
                Objects.requireNonNull(recipientRole, "recipientRole must not be null");
        entity.notificationType =
                Objects.requireNonNull(notificationType, "notificationType must not be null");
        entity.title = required(title, "title");
        entity.body = required(body, "body");
        entity.deepLink = required(deepLink, "deepLink");
        entity.payloadJson = required(payloadJson, "payloadJson");
        entity.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        return entity;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.markRead(Instant)」。
    // 具体功能：「NotificationEntity.markRead(Instant)」：标记Read：先更新内部状态 「readAt」；实际协作者为 「Objects.requireNonNull」，最终返回「void」。
    // 上游调用：「NotificationEntity.markRead(Instant)」由使用「NotificationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「NotificationEntity.markRead(Instant)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「NotificationEntity.markRead(Instant)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = Objects.requireNonNull(now, "now must not be null");
        }
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.dismiss(Instant)」。
    // 具体功能：「NotificationEntity.dismiss(Instant)」：更新dismiss：先更新内部状态 「dismissedAt」；实际协作者为 「Objects.requireNonNull」，最终返回「void」。
    // 上游调用：「NotificationEntity.dismiss(Instant)」由使用「NotificationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「NotificationEntity.dismiss(Instant)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「NotificationEntity.dismiss(Instant)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public void dismiss(Instant now) {
        if (dismissedAt == null) {
            dismissedAt = Objects.requireNonNull(now, "now must not be null");
        }
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getCaseId()」。
    // 具体功能：「NotificationEntity.getCaseId()」：读取「NotificationEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「NotificationEntity.getCaseId()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「NotificationEntity.getCaseId()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getBusinessEventKey()」。
    // 具体功能：「NotificationEntity.getBusinessEventKey()」：读取「NotificationEntity」中的「businessEventKey」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「NotificationEntity.getBusinessEventKey()」由使用「NotificationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「NotificationEntity.getBusinessEventKey()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「NotificationEntity.getBusinessEventKey()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public String getBusinessEventKey() {
        return businessEventKey;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getRecipientId()」。
    // 具体功能：「NotificationEntity.getRecipientId()」：读取「NotificationEntity」中的「recipientId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「NotificationEntity.getRecipientId()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getRecipientId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「NotificationEntity.getRecipientId()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public String getRecipientId() {
        return recipientId;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getRecipientRole()」。
    // 具体功能：「NotificationEntity.getRecipientRole()」：读取「NotificationEntity」中的「recipientRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「NotificationEntity.getRecipientRole()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getRecipientRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「NotificationEntity.getRecipientRole()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public ActorRole getRecipientRole() {
        return recipientRole;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getNotificationType()」。
    // 具体功能：「NotificationEntity.getNotificationType()」：读取「NotificationEntity」中的「notificationType」状态，向 JPA、应用服务或序列化层返回「NotificationType」。
    // 上游调用：「NotificationEntity.getNotificationType()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getNotificationType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「NotificationType」交给调用方。
    // 系统意义：「NotificationEntity.getNotificationType()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public NotificationType getNotificationType() {
        return notificationType;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getTitle()」。
    // 具体功能：「NotificationEntity.getTitle()」：读取「NotificationEntity」中的「title」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「NotificationEntity.getTitle()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getTitle()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「NotificationEntity.getTitle()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public String getTitle() {
        return title;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getBody()」。
    // 具体功能：「NotificationEntity.getBody()」：读取「NotificationEntity」中的「body」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「NotificationEntity.getBody()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getBody()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「NotificationEntity.getBody()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public String getBody() {
        return body;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getDeepLink()」。
    // 具体功能：「NotificationEntity.getDeepLink()」：读取「NotificationEntity」中的「deepLink」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「NotificationEntity.getDeepLink()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getDeepLink()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「NotificationEntity.getDeepLink()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public String getDeepLink() {
        return deepLink;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getReadAt()」。
    // 具体功能：「NotificationEntity.getReadAt()」：读取「NotificationEntity」中的「readAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「NotificationEntity.getReadAt()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getReadAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「NotificationEntity.getReadAt()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public Instant getReadAt() {
        return readAt;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getDismissedAt()」。
    // 具体功能：「NotificationEntity.getDismissedAt()」：读取「NotificationEntity」中的「dismissedAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「NotificationEntity.getDismissedAt()」由使用「NotificationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「NotificationEntity.getDismissedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「NotificationEntity.getDismissedAt()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public Instant getDismissedAt() {
        return dismissedAt;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.getCreatedAt()」。
    // 具体功能：「NotificationEntity.getCreatedAt()」：读取「NotificationEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「NotificationEntity.getCreatedAt()」的上游调用点包括 「NotificationService.view」。
    // 下游影响：「NotificationEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「NotificationEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public Instant getCreatedAt() {
        return createdAt;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationEntity.required(String,String)」。
    // 具体功能：「NotificationEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「NotificationEntity.required(String,String)」的上游调用点包括 「NotificationEntity.create」。
    // 下游影响：「NotificationEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「NotificationEntity.required(String,String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

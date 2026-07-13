/*
 * 所属模块：案件生命周期通知。
 * 文件职责：映射通知发件箱数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「pending」、「preUpdate」；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【案件生命周期通知 / JPA 实体层】类型「NotificationOutboxEntity」。
// 类型职责：映射通知发件箱数据库记录并保存可审计状态；本类型显式提供 「NotificationOutboxEntity」、「NotificationOutboxEntity」、「pending」、「preUpdate」、「required」。
// 协作关系：主要由 「NotificationService.create」 使用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "notification_outbox")
public class NotificationOutboxEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "business_event_key", length = 128, nullable = false)
    private String businessEventKey;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_payload_json", nullable = false, columnDefinition = "jsonb")
    private String eventPayloadJson;

    @Column(name = "outbox_status", length = 32, nullable = false)
    private String outboxStatus;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationOutboxEntity.NotificationOutboxEntity()」。
    // 具体功能：「NotificationOutboxEntity.NotificationOutboxEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「NotificationOutboxEntity.NotificationOutboxEntity()」的上游创建点包括 「NotificationOutboxEntity.pending」。
    // 下游影响：「NotificationOutboxEntity.NotificationOutboxEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「NotificationOutboxEntity.NotificationOutboxEntity()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected NotificationOutboxEntity() {}

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationOutboxEntity.NotificationOutboxEntity(String)」。
    // 具体功能：「NotificationOutboxEntity.NotificationOutboxEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「NotificationOutboxEntity.NotificationOutboxEntity(String)」的上游创建点包括 「NotificationOutboxEntity.pending」。
    // 下游影响：「NotificationOutboxEntity.NotificationOutboxEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「NotificationOutboxEntity.NotificationOutboxEntity(String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private NotificationOutboxEntity(String id) {
        super(id);
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationOutboxEntity.pending(String,String,String,String,String,Instant)」。
    // 具体功能：「NotificationOutboxEntity.pending(String,String,String,String,String,Instant)」：更新待处理：先更新内部状态 「caseId」、「businessEventKey」、「eventType」、「eventPayloadJson」；实际协作者为 「required」；处理的关键状态/协议值包括 「id」、「caseId」、「businessEventKey」、「eventType」，最终返回「NotificationOutboxEntity」。
    // 上游调用：「NotificationOutboxEntity.pending(String,String,String,String,String,Instant)」的上游调用点包括 「NotificationService.create」。
    // 下游影响：「NotificationOutboxEntity.pending(String,String,String,String,String,Instant)」向下依次触达 「required」；计算结果以「NotificationOutboxEntity」交给调用方。
    // 系统意义：「NotificationOutboxEntity.pending(String,String,String,String,String,Instant)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    public static NotificationOutboxEntity pending(
            String id,
            String caseId,
            String businessEventKey,
            String eventType,
            String payloadJson,
            Instant now) {
        NotificationOutboxEntity entity = new NotificationOutboxEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.businessEventKey = required(businessEventKey, "businessEventKey");
        entity.eventType = required(eventType, "eventType");
        entity.eventPayloadJson = required(payloadJson, "payloadJson");
        entity.outboxStatus = "PENDING";
        entity.availableAt = now;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationOutboxEntity.preUpdate()」。
    // 具体功能：「NotificationOutboxEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「NotificationOutboxEntity.preUpdate()」由使用「NotificationOutboxEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「NotificationOutboxEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「NotificationOutboxEntity.preUpdate()」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // 所属模块：【案件生命周期通知 / JPA 实体层】「NotificationOutboxEntity.required(String,String)」。
    // 具体功能：「NotificationOutboxEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「NotificationOutboxEntity.required(String,String)」的上游调用点包括 「NotificationOutboxEntity.pending」。
    // 下游影响：「NotificationOutboxEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「NotificationOutboxEntity.required(String,String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

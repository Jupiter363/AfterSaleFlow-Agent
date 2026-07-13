/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射Abstract数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「getId」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.Objects;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「AbstractEntity」。
// 类型职责：映射Abstract数据库记录并保存可审计状态；本类型显式提供 「AbstractEntity」、「AbstractEntity」、「getId」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@MappedSuperclass
public abstract class AbstractEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AbstractEntity.AbstractEntity()」。
    // 具体功能：「AbstractEntity.AbstractEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AbstractEntity.AbstractEntity()」由 JPA/Hibernate 反射构造，或由该实体的显式工厂方法创建。
    // 下游影响：「AbstractEntity.AbstractEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AbstractEntity.AbstractEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected AbstractEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AbstractEntity.AbstractEntity(String)」。
    // 具体功能：「AbstractEntity.AbstractEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AbstractEntity.AbstractEntity(String)」由 JPA/Hibernate 反射构造，或由该实体的显式工厂方法创建。
    // 下游影响：「AbstractEntity.AbstractEntity(String)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「AbstractEntity.AbstractEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected AbstractEntity(String id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AbstractEntity.getId()」。
    // 具体功能：「AbstractEntity.getId()」：读取「AbstractEntity」中的「id」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AbstractEntity.getId()」由使用「AbstractEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AbstractEntity.getId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AbstractEntity.getId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getId() {
        return id;
    }
}

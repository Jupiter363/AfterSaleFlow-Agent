/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射争点数据库记录并保存可审计状态。
 * 业务链路：该文件主要提供类型或包级契约；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「IssueEntity」。
// 类型职责：映射争点数据库记录并保存可审计状态；本类型显式提供 「IssueEntity」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "issue")
public class IssueEntity extends AbstractEntity {
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「IssueEntity.IssueEntity()」。
    // 具体功能：「IssueEntity.IssueEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「IssueEntity.IssueEntity()」由 JPA/Hibernate 反射构造，或由该实体的显式工厂方法创建。
    // 下游影响：「IssueEntity.IssueEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IssueEntity.IssueEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected IssueEntity() {}
}

/*
 * 所属模块：案件核心与导入。
 * 文件职责：映射模拟导入模板Cursor数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「getNextTemplateNo」、「advance」、「preUpdate」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

// 所属模块：【案件核心与导入 / JPA 实体层】类型「SimulatedImportTemplateCursorEntity」。
// 类型职责：映射模拟导入模板Cursor数据库记录并保存可审计状态；本类型显式提供 「SimulatedImportTemplateCursorEntity」、「SimulatedImportTemplateCursorEntity」、「getNextTemplateNo」、「advance」、「preUpdate」、「requireTemplateNo」。
// 协作关系：主要由 「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne」、「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "simulated_import_template_cursor")
public class SimulatedImportTemplateCursorEntity {

    public static final String CURSOR_ID = "external-case-template";

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "next_template_no", nullable = false)
    private int nextTemplateNo;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // 所属模块：【案件核心与导入 / JPA 实体层】「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity()」。
    // 具体功能：「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity()」由 JPA/Hibernate 反射构造，或由该实体的显式工厂方法创建。
    // 下游影响：「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity()」直接影响 PostgreSQL 事实投影；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected SimulatedImportTemplateCursorEntity() {}

    // 所属模块：【案件核心与导入 / JPA 实体层】「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity(String,int)」。
    // 具体功能：「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity(String,int)」：使用 「id」(String)、「nextTemplateNo」(int) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity(String,int)」由 JPA/Hibernate 反射构造，或由该实体的显式工厂方法创建。
    // 下游影响：「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity(String,int)」向下依次触达 「requireTemplateNo」。
    // 系统意义：「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity(String,int)」直接影响 PostgreSQL 事实投影；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public SimulatedImportTemplateCursorEntity(String id, int nextTemplateNo) {
        this.id = id;
        this.nextTemplateNo = requireTemplateNo(nextTemplateNo);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【案件核心与导入 / JPA 实体层】「SimulatedImportTemplateCursorEntity.getNextTemplateNo()」。
    // 具体功能：「SimulatedImportTemplateCursorEntity.getNextTemplateNo()」：读取「SimulatedImportTemplateCursorEntity」中的「nextTemplateNo」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「SimulatedImportTemplateCursorEntity.getNextTemplateNo()」的上游调用点包括 「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne」、「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor」。
    // 下游影响：「SimulatedImportTemplateCursorEntity.getNextTemplateNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「SimulatedImportTemplateCursorEntity.getNextTemplateNo()」直接影响 PostgreSQL 事实投影；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public int getNextTemplateNo() {
        return nextTemplateNo;
    }

    // 所属模块：【案件核心与导入 / JPA 实体层】「SimulatedImportTemplateCursorEntity.advance(int)」。
    // 具体功能：「SimulatedImportTemplateCursorEntity.advance(int)」：推进模拟导入模板Cursor：先更新内部状态 「nextTemplateNo」；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「SimulatedImportTemplateCursorEntity.advance(int)」由使用「SimulatedImportTemplateCursorEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SimulatedImportTemplateCursorEntity.advance(int)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SimulatedImportTemplateCursorEntity.advance(int)」直接影响 PostgreSQL 事实投影；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public void advance(int templateCount) {
        if (templateCount < 1) {
            throw new IllegalArgumentException("templateCount must be positive");
        }
        nextTemplateNo = nextTemplateNo >= templateCount ? 1 : nextTemplateNo + 1;
    }

    // 所属模块：【案件核心与导入 / JPA 实体层】「SimulatedImportTemplateCursorEntity.preUpdate()」。
    // 具体功能：「SimulatedImportTemplateCursorEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「SimulatedImportTemplateCursorEntity.preUpdate()」由使用「SimulatedImportTemplateCursorEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SimulatedImportTemplateCursorEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SimulatedImportTemplateCursorEntity.preUpdate()」直接影响 PostgreSQL 事实投影；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【案件核心与导入 / JPA 实体层】「SimulatedImportTemplateCursorEntity.requireTemplateNo(int)」。
    // 具体功能：「SimulatedImportTemplateCursorEntity.requireTemplateNo(int)」：强制校验模板编号；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「int」。
    // 上游调用：「SimulatedImportTemplateCursorEntity.requireTemplateNo(int)」的上游调用点包括 「SimulatedImportTemplateCursorEntity.SimulatedImportTemplateCursorEntity」。
    // 下游影响：「SimulatedImportTemplateCursorEntity.requireTemplateNo(int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「SimulatedImportTemplateCursorEntity.requireTemplateNo(int)」直接影响 PostgreSQL 事实投影；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static int requireTemplateNo(int value) {
        if (value < 1 || value > 20) {
            throw new IllegalArgumentException("nextTemplateNo must be between 1 and 20");
        }
        return value;
    }
}

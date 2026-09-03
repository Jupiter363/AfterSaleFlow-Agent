/*
 * 所属模块：案件核心与导入。
 * 文件职责：限定案件来源类型允许出现的状态值。
 * 业务链路：该文件主要提供类型或包级契约；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.domain;

/** Identifies how a dispute entered this bounded system. */
// 所属模块：【案件核心与导入 / 领域模型层】类型「CaseSourceType」。
// 类型职责：限定案件来源类型允许出现的状态值；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum CaseSourceType {
    EXTERNAL_IMPORT,
    INTAKE_CREATED
}

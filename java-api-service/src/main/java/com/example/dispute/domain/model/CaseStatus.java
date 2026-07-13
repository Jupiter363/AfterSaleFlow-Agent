/*
 * 所属模块：共享领域状态。
 * 文件职责：限定案件状态允许出现的状态值。
 * 业务链路：该文件主要提供类型或包级契约；定义案件、庭审、审核、执行和风险级别的有限状态词汇。
 * 关键边界：枚举值同时进入数据库和 API，改名会影响历史数据与前后端契约
 */
package com.example.dispute.domain.model;

// 所属模块：【共享领域状态 / 领域模型层】类型「CaseStatus」。
// 类型职责：限定案件状态允许出现的状态值；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：枚举值同时进入数据库和 API，改名会影响历史数据与前后端契约
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum CaseStatus {
    INTAKE_PENDING,
    INTAKE_IN_PROGRESS,
    NOT_ADMISSIBLE,
    WAITING_SLOT_COMPLETION,
    INTAKE_COMPLETED,
    EVIDENCE_OPEN,
    EVIDENCE_SEALED,
    DOSSIER_BUILDING,
    DOSSIER_BUILT,
    ROUTED,
    HEARING,
    HEARING_OPEN,
    WAITING_EVIDENCE,
    SETTLEMENT_PENDING,
    DRAFT_READY,
    DELIBERATION_RUNNING,
    REVIEW_PENDING,
    REMEDY_PLANNED,
    WAITING_HUMAN_REVIEW,
    MANUAL_HANDOFF,
    APPROVED_FOR_EXECUTION,
    EXECUTING,
    CLOSED,
    CANCELLED
}

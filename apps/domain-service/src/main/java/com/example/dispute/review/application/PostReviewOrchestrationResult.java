/*
 * 所属模块：平台人工终审。
 * 文件职责：定义事务后审核Orchestration跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.application;

// 所属模块：【平台人工终审 / 应用编排层】类型「PostReviewOrchestrationResult」。
// 类型职责：定义事务后审核Orchestration跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record PostReviewOrchestrationResult(
        String approvalRecordId,
        String caseId,
        String status,
        boolean executionAttempted,
        boolean closureAttempted,
        String message) {}

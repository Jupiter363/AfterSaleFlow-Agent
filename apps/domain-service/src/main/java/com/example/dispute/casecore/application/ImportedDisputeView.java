/*
 * 所属模块：案件核心与导入。
 * 文件职责：定义导入案件争议跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import java.time.OffsetDateTime;

// 所属模块：【案件核心与导入 / 应用编排层】类型「ImportedDisputeView」。
// 类型职责：定义导入案件争议跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ImportedDisputeView(
        String id,
        String orderId,
        String afterSaleId,
        String logisticsId,
        String userId,
        String merchantId,
        String disputeType,
        String sourceType,
        String sourceSystem,
        String externalCaseReference,
        RiskLevel riskLevel,
        String title,
        String description,
        CaseStatus caseStatus,
        String currentRoom,
        OffsetDateTime currentDeadlineAt,
        String pendingAction,
        String initiatorRole) {}

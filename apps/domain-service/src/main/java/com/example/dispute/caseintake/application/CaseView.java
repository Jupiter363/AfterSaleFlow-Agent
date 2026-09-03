/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：定义案件跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.application;

import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.casecore.domain.CasePartyPosition;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.time.OffsetDateTime;
import java.util.List;

// 所属模块：【案件受理兼容链路 / 应用编排层】类型「CaseView」。
// 类型职责：定义案件跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record CaseView(
        String id,
        String orderId,
        String afterSaleId,
        String logisticsId,
        String userId,
        String merchantId,
        String caseType,
        String disputeType,
        CaseStatus caseStatus,
        RouteType routeType,
        RiskLevel riskLevel,
        String title,
        String description,
        boolean potentialDispute,
        List<String> missingSlots,
        boolean agentDegraded,
        CaseSourceType sourceType,
        String sourceSystem,
        String externalCaseReference,
        String currentRoom,
        OffsetDateTime deadlineAt,
        String pendingAction,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt,
        ActorRole initiatorRole,
        String initiatorId,
        ActorRole respondentRole,
        String respondentId,
        CasePartyPosition partyPosition) {}

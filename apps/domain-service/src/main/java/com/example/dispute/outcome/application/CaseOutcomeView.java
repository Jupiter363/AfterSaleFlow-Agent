/*
 * 所属模块：裁决结果查询。
 * 文件职责：定义案件结果跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；聚合人工终审、非最终草案、补救执行和案件时间线形成角色可见结果页。
 * 关键边界：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
 */
package com.example.dispute.outcome.application;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.executor.application.ActionRecordView;
import java.time.OffsetDateTime;
import java.util.List;

// 所属模块：【裁决结果查询 / 应用编排层】类型「CaseOutcomeView」。
// 类型职责：定义案件结果跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record CaseOutcomeView(
        String caseId,
        String title,
        CaseStatus caseStatus,
        OffsetDateTime closedAt,
        FinalDecisionView finalDecision,
        AdjudicationDraftView adjudicationDraft,
        String reviewTaskId,
        String reviewTaskStatus,
        List<ActionRecordView> actions) {}

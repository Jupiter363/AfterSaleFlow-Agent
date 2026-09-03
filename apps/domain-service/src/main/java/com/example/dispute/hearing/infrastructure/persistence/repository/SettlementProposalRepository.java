/*
 * 所属模块：共享小法庭。
 * 文件职责：声明和解Proposal在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findTopByCaseIdOrderByProposalVersionDesc」、「findByCaseIdAndProposalVersion」、「findAllByCaseIdOrderByProposalVersionDesc」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.infrastructure.persistence.entity.SettlementProposalEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【共享小法庭 / 仓储接口层】类型「SettlementProposalRepository」。
// 类型职责：声明和解Proposal在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findTopByCaseIdOrderByProposalVersionDesc」、「findByCaseIdAndProposalVersion」、「findAllByCaseIdOrderByProposalVersionDesc」。
// 协作关系：主要由 「FinalWorkflowActivitiesAdapter.ensureSettlementDraft」、「SettlementService.confirm」、「SettlementService.get」、「SettlementService.list」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface SettlementProposalRepository
        extends JpaRepository<SettlementProposalEntity, String> {
    // 所属模块：【共享小法庭 / 仓储接口层】「SettlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc(String)」。
    // 具体功能：「SettlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc(String)」：声明按案件标识访问和解Proposal的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<SettlementProposalEntity>」返回。
    // 上游调用：「SettlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc(String)」的上游调用点包括 「SettlementService.propose」、「SettlementService.confirm」、「FinalWorkflowActivitiesAdapter.ensureSettlementDraft」。
    // 下游影响：「SettlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「SettlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<SettlementProposalEntity> findTopByCaseIdOrderByProposalVersionDesc(
            String caseId);
    // 所属模块：【共享小法庭 / 仓储接口层】「SettlementProposalRepository.findByCaseIdAndProposalVersion(String,int)」。
    // 具体功能：「SettlementProposalRepository.findByCaseIdAndProposalVersion(String,int)」：声明按案件标识、Proposal版本访问和解Proposal的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<SettlementProposalEntity>」返回。
    // 上游调用：「SettlementProposalRepository.findByCaseIdAndProposalVersion(String,int)」的上游调用点包括 「SettlementService.get」。
    // 下游影响：「SettlementProposalRepository.findByCaseIdAndProposalVersion(String,int)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「SettlementProposalRepository.findByCaseIdAndProposalVersion(String,int)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<SettlementProposalEntity> findByCaseIdAndProposalVersion(
            String caseId, int proposalVersion);
    // 所属模块：【共享小法庭 / 仓储接口层】「SettlementProposalRepository.findAllByCaseIdOrderByProposalVersionDesc(String)」。
    // 具体功能：「SettlementProposalRepository.findAllByCaseIdOrderByProposalVersionDesc(String)」：声明按案件标识访问和解Proposal的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<SettlementProposalEntity>」返回。
    // 上游调用：「SettlementProposalRepository.findAllByCaseIdOrderByProposalVersionDesc(String)」的上游调用点包括 「SettlementService.list」、「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」。
    // 下游影响：「SettlementProposalRepository.findAllByCaseIdOrderByProposalVersionDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「SettlementProposalRepository.findAllByCaseIdOrderByProposalVersionDesc(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<SettlementProposalEntity> findAllByCaseIdOrderByProposalVersionDesc(
            String caseId);
}

/*
 * 所属模块：共享小法庭。
 * 文件职责：声明和解Confirmation在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseIdAndIdempotencyKey」、「findByProposalIdAndParticipantRole」、「findAllByProposalIdAndConfirmationStatus」、「countByProposalIdAndConfirmationStatus」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.infrastructure.persistence.entity.SettlementConfirmationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【共享小法庭 / 仓储接口层】类型「SettlementConfirmationRepository」。
// 类型职责：声明和解Confirmation在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseIdAndIdempotencyKey」、「findByProposalIdAndParticipantRole」、「findAllByProposalIdAndConfirmationStatus」、「countByProposalIdAndConfirmationStatus」。
// 协作关系：主要由 「SettlementService.confirm」、「SettlementService.view」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface SettlementConfirmationRepository
        extends JpaRepository<SettlementConfirmationEntity, String> {
    // 所属模块：【共享小法庭 / 仓储接口层】「SettlementConfirmationRepository.findByCaseIdAndIdempotencyKey(String,String)」。
    // 具体功能：「SettlementConfirmationRepository.findByCaseIdAndIdempotencyKey(String,String)」：声明按案件标识、Idempotency键访问和解Confirmation的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<SettlementConfirmationEntity>」返回。
    // 上游调用：「SettlementConfirmationRepository.findByCaseIdAndIdempotencyKey(String,String)」的上游调用点包括 「SettlementService.confirm」。
    // 下游影响：「SettlementConfirmationRepository.findByCaseIdAndIdempotencyKey(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「SettlementConfirmationRepository.findByCaseIdAndIdempotencyKey(String,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<SettlementConfirmationEntity> findByCaseIdAndIdempotencyKey(
            String caseId, String idempotencyKey);
    // 所属模块：【共享小法庭 / 仓储接口层】「SettlementConfirmationRepository.findByProposalIdAndParticipantRole(String,ActorRole)」。
    // 具体功能：「SettlementConfirmationRepository.findByProposalIdAndParticipantRole(String,ActorRole)」：声明按Proposal标识、参与人角色访问和解Confirmation的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<SettlementConfirmationEntity>」返回。
    // 上游调用：「SettlementConfirmationRepository.findByProposalIdAndParticipantRole(String,ActorRole)」的上游调用点包括 「SettlementService.confirm」。
    // 下游影响：「SettlementConfirmationRepository.findByProposalIdAndParticipantRole(String,ActorRole)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「SettlementConfirmationRepository.findByProposalIdAndParticipantRole(String,ActorRole)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<SettlementConfirmationEntity> findByProposalIdAndParticipantRole(
            String proposalId, ActorRole participantRole);
    // 所属模块：【共享小法庭 / 仓储接口层】「SettlementConfirmationRepository.findAllByProposalIdAndConfirmationStatus(String,String)」。
    // 具体功能：「SettlementConfirmationRepository.findAllByProposalIdAndConfirmationStatus(String,String)」：声明按Proposal标识、Confirmation状态访问和解Confirmation的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<SettlementConfirmationEntity>」返回。
    // 上游调用：「SettlementConfirmationRepository.findAllByProposalIdAndConfirmationStatus(String,String)」的上游调用点包括 「SettlementService.view」。
    // 下游影响：「SettlementConfirmationRepository.findAllByProposalIdAndConfirmationStatus(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「SettlementConfirmationRepository.findAllByProposalIdAndConfirmationStatus(String,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<SettlementConfirmationEntity>
            findAllByProposalIdAndConfirmationStatus(
                    String proposalId, String confirmationStatus);
    // 所属模块：【共享小法庭 / 仓储接口层】「SettlementConfirmationRepository.countByProposalIdAndConfirmationStatus(String,String)」。
    // 具体功能：「SettlementConfirmationRepository.countByProposalIdAndConfirmationStatus(String,String)」：声明按Proposal标识、Confirmation状态访问和解Confirmation的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「long」返回。
    // 上游调用：「SettlementConfirmationRepository.countByProposalIdAndConfirmationStatus(String,String)」的上游调用点包括 「SettlementService.confirm」。
    // 下游影响：「SettlementConfirmationRepository.countByProposalIdAndConfirmationStatus(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「SettlementConfirmationRepository.countByProposalIdAndConfirmationStatus(String,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    long countByProposalIdAndConfirmationStatus(
            String proposalId, String confirmationStatus);
}

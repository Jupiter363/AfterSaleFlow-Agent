/*
 * 所属模块：共享小法庭。
 * 文件职责：声明庭审轮次当事方提交在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「findByCaseIdAndRoundNoAndParticipantRole」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【共享小法庭 / 仓储接口层】类型「HearingRoundPartySubmissionRepository」。
// 类型职责：声明庭审轮次当事方提交在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「findByCaseIdAndRoundNoAndParticipantRole」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.validatedSealedRounds」、「HearingCourtOrchestrator.prepareJudgeTurn」、「HearingRoundService.advanceAfterPartySubmissionIfReady」、「HearingRoundService.completeRoundAfterTimeout」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface HearingRoundPartySubmissionRepository
        extends JpaRepository<HearingRoundPartySubmissionEntity, String> {

    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundPartySubmissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(String,int)」。
    // 具体功能：「HearingRoundPartySubmissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(String,int)」：声明按案件标识、轮次编号访问庭审轮次当事方提交的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<HearingRoundPartySubmissionEntity>」返回。
    // 上游调用：「HearingRoundPartySubmissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(String,int)」的上游调用点包括 「ActiveCourtroomContextAssembler.validatedSealedRounds」、「HearingCourtOrchestrator.prepareJudgeTurn」、「HearingRoundService.status」、「HearingRoundService.completeRoundAfterTimeout」。
    // 下游影响：「HearingRoundPartySubmissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(String,int)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundPartySubmissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(String,int)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<HearingRoundPartySubmissionEntity> findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
            String caseId, int roundNo);

    // 所属模块：【共享小法庭 / 仓储接口层】「HearingRoundPartySubmissionRepository.findByCaseIdAndRoundNoAndParticipantRole(String,int,ActorRole)」。
    // 具体功能：「HearingRoundPartySubmissionRepository.findByCaseIdAndRoundNoAndParticipantRole(String,int,ActorRole)」：声明按案件标识、轮次编号、参与人角色访问庭审轮次当事方提交的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<HearingRoundPartySubmissionEntity>」返回。
    // 上游调用：「HearingRoundPartySubmissionRepository.findByCaseIdAndRoundNoAndParticipantRole(String,int,ActorRole)」的上游调用点包括 「HearingRoundService.submitParty」、「HearingRoundService.recordPartyMessageSubmission」。
    // 下游影响：「HearingRoundPartySubmissionRepository.findByCaseIdAndRoundNoAndParticipantRole(String,int,ActorRole)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HearingRoundPartySubmissionRepository.findByCaseIdAndRoundNoAndParticipantRole(String,int,ActorRole)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<HearingRoundPartySubmissionEntity> findByCaseIdAndRoundNoAndParticipantRole(
            String caseId, int roundNo, ActorRole participantRole);
}

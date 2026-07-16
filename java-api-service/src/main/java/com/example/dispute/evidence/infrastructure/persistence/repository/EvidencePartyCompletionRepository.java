/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：声明证据当事方完成确认在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseIdAndIdempotencyKey」、「findByCaseIdAndDossierVersionAndParticipantRole」、「findAllByCaseIdAndDossierVersionAndCompletionStatus」、「findTopByCaseIdOrderByDossierVersionDesc」、「countByCaseIdAndDossierVersionAndCompletionStatus」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidencePartyCompletionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【证据与版本化卷宗 / 仓储接口层】类型「EvidencePartyCompletionRepository」。
// 类型职责：声明证据当事方完成确认在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseIdAndIdempotencyKey」、「findByCaseIdAndDossierVersionAndParticipantRole」、「findAllByCaseIdAndDossierVersionAndCompletionStatus」、「findTopByCaseIdOrderByDossierVersionDesc」、「countByCaseIdAndDossierVersionAndCompletionStatus」。
// 协作关系：主要由 「EvidenceCompletionService.complete」、「EvidenceCompletionService.completionVersion」、「EvidenceCompletionService.status」、「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface EvidencePartyCompletionRepository
        extends JpaRepository<EvidencePartyCompletionEntity, String> {
    // 所属模块：【证据与版本化卷宗 / 仓储接口层】「EvidencePartyCompletionRepository.findByCaseIdAndIdempotencyKey(String,String)」。
    // 具体功能：「EvidencePartyCompletionRepository.findByCaseIdAndIdempotencyKey(String,String)」：声明按案件标识、Idempotency键访问证据当事方完成确认的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvidencePartyCompletionEntity>」返回。
    // 上游调用：「EvidencePartyCompletionRepository.findByCaseIdAndIdempotencyKey(String,String)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」、「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」。
    // 下游影响：「EvidencePartyCompletionRepository.findByCaseIdAndIdempotencyKey(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidencePartyCompletionRepository.findByCaseIdAndIdempotencyKey(String,String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvidencePartyCompletionEntity> findByCaseIdAndIdempotencyKey(
            String caseId, String idempotencyKey);
    // 所属模块：【证据与版本化卷宗 / 仓储接口层】「EvidencePartyCompletionRepository.findByCaseIdAndDossierVersionAndParticipantRole(String,int,ActorRole)」。
    // 具体功能：「EvidencePartyCompletionRepository.findByCaseIdAndDossierVersionAndParticipantRole(String,int,ActorRole)」：声明按案件标识、卷宗版本、参与人角色访问证据当事方完成确认的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvidencePartyCompletionEntity>」返回。
    // 上游调用：「EvidencePartyCompletionRepository.findByCaseIdAndDossierVersionAndParticipantRole(String,int,ActorRole)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」。
    // 下游影响：「EvidencePartyCompletionRepository.findByCaseIdAndDossierVersionAndParticipantRole(String,int,ActorRole)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidencePartyCompletionRepository.findByCaseIdAndDossierVersionAndParticipantRole(String,int,ActorRole)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvidencePartyCompletionEntity>
            findByCaseIdAndDossierVersionAndParticipantId(
                    String caseId, int dossierVersion, String participantId);
    // 所属模块：【证据与版本化卷宗 / 仓储接口层】「EvidencePartyCompletionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」。
    // 具体功能：「EvidencePartyCompletionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」：声明按案件标识、卷宗版本、完成确认状态访问证据当事方完成确认的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<EvidencePartyCompletionEntity>」返回。
    // 上游调用：「EvidencePartyCompletionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」的上游调用点包括 「EvidenceCompletionService.status」、「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」。
    // 下游影响：「EvidencePartyCompletionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidencePartyCompletionRepository.findAllByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<EvidencePartyCompletionEntity>
            findAllByCaseIdAndDossierVersionAndCompletionStatus(
                    String caseId, int dossierVersion, String completionStatus);
    // 所属模块：【证据与版本化卷宗 / 仓储接口层】「EvidencePartyCompletionRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」。
    // 具体功能：「EvidencePartyCompletionRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」：声明按案件标识访问证据当事方完成确认的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<EvidencePartyCompletionEntity>」返回。
    // 上游调用：「EvidencePartyCompletionRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」的上游调用点包括 「EvidenceCompletionService.completionVersion」。
    // 下游影响：「EvidencePartyCompletionRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidencePartyCompletionRepository.findTopByCaseIdOrderByDossierVersionDesc(String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<EvidencePartyCompletionEntity>
            findTopByCaseIdOrderByDossierVersionDesc(String caseId);
    // 所属模块：【证据与版本化卷宗 / 仓储接口层】「EvidencePartyCompletionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」。
    // 具体功能：「EvidencePartyCompletionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」：声明按案件标识、卷宗版本、完成确认状态访问证据当事方完成确认的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「long」返回。
    // 上游调用：「EvidencePartyCompletionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」、「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」。
    // 下游影响：「EvidencePartyCompletionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidencePartyCompletionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(String,int,String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
}

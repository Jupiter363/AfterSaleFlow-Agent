/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明案件阶段时钟在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseIdAndClockType」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「CasePhaseClockRepository」。
// 类型职责：声明案件阶段时钟在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseIdAndClockType」。
// 协作关系：主要由 「EvidenceCompletionService.sealEvidenceAndOpenHearing」、「ExternalCaseImportTransactionService.materializeCurrentRoom」、「FinalWorkflowActivitiesAdapter.complete」、「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface CasePhaseClockRepository
        extends JpaRepository<CasePhaseClockEntity, String> {

    // 所属模块：【房间协作与权限 / 仓储接口层】「CasePhaseClockRepository.findByCaseIdAndClockType(String,PhaseClockType)」。
    // 具体功能：「CasePhaseClockRepository.findByCaseIdAndClockType(String,PhaseClockType)」：声明按案件标识、时钟类型访问案件阶段时钟的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<CasePhaseClockEntity>」返回。
    // 上游调用：「CasePhaseClockRepository.findByCaseIdAndClockType(String,PhaseClockType)」的上游调用点包括 「ExternalCaseImportTransactionService.materializeCurrentRoom」、「EvidenceCompletionService.sealEvidenceAndOpenHearing」、「FinalWorkflowActivitiesAdapter.complete」、「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」。
    // 下游影响：「CasePhaseClockRepository.findByCaseIdAndClockType(String,PhaseClockType)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CasePhaseClockRepository.findByCaseIdAndClockType(String,PhaseClockType)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<CasePhaseClockEntity> findByCaseIdAndClockType(
            String caseId, PhaseClockType clockType);
}

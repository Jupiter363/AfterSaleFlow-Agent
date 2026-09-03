/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明案件参与人在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findAllByCaseId」、「existsByCaseIdAndActorIdAndParticipantRole」、「findByCaseIdAndActorIdAndParticipantRole」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.infrastructure.persistence.entity.CaseParticipantEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「CaseParticipantRepository」。
// 类型职责：声明案件参与人在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findAllByCaseId」、「existsByCaseIdAndActorIdAndParticipantRole」、「findByCaseIdAndActorIdAndParticipantRole」。
// 协作关系：主要由 「AccessSessionResolver.assertPartyCanAccess」、「CaseEventService.assertCanAccess」、「ParticipantService.ensureImportedParties」、「ParticipantService.participant」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface CaseParticipantRepository
        extends JpaRepository<CaseParticipantEntity, String> {

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseParticipantRepository.findAllByCaseId(String)」。
    // 具体功能：「CaseParticipantRepository.findAllByCaseId(String)」：声明按案件标识访问案件参与人的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<CaseParticipantEntity>」返回。
    // 上游调用：「CaseParticipantRepository.findAllByCaseId(String)」的上游调用点包括 「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」、「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase」、「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」、「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake」。
    // 下游影响：「CaseParticipantRepository.findAllByCaseId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseParticipantRepository.findAllByCaseId(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<CaseParticipantEntity> findAllByCaseId(String caseId);

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseParticipantRepository.existsByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」。
    // 具体功能：「CaseParticipantRepository.existsByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」：声明按案件标识、操作者标识、参与人角色访问案件参与人的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「boolean」返回。
    // 上游调用：「CaseParticipantRepository.existsByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」的上游调用点包括 「AccessSessionResolver.assertPartyCanAccess」、「CaseEventService.assertCanAccess」、「ParticipantService.ensureImportedParties」、「RoomMessageService.assertCanRead」。
    // 下游影响：「CaseParticipantRepository.existsByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseParticipantRepository.existsByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    boolean existsByCaseIdAndActorIdAndParticipantRole(
            String caseId, String actorId, ActorRole participantRole);

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseParticipantRepository.findByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」。
    // 具体功能：「CaseParticipantRepository.findByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」：声明按案件标识、操作者标识、参与人角色访问案件参与人的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<CaseParticipantEntity>」返回。
    // 上游调用：「CaseParticipantRepository.findByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」的上游调用点包括 「ParticipantService.participant」。
    // 下游影响：「CaseParticipantRepository.findByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseParticipantRepository.findByCaseIdAndActorIdAndParticipantRole(String,String,ActorRole)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<CaseParticipantEntity>
            findByCaseIdAndActorIdAndParticipantRole(
                    String caseId,
                    String actorId,
                    ActorRole participantRole);
}

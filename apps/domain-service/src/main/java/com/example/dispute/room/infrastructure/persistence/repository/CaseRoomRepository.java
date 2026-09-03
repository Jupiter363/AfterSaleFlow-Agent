/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明案件房间在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseIdAndRoomType」、「findByCaseIdAndRoomTypeForUpdate」、「findAllByCaseId」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「CaseRoomRepository」。
// 类型职责：声明案件房间在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseIdAndRoomType」、「findByCaseIdAndRoomTypeForUpdate」、「findAllByCaseId」。
// 协作关系：主要由 「CaseAgentRunController.active」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「EvidenceAgentTurnService.prepare」、「EvidenceCompletionService.sealEvidenceAndOpenHearing」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface CaseRoomRepository extends JpaRepository<CaseRoomEntity, String> {

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseRoomRepository.findByCaseIdAndRoomType(String,RoomType)」。
    // 具体功能：「CaseRoomRepository.findByCaseIdAndRoomType(String,RoomType)」：声明按案件标识、房间类型访问案件房间的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<CaseRoomEntity>」返回。
    // 上游调用：「CaseRoomRepository.findByCaseIdAndRoomType(String,RoomType)」的上游调用点包括 「CaseAgentRunController.active」、「ExternalCaseImportTransactionService.materializeCurrentRoom」、「EvidenceCompletionService.sealEvidenceAndOpenHearing」、「EvidenceDossierRevisionService.recordRevisionEvent」。
    // 下游影响：「CaseRoomRepository.findByCaseIdAndRoomType(String,RoomType)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseRoomRepository.findByCaseIdAndRoomType(String,RoomType)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<CaseRoomEntity> findByCaseIdAndRoomType(String caseId, RoomType roomType);

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseRoomRepository.findByCaseIdAndRoomTypeForUpdate(String,RoomType)」。
    // 具体功能：「CaseRoomRepository.findByCaseIdAndRoomTypeForUpdate(String,RoomType)」：声明按案件标识、房间类型面向更新访问案件房间的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<CaseRoomEntity>」返回。
    // 上游调用：「CaseRoomRepository.findByCaseIdAndRoomTypeForUpdate(String,RoomType)」的上游调用点包括 「HearingCourtOrchestrator.persistJudgeTurn」、「HearingCourtOrchestratorTest.setUp」。
    // 下游影响：「CaseRoomRepository.findByCaseIdAndRoomTypeForUpdate(String,RoomType)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseRoomRepository.findByCaseIdAndRoomTypeForUpdate(String,RoomType)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select room
              from CaseRoomEntity room
             where room.caseId = :caseId
               and room.roomType = :roomType
            """)
    Optional<CaseRoomEntity> findByCaseIdAndRoomTypeForUpdate(
            @Param("caseId") String caseId, @Param("roomType") RoomType roomType);

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseRoomRepository.findAllByCaseId(String)」。
    // 具体功能：「CaseRoomRepository.findAllByCaseId(String)」：声明按案件标识访问案件房间的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<CaseRoomEntity>」返回。
    // 上游调用：「CaseRoomRepository.findAllByCaseId(String)」的上游调用点包括 「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」、「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase」、「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」、「IntakeRoomServiceIntegrationTest.notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake」。
    // 下游影响：「CaseRoomRepository.findAllByCaseId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseRoomRepository.findAllByCaseId(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<CaseRoomEntity> findAllByCaseId(String caseId);
}

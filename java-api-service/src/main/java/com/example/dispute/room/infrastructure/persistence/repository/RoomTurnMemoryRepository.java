/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明房间轮次记忆在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc」、「findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」、「findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc」、「findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc」、「findTop10ByAgentSessionIdOrderByTurnNoDesc」、「findTop50ByAgentSessionIdOrderByTurnNoDesc」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「RoomTurnMemoryRepository」。
// 类型职责：声明房间轮次记忆在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc」、「findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc」、「findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc」、「findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc」、「findTop10ByAgentSessionIdOrderByTurnNoDesc」、「findTop50ByAgentSessionIdOrderByTurnNoDesc」。
// 协作关系：主要由 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」、「EvidenceContextEnvelopeFactory.recentTurns」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface RoomTurnMemoryRepository
        extends JpaRepository<RoomTurnMemoryEntity, String> {

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(String,RoomType)」。
    // 具体功能：「RoomTurnMemoryRepository.findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(String,RoomType)」：声明按案件标识、房间类型、Agent角色Is不空值访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<RoomTurnMemoryEntity>」返回。
    // 上游调用：「RoomTurnMemoryRepository.findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(String,RoomType)」的上游调用点包括 「RoomTurnMemoryQueryService.latestAgentMemory」、「RoomTurnMemoryPersistenceTest.persistsAgentScrollSnapshotAndFindsLatestByCaseAndRoom」。
    // 下游影响：「RoomTurnMemoryRepository.findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(String,RoomType)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(String,RoomType)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<RoomTurnMemoryEntity>
            findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                    String caseId, RoomType roomType);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(String)」。
    // 具体功能：「RoomTurnMemoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(String)」：声明按Agent会话标识、Agent角色Is不空值访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<RoomTurnMemoryEntity>」返回。
    // 上游调用：「RoomTurnMemoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(String)」的上游调用点包括 「IntakeAgentTurnService.latestScrollSnapshot」、「RoomTurnMemoryQueryService.latestAgentMemory」、「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」、「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」。
    // 下游影响：「RoomTurnMemoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<RoomTurnMemoryEntity>
            findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(
                    String agentSessionId);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」。
    // 具体功能：「RoomTurnMemoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」：声明按10按案件标识、房间类型访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<RoomTurnMemoryEntity>」返回。
    // 上游调用：「RoomTurnMemoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」的上游是持有该仓储的应用服务或 Activity，调用发生在其事务边界内。
    // 下游影响：「RoomTurnMemoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<RoomTurnMemoryEntity> findTop10ByCaseIdAndRoomTypeOrderByTurnNoDesc(
            String caseId, RoomType roomType);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」。
    // 具体功能：「RoomTurnMemoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」：声明按50按案件标识、房间类型访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<RoomTurnMemoryEntity>」返回。
    // 上游调用：「RoomTurnMemoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」的上游是持有该仓储的应用服务或 Activity，调用发生在其事务边界内。
    // 下游影响：「RoomTurnMemoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(String,RoomType)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<RoomTurnMemoryEntity> findTop50ByCaseIdAndRoomTypeOrderByTurnNoDesc(
            String caseId, RoomType roomType);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findTop10ByAgentSessionIdOrderByTurnNoDesc(String)」。
    // 具体功能：「RoomTurnMemoryRepository.findTop10ByAgentSessionIdOrderByTurnNoDesc(String)」：声明按10按Agent会话标识访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<RoomTurnMemoryEntity>」返回。
    // 上游调用：「RoomTurnMemoryRepository.findTop10ByAgentSessionIdOrderByTurnNoDesc(String)」的上游调用点包括 「RoomTurnMemoryPersistenceTest.allowsSameAgentTurnNumberForDifferentAgentSessions」。
    // 下游影响：「RoomTurnMemoryRepository.findTop10ByAgentSessionIdOrderByTurnNoDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findTop10ByAgentSessionIdOrderByTurnNoDesc(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<RoomTurnMemoryEntity> findTop10ByAgentSessionIdOrderByTurnNoDesc(
            String agentSessionId);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(String)」。
    // 具体功能：「RoomTurnMemoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(String)」：声明按50按Agent会话标识访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<RoomTurnMemoryEntity>」返回。
    // 上游调用：「RoomTurnMemoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(String)」的上游调用点包括 「EvidenceContextEnvelopeFactory.recentTurns」、「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」。
    // 下游影响：「RoomTurnMemoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<RoomTurnMemoryEntity> findTop50ByAgentSessionIdOrderByTurnNoDesc(
            String agentSessionId);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(String)」。
    // 具体功能：「RoomTurnMemoryRepository.findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(String)」：声明按Agent会话标识、Answer内容Is不空值访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<RoomTurnMemoryEntity>」返回。
    // 上游调用：「RoomTurnMemoryRepository.findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(String)」的上游调用点包括 「IntakeAgentTurnService.initiatorStatementTranscript」、「IntakeAgentTurnServiceTest.participantTurnSendsTheCompleteOrderedTranscriptBeyondTheRecentTurnWindow」。
    // 下游影响：「RoomTurnMemoryRepository.findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<RoomTurnMemoryEntity>
            findAllByAgentSessionIdAndAnswerContentIsNotNullOrderByTurnNoAsc(
                    String agentSessionId);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findMaxTurnNo(String,RoomType)」。
    // 具体功能：「RoomTurnMemoryRepository.findMaxTurnNo(String,RoomType)」：声明按较高风险等级轮次编号访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「int」返回。
    // 上游调用：「RoomTurnMemoryRepository.findMaxTurnNo(String,RoomType)」的上游是持有该仓储的应用服务或 Activity，调用发生在其事务边界内。
    // 下游影响：「RoomTurnMemoryRepository.findMaxTurnNo(String,RoomType)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findMaxTurnNo(String,RoomType)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query(
            "select coalesce(max(memory.turnNo), 0) from RoomTurnMemoryEntity memory "
                    + "where memory.caseId = :caseId and memory.roomType = :roomType")
    int findMaxTurnNo(String caseId, RoomType roomType);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomTurnMemoryRepository.findMaxTurnNoByAgentSessionId(String)」。
    // 具体功能：「RoomTurnMemoryRepository.findMaxTurnNoByAgentSessionId(String)」：声明按较高风险等级轮次编号按Agent会话标识访问房间轮次记忆的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「int」返回。
    // 上游调用：「RoomTurnMemoryRepository.findMaxTurnNoByAgentSessionId(String)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」、「IntakeAgentTurnService.startInitialTurn」。
    // 下游影响：「RoomTurnMemoryRepository.findMaxTurnNoByAgentSessionId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomTurnMemoryRepository.findMaxTurnNoByAgentSessionId(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query(
            "select coalesce(max(memory.turnNo), 0) from RoomTurnMemoryEntity memory "
                    + "where memory.agentSessionId = :agentSessionId")
    int findMaxTurnNoByAgentSessionId(String agentSessionId);
}

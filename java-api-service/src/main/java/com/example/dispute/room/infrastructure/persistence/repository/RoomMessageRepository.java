/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明房间消息在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseIdAndIdempotencyKey」、「findAllByRoomIdOrderBySequenceNoAsc」、「findMaxSequenceByRoomId」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「RoomMessageRepository」。
// 类型职责：声明房间消息在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseIdAndIdempotencyKey」、「findAllByRoomIdOrderBySequenceNoAsc」、「findMaxSequenceByRoomId」。
// 协作关系：主要由 「EvidenceAgentTurnService.appendAgentMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」、「EvidenceAgentTurnService.visibleActorScopedConversationMessages」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface RoomMessageRepository extends JpaRepository<RoomMessageEntity, String> {
    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomMessageRepository.findByCaseIdAndIdempotencyKey(String,String)」。
    // 具体功能：「RoomMessageRepository.findByCaseIdAndIdempotencyKey(String,String)」：声明按案件标识、Idempotency键访问房间消息的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<RoomMessageEntity>」返回。
    // 上游调用：「RoomMessageRepository.findByCaseIdAndIdempotencyKey(String,String)」的上游调用点包括 「HearingCourtBootstrapService.appendAgentMessageIfAbsent」、「HearingCourtOrchestrator.isJudgeTurnComplete」、「HearingCourtOrchestrator.prepareJudgeTurn」、「HearingCourtOrchestrator.persistJudgeTurn」。
    // 下游影响：「RoomMessageRepository.findByCaseIdAndIdempotencyKey(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomMessageRepository.findByCaseIdAndIdempotencyKey(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<RoomMessageEntity> findByCaseIdAndIdempotencyKey(String caseId, String idempotencyKey);
    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomMessageRepository.findAllByRoomIdOrderBySequenceNoAsc(String)」。
    // 具体功能：「RoomMessageRepository.findAllByRoomIdOrderBySequenceNoAsc(String)」：声明按房间标识访问房间消息的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<RoomMessageEntity>」返回。
    // 上游调用：「RoomMessageRepository.findAllByRoomIdOrderBySequenceNoAsc(String)」的上游调用点包括 「EvidenceAgentTurnService.visibleActorScopedConversationMessages」、「IntakeAgentTurnService.recentDialogueMessages」、「RoomMessageService.list」、「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt」。
    // 下游影响：「RoomMessageRepository.findAllByRoomIdOrderBySequenceNoAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomMessageRepository.findAllByRoomIdOrderBySequenceNoAsc(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<RoomMessageEntity> findAllByRoomIdOrderBySequenceNoAsc(String roomId);

    // 所属模块：【房间协作与权限 / 仓储接口层】「RoomMessageRepository.findMaxSequenceByRoomId(String)」。
    // 具体功能：「RoomMessageRepository.findMaxSequenceByRoomId(String)」：声明按较高风险等级序号按房间标识访问房间消息的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「long」返回。
    // 上游调用：「RoomMessageRepository.findMaxSequenceByRoomId(String)」的上游调用点包括 「HearingCourtBootstrapService.appendAgentMessageIfAbsent」、「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCourtOrchestrator.appendJudgeMessage」、「EvidenceAgentTurnService.appendAgentMessage」。
    // 下游影响：「RoomMessageRepository.findMaxSequenceByRoomId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「RoomMessageRepository.findMaxSequenceByRoomId(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query("select coalesce(max(message.sequenceNo), 0) from RoomMessageEntity message where message.roomId = :roomId")
    long findMaxSequenceByRoomId(@Param("roomId") String roomId);
}

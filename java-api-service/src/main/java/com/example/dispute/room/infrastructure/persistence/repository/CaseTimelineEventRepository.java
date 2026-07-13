/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明案件时间线事件在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「findAllByCaseIdOrderBySequenceNoAsc」、「findMaxSequenceByCaseId」、「findByCaseIdAndEventKey」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「CaseTimelineEventRepository」。
// 类型职责：声明案件时间线事件在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「findAllByCaseIdOrderBySequenceNoAsc」、「findMaxSequenceByCaseId」、「findByCaseIdAndEventKey」。
// 协作关系：主要由 「CaseEventService.catchUp」、「CaseEventService.recordLifecycleEvent」、「CaseEventService.recordRoomMessage」、「CaseEventService.replay」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface CaseTimelineEventRepository extends JpaRepository<CaseTimelineEventEntity, String> {
    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseTimelineEventRepository.findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」。
    // 具体功能：「CaseTimelineEventRepository.findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」：声明按案件标识、序号编号GreaterThan访问案件时间线事件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<CaseTimelineEventEntity>」返回。
    // 上游调用：「CaseTimelineEventRepository.findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」的上游调用点包括 「CaseEventService.replay」、「CaseEventService.catchUp」、「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」、「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser」。
    // 下游影响：「CaseTimelineEventRepository.findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseTimelineEventRepository.findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(String,long)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<CaseTimelineEventEntity> findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            String caseId, long sequenceNo);

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseTimelineEventRepository.findAllByCaseIdOrderBySequenceNoAsc(String)」。
    // 具体功能：「CaseTimelineEventRepository.findAllByCaseIdOrderBySequenceNoAsc(String)」：声明按案件标识访问案件时间线事件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<CaseTimelineEventEntity>」返回。
    // 上游调用：「CaseTimelineEventRepository.findAllByCaseIdOrderBySequenceNoAsc(String)」的上游调用点包括 「HearingCollaborationIntegrationTest.secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion」。
    // 下游影响：「CaseTimelineEventRepository.findAllByCaseIdOrderBySequenceNoAsc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseTimelineEventRepository.findAllByCaseIdOrderBySequenceNoAsc(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<CaseTimelineEventEntity> findAllByCaseIdOrderBySequenceNoAsc(String caseId);

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseTimelineEventRepository.findMaxSequenceByCaseId(String)」。
    // 具体功能：「CaseTimelineEventRepository.findMaxSequenceByCaseId(String)」：声明按较高风险等级序号按案件标识访问案件时间线事件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「long」返回。
    // 上游调用：「CaseTimelineEventRepository.findMaxSequenceByCaseId(String)」的上游调用点包括 「CaseEventService.recordRoomMessage」、「CaseEventService.recordLifecycleEvent」、「RoomMessageAndEventServiceTest.hearingPartyTextIsBoundToTheCurrentRoundAndRegisteredAsRoundStatement」、「RoomMessageAndEventServiceTest.hearingEvidenceReferenceIsBoundToTheCurrentRoundWithoutCountingAsRoundStatement」。
    // 下游影响：「CaseTimelineEventRepository.findMaxSequenceByCaseId(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseTimelineEventRepository.findMaxSequenceByCaseId(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @Query("select coalesce(max(event.sequenceNo), 0) from CaseTimelineEventEntity event where event.caseId = :caseId")
    long findMaxSequenceByCaseId(@Param("caseId") String caseId);

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseTimelineEventRepository.findByCaseIdAndEventKey(String,String)」。
    // 具体功能：「CaseTimelineEventRepository.findByCaseIdAndEventKey(String,String)」：声明按案件标识、事件键访问案件时间线事件的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<CaseTimelineEventEntity>」返回。
    // 上游调用：「CaseTimelineEventRepository.findByCaseIdAndEventKey(String,String)」的上游调用点包括 「CaseEventService.recordLifecycleEvent」、「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」、「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」、「HearingCollaborationIntegrationTest.openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap」。
    // 下游影响：「CaseTimelineEventRepository.findByCaseIdAndEventKey(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseTimelineEventRepository.findByCaseIdAndEventKey(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<CaseTimelineEventEntity> findByCaseIdAndEventKey(
            String caseId, String eventKey);
}

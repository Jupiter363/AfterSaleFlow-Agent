/*
 * 所属模块：房间协作与权限。
 * 文件职责：声明案件接待卷宗在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByCaseIdAndRoomType」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【房间协作与权限 / 仓储接口层】类型「CaseIntakeDossierRepository」。
// 类型职责：声明案件接待卷宗在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByCaseIdAndRoomType」。
// 协作关系：主要由 「EvidenceContextEnvelopeFactory.create」、「HearingCourtBootstrapService.bootstrap」、「IntakeAgentTurnService.upsertCurrentDossier」、「IntakeRoomService.acceptedIntakeResultJson」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface CaseIntakeDossierRepository
        extends JpaRepository<CaseIntakeDossierEntity, String> {

    // 所属模块：【房间协作与权限 / 仓储接口层】「CaseIntakeDossierRepository.findByCaseIdAndRoomType(String,RoomType)」。
    // 具体功能：「CaseIntakeDossierRepository.findByCaseIdAndRoomType(String,RoomType)」：声明按案件标识、房间类型访问案件接待卷宗的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<CaseIntakeDossierEntity>」返回。
    // 上游调用：「CaseIntakeDossierRepository.findByCaseIdAndRoomType(String,RoomType)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」、「EvidenceContextEnvelopeFactory.create」、「IntakeAgentTurnService.upsertCurrentDossier」、「IntakeRoomService.acceptedIntakeResultJson」。
    // 下游影响：「CaseIntakeDossierRepository.findByCaseIdAndRoomType(String,RoomType)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseIntakeDossierRepository.findByCaseIdAndRoomType(String,RoomType)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<CaseIntakeDossierEntity> findByCaseIdAndRoomType(
            String caseId, RoomType roomType);
}


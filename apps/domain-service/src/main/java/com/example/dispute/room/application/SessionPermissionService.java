/*
 * 所属模块：房间协作与权限。
 * 文件职责：编排案件房间会话的角色与读写权限规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「require」、「requireCaseRead」、「requireRoomRead」、「requirePartyPrivateSessionRead」、「requireEvidenceSubmit」、「requireHearingParticipate」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.PermissionScope;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import java.util.List;
import org.springframework.stereotype.Service;

// 所属模块：【房间协作与权限 / 应用编排层】类型「SessionPermissionService」。
// 类型职责：编排案件房间会话的角色与读写权限规则、权限校验与事实读写；本类型显式提供 「require」、「requireCaseRead」、「requireRoomRead」、「requirePartyPrivateSessionRead」、「requireEvidenceSubmit」、「requireHearingParticipate」。
// 协作关系：主要由 「AgentRunStreamEventService.requireVisibleRun」、「AgentRunStreamEventService.visibleTo」、「CaseAgentRunController.active」、「CaseEventService.accessSession」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class SessionPermissionService {

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.require(CaseAccessSessionEntity,PermissionScope)」。
    // 具体功能：「SessionPermissionService.require(CaseAccessSessionEntity,PermissionScope)」：强制校验案件房间会话的角色与读写权限；实际协作者为 「session.has」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「SessionPermissionService.require(CaseAccessSessionEntity,PermissionScope)」的上游调用点包括 「RoomMessageService.post」、「RoomMessageService.list」、「SessionPermissionService.requireCaseRead」、「SessionPermissionService.requireRoomRead」。
    // 下游影响：「SessionPermissionService.require(CaseAccessSessionEntity,PermissionScope)」向下依次触达 「session.has」。
    // 系统意义：「SessionPermissionService.require(CaseAccessSessionEntity,PermissionScope)」在“案件房间会话的角色与读写权限”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void require(CaseAccessSessionEntity session, PermissionScope scope) {
        if (session == null || !session.has(scope)) {
            throw new ForbiddenException("access session missing permission " + scope.name());
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.requireCaseRead(CaseAccessSessionEntity)」。
    // 具体功能：「SessionPermissionService.requireCaseRead(CaseAccessSessionEntity)」：强制校验案件Read；实际协作者为 「require」，最终返回「void」。
    // 上游调用：「SessionPermissionService.requireCaseRead(CaseAccessSessionEntity)」的上游调用点包括 「AgentRunStreamEventService.requireVisibleRun」、「CaseEventService.accessSession」、「SessionPermissionService.requireRoomRead」、「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview」。
    // 下游影响：「SessionPermissionService.requireCaseRead(CaseAccessSessionEntity)」向下依次触达 「require」。
    // 系统意义：「SessionPermissionService.requireCaseRead(CaseAccessSessionEntity)」在“案件Read”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void requireCaseRead(CaseAccessSessionEntity session) {
        require(session, PermissionScope.CASE_READ);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.requireRoomRead(CaseAccessSessionEntity,RoomType)」。
    // 具体功能：「SessionPermissionService.requireRoomRead(CaseAccessSessionEntity,RoomType)」：强制校验房间Read；实际协作者为 「requireCaseRead」、「require」，最终返回「void」。
    // 上游调用：「SessionPermissionService.requireRoomRead(CaseAccessSessionEntity,RoomType)」的上游调用点包括 「CaseAgentRunController.active」、「EvidenceAgentTurnService.resolveSession」、「IntakeAgentTurnService.resolveSession」、「RoomMessageService.post」。
    // 下游影响：「SessionPermissionService.requireRoomRead(CaseAccessSessionEntity,RoomType)」向下依次触达 「requireCaseRead」、「require」。
    // 系统意义：「SessionPermissionService.requireRoomRead(CaseAccessSessionEntity,RoomType)」在“房间Read”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void requireRoomRead(CaseAccessSessionEntity session, RoomType roomType) {
        requireCaseRead(session);
        switch (roomType) {
            case INTAKE -> require(session, PermissionScope.INTAKE_PRIVATE_READ);
            case EVIDENCE -> require(session, PermissionScope.EVIDENCE_READ);
            case HEARING -> require(session, PermissionScope.HEARING_READ);
            case REVIEW -> require(session, PermissionScope.REVIEW_READ);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.requirePartyPrivateSessionRead(CaseAccessSessionEntity,String,ActorRole)」。
    // 具体功能：「SessionPermissionService.requirePartyPrivateSessionRead(CaseAccessSessionEntity,String,ActorRole)」：强制校验当事方私有会话Read；实际协作者为 「session.privileged」、「session.getActorRole」、「session.getActorId」、「require」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「SessionPermissionService.requirePartyPrivateSessionRead(CaseAccessSessionEntity,String,ActorRole)」的上游调用点包括 「SessionPermissionServiceTest.partyPrivateSessionReadIsActorSpecificUnlessPrivileged」。
    // 下游影响：「SessionPermissionService.requirePartyPrivateSessionRead(CaseAccessSessionEntity,String,ActorRole)」向下依次触达 「session.privileged」、「session.getActorRole」、「session.getActorId」、「require」。
    // 系统意义：「SessionPermissionService.requirePartyPrivateSessionRead(CaseAccessSessionEntity,String,ActorRole)」在“当事方私有会话Read”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void requirePartyPrivateSessionRead(
            CaseAccessSessionEntity session, String ownerActorId, ActorRole ownerRole) {
        require(session, PermissionScope.INTAKE_PRIVATE_READ);
        if (session.privileged()) {
            return;
        }
        if (session.getActorRole() == ownerRole && session.getActorId().equals(ownerActorId)) {
            return;
        }
        throw new ForbiddenException("actor cannot read another party private session");
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.requireEvidenceSubmit(CaseAccessSessionEntity)」。
    // 具体功能：「SessionPermissionService.requireEvidenceSubmit(CaseAccessSessionEntity)」：强制校验证据Submit；实际协作者为 「require」，最终返回「void」。
    // 上游调用：「SessionPermissionService.requireEvidenceSubmit(CaseAccessSessionEntity)」的上游调用点包括 「EvidenceAgentTurnService.resolveSession」、「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview」。
    // 下游影响：「SessionPermissionService.requireEvidenceSubmit(CaseAccessSessionEntity)」向下依次触达 「require」。
    // 系统意义：「SessionPermissionService.requireEvidenceSubmit(CaseAccessSessionEntity)」在“证据Submit”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void requireEvidenceSubmit(CaseAccessSessionEntity session) {
        require(session, PermissionScope.EVIDENCE_SUBMIT);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.requireHearingParticipate(CaseAccessSessionEntity)」。
    // 具体功能：「SessionPermissionService.requireHearingParticipate(CaseAccessSessionEntity)」：强制校验庭审Participate；实际协作者为 「require」，最终返回「void」。
    // 上游调用：「SessionPermissionService.requireHearingParticipate(CaseAccessSessionEntity)」的上游调用点包括 「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview」。
    // 下游影响：「SessionPermissionService.requireHearingParticipate(CaseAccessSessionEntity)」向下依次触达 「require」。
    // 系统意义：「SessionPermissionService.requireHearingParticipate(CaseAccessSessionEntity)」在“庭审Participate”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void requireHearingParticipate(CaseAccessSessionEntity session) {
        require(session, PermissionScope.HEARING_PARTICIPATE);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.requireReviewRead(CaseAccessSessionEntity)」。
    // 具体功能：「SessionPermissionService.requireReviewRead(CaseAccessSessionEntity)」：强制校验审核Read；实际协作者为 「require」，最终返回「void」。
    // 上游调用：「SessionPermissionService.requireReviewRead(CaseAccessSessionEntity)」的上游调用点包括 「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview」、「SessionPermissionServiceTest.reviewerSessionCanReadAllRoomsAndDecideReviews」。
    // 下游影响：「SessionPermissionService.requireReviewRead(CaseAccessSessionEntity)」向下依次触达 「require」。
    // 系统意义：「SessionPermissionService.requireReviewRead(CaseAccessSessionEntity)」在“审核Read”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void requireReviewRead(CaseAccessSessionEntity session) {
        require(session, PermissionScope.REVIEW_READ);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.requireReviewDecision(CaseAccessSessionEntity)」。
    // 具体功能：「SessionPermissionService.requireReviewDecision(CaseAccessSessionEntity)」：强制校验审核决定；实际协作者为 「require」，最终返回「void」。
    // 上游调用：「SessionPermissionService.requireReviewDecision(CaseAccessSessionEntity)」的上游调用点包括 「SessionPermissionServiceTest.partySessionsCanReadCaseAndParticipateButCannotReview」、「SessionPermissionServiceTest.reviewerSessionCanReadAllRoomsAndDecideReviews」。
    // 下游影响：「SessionPermissionService.requireReviewDecision(CaseAccessSessionEntity)」向下依次触达 「require」。
    // 系统意义：「SessionPermissionService.requireReviewDecision(CaseAccessSessionEntity)」在“审核决定”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void requireReviewDecision(CaseAccessSessionEntity session) {
        require(session, PermissionScope.REVIEW_DECIDE);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「SessionPermissionService.canReadActorAudience(CaseAccessSessionEntity,List)」。
    // 具体功能：「SessionPermissionService.canReadActorAudience(CaseAccessSessionEntity,List)」：判断能否Read操作者受众 JSON；实际协作者为 「session.privileged」、「session.getActorRole」、「session.getActorId」，最终返回「boolean」。
    // 上游调用：「SessionPermissionService.canReadActorAudience(CaseAccessSessionEntity,List)」的上游调用点包括 「AgentRunStreamEventService.visibleTo」、「CaseEventService.visibleTo」、「EvidenceAgentTurnService.visibleToAccessSession」、「RoomMessageService.visibleTo」。
    // 下游影响：「SessionPermissionService.canReadActorAudience(CaseAccessSessionEntity,List)」向下依次触达 「session.privileged」、「session.getActorRole」、「session.getActorId」；计算结果以「boolean」交给调用方。
    // 系统意义：「SessionPermissionService.canReadActorAudience(CaseAccessSessionEntity,List)」负责主链路中的“Read操作者受众 JSON”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public boolean canReadActorAudience(
            CaseAccessSessionEntity session, List<String> audienceActorIds) {
        if (session == null) {
            return false;
        }
        if (audienceActorIds == null || audienceActorIds.isEmpty()) {
            return true;
        }
        return session.privileged()
                || session.getActorRole() == ActorRole.CUSTOMER_SERVICE
                || audienceActorIds.contains(session.getActorId());
    }
}

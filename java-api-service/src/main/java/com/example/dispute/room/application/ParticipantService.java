/*
 * 所属模块：房间协作与权限。
 * 文件职责：编排参与人规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「addInitiator」、「inviteBoth」、「ensureImportedParties」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseParticipantEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

// 所属模块：【房间协作与权限 / 应用编排层】类型「ParticipantService」。
// 类型职责：编排参与人规则、权限校验与事实读写；本类型显式提供 「ParticipantService」、「addInitiator」、「inviteBoth」、「ensureImportedParties」、「participant」、「assertCanConductIntake」。
// 协作关系：主要由 「CaseApplicationService.createNew」、「ExternalCaseImportTransactionService.importDispute」、「ExternalCaseImportTransactionService.restoreExisting」、「IntakeRoomService.confirm」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class ParticipantService {

    private final CaseParticipantRepository repository;

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.ParticipantService(CaseParticipantRepository)」。
    // 具体功能：「ParticipantService.ParticipantService(CaseParticipantRepository)」：通过构造器接收 「repository」(CaseParticipantRepository) 并保存为「ParticipantService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ParticipantService.ParticipantService(CaseParticipantRepository)」的上游创建点包括 「IntakeRoomServiceTest.setUp」。
    // 下游影响：「ParticipantService.ParticipantService(CaseParticipantRepository)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ParticipantService.ParticipantService(CaseParticipantRepository)」负责主链路中的“参与人服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ParticipantService(CaseParticipantRepository repository) {
        this.repository = repository;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.addInitiator(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」。
    // 具体功能：「ParticipantService.addInitiator(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」：添加发起方；实际协作者为 「repository.saveAll」、「dispute.getId」、「initiator.actorId」、「initiator.role」，最终返回「void」。
    // 上游调用：「ParticipantService.addInitiator(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」的上游调用点包括 「CaseApplicationService.createNew」、「IntakeRoomService.confirm」。
    // 下游影响：「ParticipantService.addInitiator(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」向下依次触达 「repository.saveAll」、「dispute.getId」、「initiator.actorId」、「initiator.role」。
    // 系统意义：「ParticipantService.addInitiator(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」负责主链路中的“发起方”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void addInitiator(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        if (!casePartyOwnsCase(dispute, initiator)) {
            assertTrustedIntakeActor(initiator);
            return;
        }
        repository.saveAll(
                List.of(
                        participant(
                                dispute.getId(),
                                initiator.actorId(),
                                initiator.role(),
                                initiator,
                                now)));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.inviteBoth(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」。
    // 具体功能：「ParticipantService.inviteBoth(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」：邀请双方；实际协作者为 「repository.saveAll」、「dispute.getId」、「dispute.getUserId」、「dispute.getMerchantId」，最终返回「void」。
    // 上游调用：「ParticipantService.inviteBoth(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」的上游调用点包括 「IntakeRoomService.confirm」。
    // 下游影响：「ParticipantService.inviteBoth(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」向下依次触达 「repository.saveAll」、「dispute.getId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「ParticipantService.inviteBoth(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」负责主链路中的“双方”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void inviteBoth(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        assertCanConductIntake(dispute, initiator);
        CaseParticipantEntity user =
                participant(
                        dispute.getId(),
                        dispute.getUserId(),
                        ActorRole.USER,
                        initiator,
                        now);
        CaseParticipantEntity merchant =
                participant(
                        dispute.getId(),
                        dispute.getMerchantId(),
                        ActorRole.MERCHANT,
                        initiator,
                        now);
        repository.saveAll(List.of(user, merchant));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.ensureImportedParties(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」。
    // 具体功能：「ParticipantService.ensureImportedParties(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」：确保导入案件参与方：先把新状态写入 PostgreSQL 事实表；实际协作者为 「repository.existsByCaseIdAndActorIdAndParticipantRole」、「repository.save」、「CaseParticipantEntity.invited」、「systemActor.role」；不满足前置条件时抛出 「SecurityException」，最终返回「void」。
    // 上游调用：「ParticipantService.ensureImportedParties(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」、「ExternalCaseImportTransactionService.restoreExisting」。
    // 下游影响：「ParticipantService.ensureImportedParties(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」向下依次触达 「repository.existsByCaseIdAndActorIdAndParticipantRole」、「repository.save」、「CaseParticipantEntity.invited」、「systemActor.role」。
    // 系统意义：「ParticipantService.ensureImportedParties(FulfillmentCaseEntity,AuthenticatedActor,OffsetDateTime)」负责主链路中的“导入案件参与方”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void ensureImportedParties(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor systemActor,
            OffsetDateTime now) {
        if (systemActor.role() != ActorRole.SYSTEM) {
            throw new SecurityException(
                    "external dispute participants require service identity");
        }
        if (!repository.existsByCaseIdAndActorIdAndParticipantRole(
                dispute.getId(), dispute.getUserId(), ActorRole.USER)) {
            repository.save(
                    CaseParticipantEntity.invited(
                            participantId(),
                            dispute.getId(),
                            dispute.getUserId(),
                            ActorRole.USER,
                            now,
                            systemActor.actorId()));
        }
        if (!repository.existsByCaseIdAndActorIdAndParticipantRole(
                dispute.getId(),
                dispute.getMerchantId(),
                ActorRole.MERCHANT)) {
            repository.save(
                    CaseParticipantEntity.invited(
                            participantId(),
                            dispute.getId(),
                            dispute.getMerchantId(),
                            ActorRole.MERCHANT,
                            now,
                            systemActor.actorId()));
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.participant(String,String,ActorRole,AuthenticatedActor,OffsetDateTime)」。
    // 具体功能：「ParticipantService.participant(String,String,ActorRole,AuthenticatedActor,OffsetDateTime)」：构建参与人；实际协作者为 「repository.findByCaseIdAndActorIdAndParticipantRole」、「CaseParticipantEntity.active」、「CaseParticipantEntity.invited」、「existing.isPresent」，最终返回「CaseParticipantEntity」。
    // 上游调用：「ParticipantService.participant(String,String,ActorRole,AuthenticatedActor,OffsetDateTime)」的上游调用点包括 「ParticipantService.addInitiator」、「ParticipantService.inviteBoth」。
    // 下游影响：「ParticipantService.participant(String,String,ActorRole,AuthenticatedActor,OffsetDateTime)」向下依次触达 「repository.findByCaseIdAndActorIdAndParticipantRole」、「CaseParticipantEntity.active」、「CaseParticipantEntity.invited」、「existing.isPresent」；计算结果以「CaseParticipantEntity」交给调用方。
    // 系统意义：「ParticipantService.participant(String,String,ActorRole,AuthenticatedActor,OffsetDateTime)」负责主链路中的“参与人”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private CaseParticipantEntity participant(
            String caseId,
            String actorId,
            ActorRole role,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        var existing =
                repository.findByCaseIdAndActorIdAndParticipantRole(
                        caseId, actorId, role);
        if (existing.isPresent()) {
            CaseParticipantEntity participant = existing.get();
            if (role == initiator.role()
                    && actorId.equals(initiator.actorId())) {
                participant.activate(now, initiator.actorId());
            }
            return participant;
        }
        if (role == initiator.role() && actorId.equals(initiator.actorId())) {
            return CaseParticipantEntity.active(
                    participantId(), caseId, actorId, role, now, initiator.actorId());
        }
        return CaseParticipantEntity.invited(
                participantId(), caseId, actorId, role, now, initiator.actorId());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.assertCanConductIntake(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「ParticipantService.assertCanConductIntake(FulfillmentCaseEntity,AuthenticatedActor)」：断言CanConduct接待；实际协作者为 「casePartyOwnsCase」、「trustedIntakeActor」；不满足前置条件时抛出 「SecurityException」，最终返回「void」。
    // 上游调用：「ParticipantService.assertCanConductIntake(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「ParticipantService.inviteBoth」。
    // 下游影响：「ParticipantService.assertCanConductIntake(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「casePartyOwnsCase」、「trustedIntakeActor」。
    // 系统意义：「ParticipantService.assertCanConductIntake(FulfillmentCaseEntity,AuthenticatedActor)」在“CanConduct接待”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static void assertCanConductIntake(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if (!casePartyOwnsCase(dispute, actor) && !trustedIntakeActor(actor)) {
            throw new SecurityException("intake confirmation requires a case party");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.casePartyOwnsCase(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「ParticipantService.casePartyOwnsCase(FulfillmentCaseEntity,AuthenticatedActor)」：判断案件当事方Owns案件；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」，最终返回「boolean」。
    // 上游调用：「ParticipantService.casePartyOwnsCase(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「ParticipantService.addInitiator」、「ParticipantService.assertCanConductIntake」。
    // 下游影响：「ParticipantService.casePartyOwnsCase(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；计算结果以「boolean」交给调用方。
    // 系统意义：「ParticipantService.casePartyOwnsCase(FulfillmentCaseEntity,AuthenticatedActor)」负责主链路中的“案件当事方Owns案件”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean casePartyOwnsCase(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        return actor.role() == ActorRole.USER
                        && actor.actorId().equals(dispute.getUserId())
                || actor.role() == ActorRole.MERCHANT
                        && actor.actorId().equals(dispute.getMerchantId());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.assertTrustedIntakeActor(AuthenticatedActor)」。
    // 具体功能：「ParticipantService.assertTrustedIntakeActor(AuthenticatedActor)」：断言Trusted接待操作者；实际协作者为 「trustedIntakeActor」；不满足前置条件时抛出 「SecurityException」，最终返回「void」。
    // 上游调用：「ParticipantService.assertTrustedIntakeActor(AuthenticatedActor)」的上游调用点包括 「ParticipantService.addInitiator」。
    // 下游影响：「ParticipantService.assertTrustedIntakeActor(AuthenticatedActor)」向下依次触达 「trustedIntakeActor」。
    // 系统意义：「ParticipantService.assertTrustedIntakeActor(AuthenticatedActor)」在“Trusted接待操作者”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static void assertTrustedIntakeActor(AuthenticatedActor actor) {
        if (!trustedIntakeActor(actor)) {
            throw new SecurityException("intake confirmation requires a case party");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.trustedIntakeActor(AuthenticatedActor)」。
    // 具体功能：「ParticipantService.trustedIntakeActor(AuthenticatedActor)」：判断trusted接待操作者；实际协作者为 「actor.role」，最终返回「boolean」。
    // 上游调用：「ParticipantService.trustedIntakeActor(AuthenticatedActor)」的上游调用点包括 「ParticipantService.assertCanConductIntake」、「ParticipantService.assertTrustedIntakeActor」。
    // 下游影响：「ParticipantService.trustedIntakeActor(AuthenticatedActor)」向下依次触达 「actor.role」；计算结果以「boolean」交给调用方。
    // 系统意义：「ParticipantService.trustedIntakeActor(AuthenticatedActor)」负责主链路中的“trusted接待操作者”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean trustedIntakeActor(AuthenticatedActor actor) {
        return switch (actor.role()) {
            case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
            case USER, MERCHANT -> false;
        };
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「ParticipantService.participantId()」。
    // 具体功能：「ParticipantService.participantId()」：构建参与人标识；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「PART_」、「-」，最终返回「String」。
    // 上游调用：「ParticipantService.participantId()」的上游调用点包括 「ParticipantService.ensureImportedParties」、「ParticipantService.participant」。
    // 下游影响：「ParticipantService.participantId()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「ParticipantService.participantId()」负责主链路中的“参与人标识”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String participantId() {
        return "PART_" + UUID.randomUUID().toString().replace("-", "");
    }
}

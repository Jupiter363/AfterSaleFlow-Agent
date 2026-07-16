/*
 * 所属模块：房间协作与权限。
 * 文件职责：编排房间轮次记忆Query规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「latestAgentMemory」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【房间协作与权限 / 应用编排层】类型「RoomTurnMemoryQueryService」。
// 类型职责：编排房间轮次记忆Query规则、权限校验与事实读写；本类型显式提供 「RoomTurnMemoryQueryService」、「latestAgentMemory」、「isParty」、「supportsPrivateAgentSession」、「agentKey」、「promptProfileId」。
// 协作关系：主要由 「RoomTurnMemoryController.latest」、「RoomAndEventControllerTest.returnsLatestRoomTurnMemoryForTheIntakeScroll」、「RoomTurnMemoryQueryServiceTest.latestAgentMemoryRejectsActorsOutsideTheDispute」、「RoomTurnMemoryQueryServiceTest.latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class RoomTurnMemoryQueryService {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseParticipantRepository participantRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final AccessSessionResolver accessSessionResolver;
    private final AgentSessionResolver agentSessionResolver;
    private final SessionPermissionService permissionService;
    private final IntakeProgressService intakeProgressService;
    private final ObjectMapper objectMapper;

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.RoomTurnMemoryQueryService(FulfillmentCaseRepository,CaseParticipantRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,ObjectMapper)」。
    // 具体功能：「RoomTurnMemoryQueryService.RoomTurnMemoryQueryService(FulfillmentCaseRepository,CaseParticipantRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,ObjectMapper)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「participantRepository」(CaseParticipantRepository)、「memoryRepository」(RoomTurnMemoryRepository)、「intakeDossierRepository」(CaseIntakeDossierRepository)、「accessSessionResolver」(AccessSessionResolver)、「agentSessionResolver」(AgentSessionResolver)、「permissionService」(SessionPermissionService)、「objectMapper」(ObjectMapper) 并保存为「RoomTurnMemoryQueryService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RoomTurnMemoryQueryService.RoomTurnMemoryQueryService(FulfillmentCaseRepository,CaseParticipantRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,ObjectMapper)」的上游创建点包括 「RoomTurnMemoryQueryServiceTest.setUp」。
    // 下游影响：「RoomTurnMemoryQueryService.RoomTurnMemoryQueryService(FulfillmentCaseRepository,CaseParticipantRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RoomTurnMemoryQueryService.RoomTurnMemoryQueryService(FulfillmentCaseRepository,CaseParticipantRepository,RoomTurnMemoryRepository,CaseIntakeDossierRepository,AccessSessionResolver,AgentSessionResolver,SessionPermissionService,ObjectMapper)」负责主链路中的“房间轮次记忆Query服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RoomTurnMemoryQueryService(
            FulfillmentCaseRepository caseRepository,
            CaseParticipantRepository participantRepository,
            RoomTurnMemoryRepository memoryRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            AccessSessionResolver accessSessionResolver,
            AgentSessionResolver agentSessionResolver,
            SessionPermissionService permissionService,
            IntakeProgressService intakeProgressService,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.participantRepository = participantRepository;
        this.memoryRepository = memoryRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.accessSessionResolver = accessSessionResolver;
        this.agentSessionResolver = agentSessionResolver;
        this.permissionService = permissionService;
        this.intakeProgressService = intakeProgressService;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.latestAgentMemory(String,RoomType,AuthenticatedActor)」。
    // 具体功能：「RoomTurnMemoryQueryService.latestAgentMemory(String,RoomType,AuthenticatedActor)」：构建最新版本Agent记忆：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常，再在读取或写入房间前校验参与关系和角色权限；实际协作者为 「caseRepository.findById」、「accessSessionResolver.resolve」、「permissionService.requireRoomRead」、「agentSessionResolver.resolve」；处理的关键状态/协议值包括 「MEMEO_DEFAULT」，最终返回「Optional<RoomTurnMemoryView>」。
    // 上游调用：「RoomTurnMemoryQueryService.latestAgentMemory(String,RoomType,AuthenticatedActor)」的上游调用点包括 「RoomTurnMemoryController.latest」、「RoomAndEventControllerTest.returnsLatestRoomTurnMemoryForTheIntakeScroll」、「RoomTurnMemoryQueryServiceTest.latestAgentMemoryReturnsStructuredScrollSnapshotForCaseOwner」、「RoomTurnMemoryQueryServiceTest.latestAgentMemoryRejectsActorsOutsideTheDispute」。
    // 下游影响：「RoomTurnMemoryQueryService.latestAgentMemory(String,RoomType,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「accessSessionResolver.resolve」、「permissionService.requireRoomRead」、「agentSessionResolver.resolve」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「RoomTurnMemoryQueryService.latestAgentMemory(String,RoomType,AuthenticatedActor)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public Optional<RoomTurnMemoryView> latestAgentMemory(
            String caseId, RoomType roomType, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireRoomRead(accessSession, roomType);
        if (roomType == RoomType.INTAKE) {
            intakeProgressService.assertIntakeRead(dispute, actor);
        }
        if (isParty(actor.role()) && supportsPrivateAgentSession(roomType)) {
            AgentConversationSessionEntity agentSession =
                    agentSessionResolver.resolve(
                            accessSession,
                            roomType,
                            agentKey(roomType),
                            promptProfileId(roomType, actor.role()),
                            "MEMEO_DEFAULT");
            Optional<RoomTurnMemoryView> privateMemory = memoryRepository
                    .findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(
                            agentSession.getId())
                    .map(this::view);
            if (privateMemory.isPresent() || roomType != RoomType.INTAKE) {
                return privateMemory;
            }
            return intakeDossierRepository
                    .findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                    .map(this::dossierOnlyView);
        }
        return memoryRepository
                .findTopByCaseIdAndRoomTypeAndAgentRoleIsNotNullOrderByTurnNoDesc(
                        caseId, roomType)
                .map(this::view);
    }

    private RoomTurnMemoryView dossierOnlyView(CaseIntakeDossierEntity dossier) {
        JsonNode snapshot = readJson(dossier.getDossierJson(), false);
        return new RoomTurnMemoryView(
                dossier.getCaseId(),
                RoomType.INTAKE,
                dossier.getSourceTurnNo(),
                IntakeAgentTurnService.AGENT_ROLE,
                "",
                objectMapper.createObjectNode(),
                snapshot,
                objectMapper.createArrayNode(),
                objectMapper.createObjectNode(),
                intakeDossierView(dossier),
                dossier.getUpdatedAt());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.isParty(ActorRole)」。
    // 具体功能：「RoomTurnMemoryQueryService.isParty(ActorRole)」：判断是否当事方，最终返回「boolean」。
    // 上游调用：「RoomTurnMemoryQueryService.isParty(ActorRole)」的上游调用点包括 「RoomTurnMemoryQueryService.latestAgentMemory」。
    // 下游影响：「RoomTurnMemoryQueryService.isParty(ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「RoomTurnMemoryQueryService.isParty(ActorRole)」负责主链路中的“当事方”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.supportsPrivateAgentSession(RoomType)」。
    // 具体功能：「RoomTurnMemoryQueryService.supportsPrivateAgentSession(RoomType)」：判断是否支持私有Agent会话，最终返回「boolean」。
    // 上游调用：「RoomTurnMemoryQueryService.supportsPrivateAgentSession(RoomType)」的上游调用点包括 「RoomTurnMemoryQueryService.latestAgentMemory」。
    // 下游影响：「RoomTurnMemoryQueryService.supportsPrivateAgentSession(RoomType)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「RoomTurnMemoryQueryService.supportsPrivateAgentSession(RoomType)」负责主链路中的“私有Agent会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean supportsPrivateAgentSession(RoomType roomType) {
        return roomType == RoomType.INTAKE || roomType == RoomType.EVIDENCE;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.agentKey(RoomType)」。
    // 具体功能：「RoomTurnMemoryQueryService.agentKey(RoomType)」：构建Agent键；处理的关键状态/协议值包括 「PRESIDING_JUDGE」、「REVIEW_COPILOT」，最终返回「String」。
    // 上游调用：「RoomTurnMemoryQueryService.agentKey(RoomType)」的上游调用点包括 「RoomTurnMemoryQueryService.latestAgentMemory」、「RoomTurnMemoryQueryService.promptProfileId」。
    // 下游影响：「RoomTurnMemoryQueryService.agentKey(RoomType)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryQueryService.agentKey(RoomType)」负责主链路中的“Agent键”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String agentKey(RoomType roomType) {
        return switch (roomType) {
            case INTAKE -> IntakeAgentTurnService.AGENT_ROLE;
            case EVIDENCE -> EvidenceAgentTurnService.AGENT_ROLE;
            case HEARING -> "PRESIDING_JUDGE";
            case REVIEW -> "REVIEW_COPILOT";
        };
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.promptProfileId(RoomType,ActorRole)」。
    // 具体功能：「RoomTurnMemoryQueryService.promptProfileId(RoomType,ActorRole)」：构建promptProfile标识；实际协作者为 「agentKey」；处理的关键状态/协议值包括 「:」、「:v1」，最终返回「String」。
    // 上游调用：「RoomTurnMemoryQueryService.promptProfileId(RoomType,ActorRole)」的上游调用点包括 「RoomTurnMemoryQueryService.latestAgentMemory」。
    // 下游影响：「RoomTurnMemoryQueryService.promptProfileId(RoomType,ActorRole)」向下依次触达 「agentKey」；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryQueryService.promptProfileId(RoomType,ActorRole)」负责主链路中的“promptProfile标识”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String promptProfileId(RoomType roomType, ActorRole role) {
        return agentKey(roomType) + ":" + role.name() + ":v1";
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「RoomTurnMemoryQueryService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」：断言CanRead；实际协作者为 「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「actor.role」、「actor.actorId」、「dispute.getUserId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「RoomTurnMemoryQueryService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」只由「RoomTurnMemoryQueryService」内部流程使用，负责封装“CanRead”这一步校验、映射或状态转换。
    // 下游影响：「RoomTurnMemoryQueryService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「actor.role」、「actor.actorId」、「dispute.getUserId」。
    // 系统意义：「RoomTurnMemoryQueryService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」在“CanRead”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void assertCanRead(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean privileged =
                switch (actor.role()) {
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                    default -> false;
                };
        boolean owner =
                actor.role() == ActorRole.USER && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        boolean participant =
                participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), actor.actorId(), actor.role());
        if (!privileged && !owner && !participant) {
            throw new ForbiddenException("actor cannot access turn memory");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.view(RoomTurnMemoryEntity)」。
    // 具体功能：「RoomTurnMemoryQueryService.view(RoomTurnMemoryEntity)」：构建视图；实际协作者为 「intakeDossierRepository.findByCaseIdAndRoomType」、「memory.getDossierPatchJson」、「memory.getCaseId」、「memory.getRoomType」；处理的关键状态/协议值包括 「memory_frame」，最终返回「RoomTurnMemoryView」。
    // 上游调用：「RoomTurnMemoryQueryService.view(RoomTurnMemoryEntity)」只由「RoomTurnMemoryQueryService」内部流程使用，负责封装“视图”这一步校验、映射或状态转换。
    // 下游影响：「RoomTurnMemoryQueryService.view(RoomTurnMemoryEntity)」向下依次触达 「intakeDossierRepository.findByCaseIdAndRoomType」、「memory.getDossierPatchJson」、「memory.getCaseId」、「memory.getRoomType」；计算结果以「RoomTurnMemoryView」交给调用方。
    // 系统意义：「RoomTurnMemoryQueryService.view(RoomTurnMemoryEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private RoomTurnMemoryView view(RoomTurnMemoryEntity memory) {
        JsonNode dossierPatch = readJson(memory.getDossierPatchJson(), false);
        return new RoomTurnMemoryView(
                memory.getCaseId(),
                memory.getRoomType(),
                memory.getTurnNo(),
                memory.getAgentRole(),
                memory.getAgentResponse(),
                dossierPatch,
                readJson(memory.getScrollSnapshotJson(), false),
                readJson(memory.getCanvasOperationsJson(), true),
                dossierPatch.path("memory_frame").isMissingNode()
                        ? objectMapper.createObjectNode()
                        : dossierPatch.path("memory_frame"),
                intakeDossierRepository
                        .findByCaseIdAndRoomType(memory.getCaseId(), RoomType.INTAKE)
                        .map(this::intakeDossierView)
                        .orElse(null),
                memory.getCreatedAt());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.intakeDossierView(CaseIntakeDossierEntity)」。
    // 具体功能：「RoomTurnMemoryQueryService.intakeDossierView(CaseIntakeDossierEntity)」：构建接待卷宗视图；实际协作者为 「dossier.getCaseId」、「dossier.getRoomType」、「dossier.getDossierVersion」、「dossier.getDossierJson」，最终返回「CaseIntakeDossierView」。
    // 上游调用：「RoomTurnMemoryQueryService.intakeDossierView(CaseIntakeDossierEntity)」只由「RoomTurnMemoryQueryService」内部流程使用，负责封装“接待卷宗视图”这一步校验、映射或状态转换。
    // 下游影响：「RoomTurnMemoryQueryService.intakeDossierView(CaseIntakeDossierEntity)」向下依次触达 「dossier.getCaseId」、「dossier.getRoomType」、「dossier.getDossierVersion」、「dossier.getDossierJson」；计算结果以「CaseIntakeDossierView」交给调用方。
    // 系统意义：「RoomTurnMemoryQueryService.intakeDossierView(CaseIntakeDossierEntity)」负责主链路中的“接待卷宗视图”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private CaseIntakeDossierView intakeDossierView(
            com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity dossier) {
        return new CaseIntakeDossierView(
                dossier.getCaseId(),
                dossier.getRoomType(),
                dossier.getDossierVersion(),
                readJson(dossier.getDossierJson(), false),
                dossier.getQualityScore(),
                dossier.isReadyForNextStep(),
                dossier.getAdmissionRecommendation(),
                dossier.getSourceTurnNo(),
                dossier.getUpdatedAt());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「RoomTurnMemoryQueryService.readJson(String,boolean)」。
    // 具体功能：「RoomTurnMemoryQueryService.readJson(String,boolean)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.createArrayNode」、「objectMapper.createObjectNode」、「objectMapper.readTree」，最终返回「JsonNode」。
    // 上游调用：「RoomTurnMemoryQueryService.readJson(String,boolean)」的上游调用点包括 「RoomTurnMemoryQueryService.view」、「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「RoomTurnMemoryQueryService.readJson(String,boolean)」向下依次触达 「objectMapper.createArrayNode」、「objectMapper.createObjectNode」、「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「RoomTurnMemoryQueryService.readJson(String,boolean)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode readJson(String json, boolean arrayDefault) {
        if (json == null || json.isBlank()) {
            return arrayDefault ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return arrayDefault ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
        }
    }
}

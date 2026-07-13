/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：编排案件应用规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「create」、「get」、「list」；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【案件受理兼容链路 / 应用编排层】类型「CaseApplicationService」。
// 类型职责：编排案件应用规则、权限校验与事实读写；本类型显式提供 「CaseApplicationService」、「create」、「get」、「list」、「createNew」、「initialIntakeShell」。
// 协作关系：主要由 「DisputeController.create」、「DisputeController.get」、「DisputeController.list」、「CaseApplicationServiceTest.createsDeterministicShellAndStartsTheSingleIntakeAgentTurn」 使用。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class CaseApplicationService {

    private final FulfillmentCaseRepository caseRepository;
    private final AuditLogRepository auditLogRepository;
    private final CaseRoomRepository roomRepository;
    private final ParticipantService participantService;
    private final IntakeAgentTurnService intakeAgentTurnService;
    private final AppProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.CaseApplicationService(FulfillmentCaseRepository,AuditLogRepository,CaseRoomRepository,ParticipantService,IntakeAgentTurnService,AppProperties,Clock,ObjectMapper)」。
    // 具体功能：「CaseApplicationService.CaseApplicationService(FulfillmentCaseRepository,AuditLogRepository,CaseRoomRepository,ParticipantService,IntakeAgentTurnService,AppProperties,Clock,ObjectMapper)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「auditLogRepository」(AuditLogRepository)、「roomRepository」(CaseRoomRepository)、「participantService」(ParticipantService)、「intakeAgentTurnService」(IntakeAgentTurnService)、「properties」(AppProperties)、「clock」(Clock)、「objectMapper」(ObjectMapper) 并保存为「CaseApplicationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseApplicationService.CaseApplicationService(FulfillmentCaseRepository,AuditLogRepository,CaseRoomRepository,ParticipantService,IntakeAgentTurnService,AppProperties,Clock,ObjectMapper)」的上游创建点包括 「CaseApplicationServiceTest.setUp」。
    // 下游影响：「CaseApplicationService.CaseApplicationService(FulfillmentCaseRepository,AuditLogRepository,CaseRoomRepository,ParticipantService,IntakeAgentTurnService,AppProperties,Clock,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseApplicationService.CaseApplicationService(FulfillmentCaseRepository,AuditLogRepository,CaseRoomRepository,ParticipantService,IntakeAgentTurnService,AppProperties,Clock,ObjectMapper)」负责主链路中的“案件应用服务”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseApplicationService(
            FulfillmentCaseRepository caseRepository,
            AuditLogRepository auditLogRepository,
            CaseRoomRepository roomRepository,
            ParticipantService participantService,
            IntakeAgentTurnService intakeAgentTurnService,
            AppProperties properties,
            Clock clock,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.auditLogRepository = auditLogRepository;
        this.roomRepository = roomRepository;
        this.participantService = participantService;
        this.intakeAgentTurnService = intakeAgentTurnService;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.create(CreateCaseCommand,AuthenticatedActor,String,String,String)」。
    // 具体功能：「CaseApplicationService.create(CreateCaseCommand,AuthenticatedActor,String,String,String)」：创建案件：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「caseRepository.findByCreationIdempotencyKey」、「entity.getIntakeResultJson」、「assertCanCreate」、「assertCanRead」，最终返回「CaseView」。
    // 上游调用：「CaseApplicationService.create(CreateCaseCommand,AuthenticatedActor,String,String,String)」的上游调用点包括 「DisputeController.create」、「DisputeControllerTest.createsDisputeThroughTheUnversionedFinalApi」、「CaseApplicationServiceTest.createsDeterministicShellAndStartsTheSingleIntakeAgentTurn」、「CaseApplicationServiceTest.structuredClaimResolutionSeedIsPassedToTheIntakeAgentWithoutExecutingTools」。
    // 下游影响：「CaseApplicationService.create(CreateCaseCommand,AuthenticatedActor,String,String,String)」向下依次触达 「caseRepository.findByCreationIdempotencyKey」、「entity.getIntakeResultJson」、「assertCanCreate」、「assertCanRead」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseApplicationService.create(CreateCaseCommand,AuthenticatedActor,String,String,String)」定义原子提交边界；接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public CaseView create(
            CreateCaseCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        assertCanCreate(command, actor);
        return caseRepository
                .findByCreationIdempotencyKey(idempotencyKey)
                .map(
                        entity -> {
                            assertCanRead(entity, actor);
                            return toView(
                                    entity,
                                    readSnapshot(entity.getIntakeResultJson()));
                        })
                .orElseGet(
                        () ->
                                createNew(
                                        command,
                                        actor,
                                        idempotencyKey,
                                        traceId,
                                        requestId));
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.get(String,AuthenticatedActor)」。
    // 具体功能：「CaseApplicationService.get(String,AuthenticatedActor)」：读取案件：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「entity.getIntakeResultJson」、「assertCanRead」、「toView」；处理的关键状态/协议值包括 「case_id」，最终返回「CaseView」。
    // 上游调用：「CaseApplicationService.get(String,AuthenticatedActor)」的上游调用点包括 「DisputeController.get」、「DisputeControllerTest.readsAndListsDisputesThroughFinalPaths」、「CaseApplicationServiceTest.notifiedCounterpartyCanReadMerchantInitiatedCaseButInitiatorRoleStaysMerchant」、「CaseApplicationServiceTest.normalizesLegacyFulfillmentDisputesToTheFinalPublicCaseType」。
    // 下游影响：「CaseApplicationService.get(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「entity.getIntakeResultJson」、「assertCanRead」、「toView」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseApplicationService.get(String,AuthenticatedActor)」定义原子提交边界；接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public CaseView get(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity entity =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        assertCanRead(entity, actor);
        return toView(entity, readSnapshot(entity.getIntakeResultJson()));
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.list(CaseStatus,String,int,int,AuthenticatedActor)」。
    // 具体功能：「CaseApplicationService.list(CaseStatus,String,int,int,AuthenticatedActor)」：列出案件Page：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「caseRepository.findAll」、「Sort.by」、「criteria.and」、「criteria.isNull」；处理的关键状态/协议值包括 「deletedAt」、「caseType」、「DISPUTE」、「FULFILLMENT_DISPUTE」，最终返回「CasePageView」。
    // 上游调用：「CaseApplicationService.list(CaseStatus,String,int,int,AuthenticatedActor)」的上游调用点包括 「DisputeController.list」、「DisputeControllerTest.readsAndListsDisputesThroughFinalPaths」。
    // 下游影响：「CaseApplicationService.list(CaseStatus,String,int,int,AuthenticatedActor)」向下依次触达 「caseRepository.findAll」、「Sort.by」、「criteria.and」、「criteria.isNull」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseApplicationService.list(CaseStatus,String,int,int,AuthenticatedActor)」定义原子提交边界；接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public CasePageView list(
            CaseStatus status,
            String disputeType,
            int page,
            int size,
            AuthenticatedActor actor) {
        Specification<FulfillmentCaseEntity> specification =
                (root, query, criteria) ->
                        criteria.and(
                                criteria.isNull(root.get("deletedAt")),
                                criteria.or(
                                        criteria.equal(
                                                root.get("caseType"),
                                                "DISPUTE"),
                                        criteria.equal(
                                                root.get("caseType"),
                                                "FULFILLMENT_DISPUTE"),
                                        criteria.and(
                                                criteria.equal(
                                                        root.get("caseType"),
                                                        "TRANSFERRED"),
                                                criteria.equal(
                                                        root.get("sourceType"),
                                                        CaseSourceType.INTAKE_CREATED))));
        if (status != null) {
            specification =
                    specification.and(
                            (root, query, criteria) ->
                                    criteria.equal(root.get("caseStatus"), status));
        }
        if (disputeType != null && !disputeType.isBlank()) {
            specification =
                    specification.and(
                            (root, query, criteria) ->
                                    criteria.equal(
                                            root.get("disputeType"),
                                            disputeType));
        }
        specification =
                switch (actor.role()) {
                    case USER ->
                            specification.and(
                                    (root, query, criteria) ->
                                            criteria.equal(
                                                    root.get("userId"),
                                                    actor.actorId()));
                    case MERCHANT ->
                            specification.and(
                                    (root, query, criteria) ->
                                            criteria.equal(
                                                    root.get("merchantId"),
                                                    actor.actorId()));
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM ->
                            specification;
                };
        Page<FulfillmentCaseEntity> result =
                caseRepository.findAll(
                        specification,
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Direction.DESC, "createdAt")));
        return new CasePageView(
                result.getContent().stream()
                        .map(
                                entity ->
                                        toView(
                                                entity,
                                                readSnapshot(
                                                        entity.getIntakeResultJson())))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.createNew(CreateCaseCommand,AuthenticatedActor,String,String,String)」。
    // 具体功能：「CaseApplicationService.createNew(CreateCaseCommand,AuthenticatedActor,String,String,String)」：创建新案件：先把新状态写入 PostgreSQL 事实表；实际协作者为 「caseRepository.save」、「roomRepository.save」、「participantService.addInitiator」、「intakeAgentTurnService.startInitialTurn」；处理的关键状态/协议值包括 「CASE_」、「userId」、「merchantId」、「idempotencyKey」，最终返回「CaseView」。
    // 上游调用：「CaseApplicationService.createNew(CreateCaseCommand,AuthenticatedActor,String,String,String)」的上游调用点包括 「CaseApplicationService.create」。
    // 下游影响：「CaseApplicationService.createNew(CreateCaseCommand,AuthenticatedActor,String,String,String)」向下依次触达 「caseRepository.save」、「roomRepository.save」、「participantService.addInitiator」、「intakeAgentTurnService.startInitialTurn」；计算结果以「CaseView」交给调用方。
    // 系统意义：「CaseApplicationService.createNew(CreateCaseCommand,AuthenticatedActor,String,String,String)」负责主链路中的“新案件”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private CaseView createNew(
            CreateCaseCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        // Case creation persists only the submitted form and trusted references.
        // The first and only model-backed interpretation is the streamed
        // INTAKE_TURN started below. Calling the legacy intake analyzer here used
        // to create two competing summaries before the user entered the room.
        IntakeAnalysis analysis = initialIntakeShell(command);

        List<String> missingSlots =
                command.orderId() == null || command.orderId().isBlank()
                        ? mergeOrderSlot(analysis.missingSlots())
                        : analysis.missingSlots();
        CaseStatus status =
                missingSlots.isEmpty()
                        ? CaseStatus.INTAKE_COMPLETED
                        : CaseStatus.WAITING_SLOT_COMPLETION;
        IntakeSnapshot snapshot =
                new IntakeSnapshot(
                        analysis.potentialDispute(), missingSlots, false, clock.instant());
        String snapshotJson = writeJson(snapshot);
        String caseId = "CASE_" + compactUuid();
        FulfillmentCaseEntity entity =
                FulfillmentCaseEntity.create(
                        caseId,
                        blankToNull(command.orderId()),
                        blankToNull(command.afterSaleId()),
                        blankToNull(command.logisticsId()),
                        required(command.userId(), "userId"),
                        required(command.merchantId(), "merchantId"),
                        command.initiatorRole(),
                        required(idempotencyKey, "idempotencyKey"),
                        required(analysis.caseType(), "caseType"),
                        required(analysis.title(), "title"),
                        required(analysis.normalizedDescription(), "normalizedDescription"),
                        analysis.riskLevel(),
                        actor.actorId());
        entity.completeIntake(
                analysis.disputeType(),
                status,
                analysis.riskLevel(),
                snapshotJson,
                actor.actorId());
        FulfillmentCaseEntity saved = caseRepository.save(entity);
        OffsetDateTime now = OffsetDateTime.now(clock);
        roomRepository.save(
                CaseRoomEntity.open(
                        "ROOM_" + compactUuid(),
                        caseId,
                        RoomType.INTAKE,
                        now,
                        actor.actorId()));
        if (actor.role() == ActorRole.USER
                || actor.role() == ActorRole.MERCHANT) {
            participantService.addInitiator(saved, actor, now);
        }
        intakeAgentTurnService.startInitialTurn(
                saved.getId(),
                actor,
                new IntakeLobbySeed(
                        command.orderId(),
                        command.afterSaleId(),
                        command.logisticsId(),
                        intakeInitiatorRole(actor.role(), command.initiatorRole()),
                        command.description(),
                        null,
                        command.claimResolutionSeed(),
                        command.respondentAttitudeSeed()),
                traceId,
                requestId);

        if (properties.logging().auditEnabled()) {
            auditLogRepository.save(
                    AuditLogEntity.caseCreated(
                            "AUDIT_" + compactUuid(),
                            caseId,
                            traceId,
                            requestId,
                            actor.actorId(),
                            actor.role().name(),
                            writeJson(Map.of("case_status", status.name()))));
        }
        return toView(saved, snapshot);
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.initialIntakeShell(CreateCaseCommand)」。
    // 具体功能：「CaseApplicationService.initialIntakeShell(CreateCaseCommand)」：构建initial接待Shell；实际协作者为 「command.orderId」、「command.description」、「required」；处理的关键状态/协议值包括 「ORDER_ID」、「DISPUTE」、「FULFILLMENT_CONFLICT」、「履约争议待核实」，最终返回「IntakeAnalysis」。
    // 上游调用：「CaseApplicationService.initialIntakeShell(CreateCaseCommand)」的上游调用点包括 「CaseApplicationService.createNew」。
    // 下游影响：「CaseApplicationService.initialIntakeShell(CreateCaseCommand)」向下依次触达 「command.orderId」、「command.description」、「required」；计算结果以「IntakeAnalysis」交给调用方。
    // 系统意义：「CaseApplicationService.initialIntakeShell(CreateCaseCommand)」负责主链路中的“initial接待Shell”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static IntakeAnalysis initialIntakeShell(CreateCaseCommand command) {
        List<String> missing =
                command.orderId() == null || command.orderId().isBlank()
                        ? List.of("ORDER_ID")
                        : List.of();
        return new IntakeAnalysis(
                "DISPUTE",
                "FULFILLMENT_CONFLICT",
                RiskLevel.MEDIUM,
                true,
                missing,
                "履约争议待核实",
                required(command.description(), "description"));
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.mergeOrderSlot(List)」。
    // 具体功能：「CaseApplicationService.mergeOrderSlot(List)」：合并顺序Slot；处理的关键状态/协议值包括 「ORDER_ID」，最终返回「List<String>」。
    // 上游调用：「CaseApplicationService.mergeOrderSlot(List)」的上游调用点包括 「CaseApplicationService.createNew」。
    // 下游影响：「CaseApplicationService.mergeOrderSlot(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「List<String>」交给调用方。
    // 系统意义：「CaseApplicationService.mergeOrderSlot(List)」负责主链路中的“顺序Slot”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static List<String> mergeOrderSlot(List<String> slots) {
        if (slots.contains("ORDER_ID")) {
            return slots;
        }
        java.util.ArrayList<String> merged = new java.util.ArrayList<>(slots);
        merged.add("ORDER_ID");
        return List.copyOf(merged);
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.intakeInitiatorRole(ActorRole)」。
    // 具体功能：「CaseApplicationService.intakeInitiatorRole(ActorRole)」：提供「intakeInitiatorRole」的便捷重载：接收 「role」(ActorRole)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「CaseApplicationService.intakeInitiatorRole(ActorRole)」的上游调用点包括 「CaseApplicationService.createNew」、「CaseApplicationService.intakeInitiatorRole」。
    // 下游影响：「CaseApplicationService.intakeInitiatorRole(ActorRole)」向下依次触达 「intakeInitiatorRole」；计算结果以「String」交给调用方。
    // 系统意义：「CaseApplicationService.intakeInitiatorRole(ActorRole)」负责主链路中的“接待发起方角色”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static String intakeInitiatorRole(ActorRole role) {
        return intakeInitiatorRole(role, ActorRole.USER);
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.intakeInitiatorRole(ActorRole,ActorRole)」。
    // 具体功能：「CaseApplicationService.intakeInitiatorRole(ActorRole,ActorRole)」：构建接待发起方角色，最终返回「String」。
    // 上游调用：「CaseApplicationService.intakeInitiatorRole(ActorRole,ActorRole)」的上游调用点包括 「CaseApplicationService.createNew」、「CaseApplicationService.intakeInitiatorRole」。
    // 下游影响：「CaseApplicationService.intakeInitiatorRole(ActorRole,ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseApplicationService.intakeInitiatorRole(ActorRole,ActorRole)」负责主链路中的“接待发起方角色”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static String intakeInitiatorRole(ActorRole role, ActorRole requestedInitiatorRole) {
        return switch (role) {
            case USER, MERCHANT -> role.name();
            case CUSTOMER_SERVICE, SYSTEM -> requestedInitiatorRole.name();
            case PLATFORM_REVIEWER, ADMIN -> ActorRole.SYSTEM.name();
        };
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「CaseApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」：断言CanRead；实际协作者为 「actor.role」、「actor.actorId」、「entity.getUserId」、「entity.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「CaseApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「CaseApplicationService.create」、「CaseApplicationService.get」。
    // 下游影响：「CaseApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「entity.getUserId」、「entity.getMerchantId」。
    // 系统意义：「CaseApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」在“CanRead”进入下游前阻断非法状态；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private void assertCanRead(FulfillmentCaseEntity entity, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(entity.getUserId());
                    case MERCHANT -> actor.actorId().equals(entity.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot access this case");
        }
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.assertCanCreate(CreateCaseCommand,AuthenticatedActor)」。
    // 具体功能：「CaseApplicationService.assertCanCreate(CreateCaseCommand,AuthenticatedActor)」：断言CanCreate；实际协作者为 「actor.role」、「actor.actorId」、「command.userId」、「command.merchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「CaseApplicationService.assertCanCreate(CreateCaseCommand,AuthenticatedActor)」的上游调用点包括 「CaseApplicationService.create」。
    // 下游影响：「CaseApplicationService.assertCanCreate(CreateCaseCommand,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「command.userId」、「command.merchantId」。
    // 系统意义：「CaseApplicationService.assertCanCreate(CreateCaseCommand,AuthenticatedActor)」在“CanCreate”进入下游前阻断非法状态；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static void assertCanCreate(
            CreateCaseCommand command, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(command.userId());
                    case MERCHANT -> actor.actorId().equals(command.merchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot create a case for this party");
        }
        if ((actor.role() == ActorRole.USER || actor.role() == ActorRole.MERCHANT)
                && command.initiatorRole() != actor.role()) {
            throw new ForbiddenException("party actor must be the intake initiator");
        }
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.toView(FulfillmentCaseEntity,IntakeSnapshot)」。
    // 具体功能：「CaseApplicationService.toView(FulfillmentCaseEntity,IntakeSnapshot)」：转换视图；实际协作者为 「entity.getId」、「entity.getOrderId」、「entity.getAfterSaleId」、「entity.getLogisticsId」，最终返回「CaseView」。
    // 上游调用：「CaseApplicationService.toView(FulfillmentCaseEntity,IntakeSnapshot)」的上游调用点包括 「CaseApplicationService.create」、「CaseApplicationService.get」、「CaseApplicationService.list」、「CaseApplicationService.createNew」。
    // 下游影响：「CaseApplicationService.toView(FulfillmentCaseEntity,IntakeSnapshot)」向下依次触达 「entity.getId」、「entity.getOrderId」、「entity.getAfterSaleId」、「entity.getLogisticsId」；计算结果以「CaseView」交给调用方。
    // 系统意义：「CaseApplicationService.toView(FulfillmentCaseEntity,IntakeSnapshot)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private CaseView toView(FulfillmentCaseEntity entity, IntakeSnapshot snapshot) {
        return new CaseView(
                entity.getId(),
                entity.getOrderId(),
                entity.getAfterSaleId(),
                entity.getLogisticsId(),
                entity.getUserId(),
                entity.getMerchantId(),
                publicCaseType(entity.getCaseType()),
                entity.getDisputeType(),
                entity.getCaseStatus(),
                entity.getRouteType(),
                entity.getRiskLevel(),
                entity.getTitle(),
                entity.getDescription(),
                snapshot.potentialDispute(),
                snapshot.missingSlots(),
                snapshot.agentDegraded(),
                entity.getSourceType(),
                entity.getSourceSystem(),
                entity.getExternalCaseRef(),
                entity.getCurrentRoom(),
                entity.getCurrentDeadlineAt(),
                pendingAction(entity.getCaseStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getClosedAt(),
                entity.getInitiatorRole());
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.publicCaseType(String)」。
    // 具体功能：「CaseApplicationService.publicCaseType(String)」：构建公开案件类型；处理的关键状态/协议值包括 「FULFILLMENT_DISPUTE」、「DISPUTE」，最终返回「String」。
    // 上游调用：「CaseApplicationService.publicCaseType(String)」的上游调用点包括 「CaseApplicationService.toView」。
    // 下游影响：「CaseApplicationService.publicCaseType(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseApplicationService.publicCaseType(String)」负责主链路中的“公开案件类型”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static String publicCaseType(String caseType) {
        return "FULFILLMENT_DISPUTE".equals(caseType)
                ? "DISPUTE"
                : caseType;
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.pendingAction(CaseStatus)」。
    // 具体功能：「CaseApplicationService.pendingAction(CaseStatus)」：构建待处理动作；处理的关键状态/协议值包括 「COMPLETE_INTAKE」、「SUBMIT_EVIDENCE」、「ENTER_HEARING」、「PARTICIPATE_HEARING」，最终返回「String」。
    // 上游调用：「CaseApplicationService.pendingAction(CaseStatus)」的上游调用点包括 「CaseApplicationService.toView」。
    // 下游影响：「CaseApplicationService.pendingAction(CaseStatus)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseApplicationService.pendingAction(CaseStatus)」负责主链路中的“待处理动作”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static String pendingAction(CaseStatus status) {
        return switch (status) {
            case INTAKE_PENDING,
                    INTAKE_IN_PROGRESS,
                    WAITING_SLOT_COMPLETION,
                    INTAKE_COMPLETED -> "COMPLETE_INTAKE";
            case EVIDENCE_OPEN, WAITING_EVIDENCE -> "SUBMIT_EVIDENCE";
            case EVIDENCE_SEALED -> "ENTER_HEARING";
            case HEARING_OPEN, HEARING -> "PARTICIPATE_HEARING";
            case SETTLEMENT_PENDING -> "REVIEW_SETTLEMENT";
            case DRAFT_READY,
                    DELIBERATION_RUNNING,
                    REVIEW_PENDING,
                    WAITING_HUMAN_REVIEW,
                    REMEDY_PLANNED -> "AWAIT_REVIEW";
            case APPROVED_FOR_EXECUTION, EXECUTING -> "TRACK_EXECUTION";
            case CLOSED, CANCELLED, MANUAL_HANDOFF, NOT_ADMISSIBLE -> "VIEW_OUTCOME";
            default -> "CONTINUE_CASE";
        };
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.readSnapshot(String)」。
    // 具体功能：「CaseApplicationService.readSnapshot(String)」：读取快照；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「IntakeSnapshot」。
    // 上游调用：「CaseApplicationService.readSnapshot(String)」的上游调用点包括 「CaseApplicationService.create」、「CaseApplicationService.get」、「CaseApplicationService.list」。
    // 下游影响：「CaseApplicationService.readSnapshot(String)」向下依次触达 「objectMapper.readValue」；计算结果以「IntakeSnapshot」交给调用方。
    // 系统意义：「CaseApplicationService.readSnapshot(String)」统一“快照”的跨层表示，避免不同入口产生不兼容字段；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private IntakeSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, IntakeSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted intake snapshot", exception);
        }
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.writeJson(Object)」。
    // 具体功能：「CaseApplicationService.writeJson(Object)」：写入JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「CaseApplicationService.writeJson(Object)」的上游调用点包括 「CaseApplicationService.createNew」。
    // 下游影响：「CaseApplicationService.writeJson(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「CaseApplicationService.writeJson(Object)」负责主链路中的“JSON”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize intake data", exception);
        }
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.compactUuid()」。
    // 具体功能：「CaseApplicationService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「CaseApplicationService.compactUuid()」的上游调用点包括 「CaseApplicationService.createNew」。
    // 下游影响：「CaseApplicationService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「CaseApplicationService.compactUuid()」负责主链路中的“UUID”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.required(String,String)」。
    // 具体功能：「CaseApplicationService.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「CaseApplicationService.required(String,String)」的上游调用点包括 「CaseApplicationService.createNew」、「CaseApplicationService.initialIntakeShell」。
    // 下游影响：「CaseApplicationService.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseApplicationService.required(String,String)」在“字符串”进入下游前阻断非法状态；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】「CaseApplicationService.blankToNull(String)」。
    // 具体功能：「CaseApplicationService.blankToNull(String)」：判断空白值空值，最终返回「String」。
    // 上游调用：「CaseApplicationService.blankToNull(String)」的上游调用点包括 「CaseApplicationService.createNew」。
    // 下游影响：「CaseApplicationService.blankToNull(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseApplicationService.blankToNull(String)」负责主链路中的“空值”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // 所属模块：【案件受理兼容链路 / 应用编排层】类型「IntakeSnapshot」。
    // 类型职责：定义接待快照跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record IntakeSnapshot(
            boolean potentialDispute,
            List<String> missingSlots,
            boolean agentDegraded,
            java.time.Instant analyzedAt) {}
}

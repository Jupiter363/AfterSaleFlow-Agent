/*
 * 所属模块：案件核心与导入。
 * 文件职责：编排外部案件导入Transaction规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「simulateExternalImport」、「importDispute」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.casecore.infrastructure.persistence.entity.SimulatedImportTemplateCursorEntity;
import com.example.dispute.casecore.infrastructure.persistence.repository.SimulatedImportTemplateCursorRepository;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.hearing.application.HearingImportedCaseInitializer;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeCaseSeedMetadata;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.ActivateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.RoomEpochAllocation;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TerminalRoomEpoch;
import com.example.dispute.workflow.contract.v1.ContractTypes;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically imports one dispute candidate from a trusted platform adapter.
 *
 * <p>The external source pair is the business idempotency key; request keys may
 * change across adapter retries and therefore cannot be the sole identity.
 */
// 所属模块：【案件核心与导入 / 应用编排层】类型「ExternalCaseImportTransactionService」。
// 类型职责：编排外部案件导入Transaction规则、权限校验与事实读写；本类型显式提供 「ExternalCaseImportTransactionService」、「simulateExternalImport」、「importDispute」、「restoreExisting」、「startIntakeIfNeeded」、「materializeCurrentRoom」。
// 协作关系：主要由 「DisputeImportService.importOne」、「DisputeImportService.simulateExternalImport」、「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates」、「DisputeImportServiceTest.setUp」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class ExternalCaseImportTransactionService {

    private final FulfillmentCaseRepository repository;
    private final CaseRoomRepository roomRepository;
    private final CasePhaseClockRepository clockRepository;
    private final ParticipantService participantService;
    private final IntakeAgentTurnService intakeAgentTurnService;
    private final SimulatedImportTemplateCursorRepository simulatedImportCursorRepository;
    private final SimulatedExternalDisputeTemplateCatalog simulatedImportTemplates;
    private final PostCommitSideEffectExecutor postCommit;
    private final RoomEpochAllocator roomEpochAllocator;
    private final HearingImportedCaseInitializer hearingInitializer;
    private final DisputeProperties properties;
    private final Clock clock;
    private final ImportedCaseIdFactory caseIdFactory;

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.ExternalCaseImportTransactionService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,ParticipantService,IntakeAgentTurnService,SimulatedImportTemplateCursorRepository,SimulatedExternalDisputeTemplateCatalog,PostCommitSideEffectExecutor,DisputeProperties,Clock)」。
    // 具体功能：「ExternalCaseImportTransactionService.ExternalCaseImportTransactionService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,ParticipantService,IntakeAgentTurnService,SimulatedImportTemplateCursorRepository,SimulatedExternalDisputeTemplateCatalog,PostCommitSideEffectExecutor,DisputeProperties,Clock)」：通过构造器接收 「repository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「clockRepository」(CasePhaseClockRepository)、「participantService」(ParticipantService)、「intakeAgentTurnService」(IntakeAgentTurnService)、「simulatedImportCursorRepository」(SimulatedImportTemplateCursorRepository)、「simulatedImportTemplates」(SimulatedExternalDisputeTemplateCatalog)、「postCommit」(PostCommitSideEffectExecutor)、「properties」(DisputeProperties)、「clock」(Clock) 并保存为「ExternalCaseImportTransactionService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ExternalCaseImportTransactionService.ExternalCaseImportTransactionService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,ParticipantService,IntakeAgentTurnService,SimulatedImportTemplateCursorRepository,SimulatedExternalDisputeTemplateCatalog,PostCommitSideEffectExecutor,DisputeProperties,Clock)」的上游创建点包括 「DisputeImportServiceTest.setUp」、「SimulatedExternalImportTemplateCycleTest.transactionService」。
    // 下游影响：「ExternalCaseImportTransactionService.ExternalCaseImportTransactionService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,ParticipantService,IntakeAgentTurnService,SimulatedImportTemplateCursorRepository,SimulatedExternalDisputeTemplateCatalog,PostCommitSideEffectExecutor,DisputeProperties,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ExternalCaseImportTransactionService.ExternalCaseImportTransactionService(FulfillmentCaseRepository,CaseRoomRepository,CasePhaseClockRepository,ParticipantService,IntakeAgentTurnService,SimulatedImportTemplateCursorRepository,SimulatedExternalDisputeTemplateCatalog,PostCommitSideEffectExecutor,DisputeProperties,Clock)」负责主链路中的“外部案件导入Transaction服务”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    @Autowired
    public ExternalCaseImportTransactionService(
            FulfillmentCaseRepository repository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository clockRepository,
            ParticipantService participantService,
            IntakeAgentTurnService intakeAgentTurnService,
            SimulatedImportTemplateCursorRepository simulatedImportCursorRepository,
            SimulatedExternalDisputeTemplateCatalog simulatedImportTemplates,
            PostCommitSideEffectExecutor postCommit,
            RoomEpochAllocator roomEpochAllocator,
            HearingImportedCaseInitializer hearingInitializer,
            DisputeProperties properties,
            Clock clock,
            ObjectProvider<ImportedCaseIdFactory> caseIdFactoryProvider) {
        this(
                repository,
                roomRepository,
                clockRepository,
                participantService,
                intakeAgentTurnService,
                simulatedImportCursorRepository,
                simulatedImportTemplates,
                postCommit,
                roomEpochAllocator,
                hearingInitializer,
                properties,
                clock,
                caseIdFactoryProvider.getIfUnique(
                        () -> () -> "CASE_" + compactUuid()));
    }

    public ExternalCaseImportTransactionService(
            FulfillmentCaseRepository repository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository clockRepository,
            ParticipantService participantService,
            IntakeAgentTurnService intakeAgentTurnService,
            SimulatedImportTemplateCursorRepository simulatedImportCursorRepository,
            SimulatedExternalDisputeTemplateCatalog simulatedImportTemplates,
            PostCommitSideEffectExecutor postCommit,
            RoomEpochAllocator roomEpochAllocator,
            HearingImportedCaseInitializer hearingInitializer,
            DisputeProperties properties,
            Clock clock) {
        this(
                repository,
                roomRepository,
                clockRepository,
                participantService,
                intakeAgentTurnService,
                simulatedImportCursorRepository,
                simulatedImportTemplates,
                postCommit,
                roomEpochAllocator,
                hearingInitializer,
                properties,
                clock,
                () -> "CASE_" + compactUuid());
    }

    private ExternalCaseImportTransactionService(
            FulfillmentCaseRepository repository,
            CaseRoomRepository roomRepository,
            CasePhaseClockRepository clockRepository,
            ParticipantService participantService,
            IntakeAgentTurnService intakeAgentTurnService,
            SimulatedImportTemplateCursorRepository simulatedImportCursorRepository,
            SimulatedExternalDisputeTemplateCatalog simulatedImportTemplates,
            PostCommitSideEffectExecutor postCommit,
            RoomEpochAllocator roomEpochAllocator,
            HearingImportedCaseInitializer hearingInitializer,
            DisputeProperties properties,
            Clock clock,
            ImportedCaseIdFactory caseIdFactory) {
        this.repository = repository;
        this.roomRepository = roomRepository;
        this.clockRepository = clockRepository;
        this.participantService = participantService;
        this.intakeAgentTurnService = intakeAgentTurnService;
        this.simulatedImportCursorRepository = simulatedImportCursorRepository;
        this.simulatedImportTemplates = simulatedImportTemplates;
        this.postCommit = postCommit;
        this.roomEpochAllocator = roomEpochAllocator;
        this.hearingInitializer = hearingInitializer;
        this.properties = properties;
        this.clock = clock;
        this.caseIdFactory = Objects.requireNonNull(caseIdFactory, "caseIdFactory");
    }

    /**
     * Imports the next deterministic demo case and advances the shared cursor atomically.
     *
     * <p>Creation-key replay is resolved before locking the cursor, so retries return the
     * original case without consuming another template.
     */
    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」：模拟外部导入：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「repository.findByCreationIdempotencyKey」、「simulatedImportCursorRepository.findByIdForUpdate」、「simulatedImportCursorRepository.save」、「actor.role」；不满足前置条件时抛出 「SecurityException」；处理的关键状态/协议值包括 「idempotencyKey」，最终返回「SimulatedImportResultView」。
    // 上游调用：「ExternalCaseImportTransactionService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」的上游调用点包括 「DisputeImportService.simulateExternalImport」、「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates」。
    // 下游影响：「ExternalCaseImportTransactionService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」向下依次触达 「repository.findByCreationIdempotencyKey」、「simulatedImportCursorRepository.findByIdForUpdate」、「simulatedImportCursorRepository.save」、「actor.role」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ExternalCaseImportTransactionService.simulateExternalImport(SimulateExternalImportCommand,AuthenticatedActor,String,String,String)」定义原子提交边界；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public SimulatedImportResultView simulateExternalImport(
            SimulateExternalImportCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("external dispute simulation requires service identity");
        }
        requireText(idempotencyKey, "idempotencyKey");
        var replay = repository.findByCreationIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            FulfillmentCaseEntity existing = replay.orElseThrow();
            assertSimulationReplayMatches(existing, command, idempotencyKey);
            return new SimulatedImportResultView(List.of(restoreExisting(existing, actor)));
        }

        SimulatedImportTemplateCursorEntity cursor =
                simulatedImportCursorRepository
                        .findByIdForUpdate(SimulatedImportTemplateCursorEntity.CURSOR_ID)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "simulated import template cursor is missing"));
        SimulatedExternalDisputeTemplate template =
                simulatedImportTemplates.get(cursor.getNextTemplateNo());
        ImportDisputeCommand importCommand = simulatedCommand(command, template, idempotencyKey);
        ImportedDisputeView imported =
                importDispute(importCommand, actor, idempotencyKey, traceId, requestId);

        cursor.advance(simulatedImportTemplates.size());
        simulatedImportCursorRepository.save(cursor);
        return new SimulatedImportResultView(List.of(imported));
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」：导入争议：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「repository.findByCreationIdempotencyKey」、「repository.findBySourceSystemAndExternalCaseRef」、「repository.save」、「participantService.ensureImportedParties」；不满足前置条件时抛出 「SecurityException」、「IdempotencyConflictException」；处理的关键状态/协议值包括 「idempotencyKey」、「CASE_」，最终返回「ImportedDisputeView」。
    // 上游调用：「ExternalCaseImportTransactionService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」的上游调用点包括 「DisputeImportService.importOne」、「ExternalCaseImportTransactionService.simulateExternalImport」。
    // 下游影响：「ExternalCaseImportTransactionService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」向下依次触达 「repository.findByCreationIdempotencyKey」、「repository.findBySourceSystemAndExternalCaseRef」、「repository.save」、「participantService.ensureImportedParties」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ExternalCaseImportTransactionService.importDispute(ImportDisputeCommand,AuthenticatedActor,String,String,String)」定义原子提交边界；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public ImportedDisputeView importDispute(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("external dispute import requires service identity");
        }
        requireText(idempotencyKey, "idempotencyKey");
        ActorRole initiatorRole = partyInitiatorRole(command.initiatorRole());
        var requestReplay =
                repository.findByCreationIdempotencyKey(idempotencyKey);
        if (requestReplay.isPresent()) {
            FulfillmentCaseEntity existing =
                    lockExisting(requestReplay.orElseThrow().getId());
            assertImportReplayMatches(existing, command);
            bindIntakeSeed(existing, command, initiatorRole);
            return restoreLockedExisting(existing, actor);
        }
        return repository
                .findBySourceSystemAndExternalCaseRef(
                        command.sourceSystem(), command.externalCaseReference())
                .map(existing -> restoreMatchingImport(existing, command, actor))
                .orElseGet(
                        () -> {
                            FulfillmentCaseEntity entity =
                                    FulfillmentCaseEntity.imported(
                                            caseIdFactory.nextCaseId(),
                                            command.orderReference(),
                                            command.afterSalesReference(),
                                            command.logisticsReference(),
                                            command.userId(),
                                            command.merchantId(),
                                            initiatorRole,
                                            idempotencyKey,
                                            command.disputeType(),
                                            command.title(),
                                            command.description(),
                                            command.riskLevel(),
                                            command.caseStatus(),
                                            command.currentRoom(),
                                            command.currentDeadlineAt(),
                                            command.sourceSystem(),
                                            command.externalCaseReference(),
                                            actor.actorId());
                            entity.bindImportRequestFingerprint(
                                    ImportDisputeRequestFingerprint.of(command));
                            bindIntakeSeed(entity, command, initiatorRole);
                            FulfillmentCaseEntity saved = repository.save(entity);
                            OffsetDateTime now = OffsetDateTime.now(clock);
                            MaterializedRoom materializedRoom =
                                    materializeCurrentRoom(
                                            saved, command, actor.actorId(), now);
                            participantService.ensureImportedParties(
                                    saved, actor, now);
                            RoomEpochAllocation roomEpoch =
                                    recordRoomEpoch(saved, materializedRoom, now);
                            initializeHearingIfNeeded(saved, materializedRoom);
                            startIntakeIfNeeded(
                                    saved,
                                    command,
                                    initiatorRole,
                                    traceId,
                                    requestId,
                                    roomEpoch);
                            return view(saved);
                        });
    }

    private ImportedDisputeView restoreMatchingImport(
            FulfillmentCaseEntity existing,
            ImportDisputeCommand command,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity locked = lockExisting(existing.getId());
        assertImportReplayMatches(locked, command);
        bindIntakeSeed(locked, command, partyInitiatorRole(command.initiatorRole()));
        return restoreLockedExisting(locked, actor);
    }

    private static void assertImportReplayMatches(
            FulfillmentCaseEntity existing,
            ImportDisputeCommand command) {
        if (existing.getSourceType() != CaseSourceType.EXTERNAL_IMPORT
                || !Objects.equals(
                        existing.getImportRequestFingerprint(),
                        ImportDisputeRequestFingerprint.of(command))) {
            throw new IdempotencyConflictException(
                    "import replay is bound to another immutable request");
        }
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.restoreExisting(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「ExternalCaseImportTransactionService.restoreExisting(FulfillmentCaseEntity,AuthenticatedActor)」：恢复Existing；实际协作者为 「participantService.ensureImportedParties」、「actor.actorId」、「materializePersistedCurrentRoom」、「view」，最终返回「ImportedDisputeView」。
    // 上游调用：「ExternalCaseImportTransactionService.restoreExisting(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「ExternalCaseImportTransactionService.simulateExternalImport」、「ExternalCaseImportTransactionService.importDispute」。
    // 下游影响：「ExternalCaseImportTransactionService.restoreExisting(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「participantService.ensureImportedParties」、「actor.actorId」、「materializePersistedCurrentRoom」、「view」；计算结果以「ImportedDisputeView」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.restoreExisting(FulfillmentCaseEntity,AuthenticatedActor)」负责主链路中的“Existing”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private ImportedDisputeView restoreExisting(
            FulfillmentCaseEntity existing,
            AuthenticatedActor actor) {
        return restoreLockedExisting(lockExisting(existing.getId()), actor);
    }

    private ImportedDisputeView restoreLockedExisting(
            FulfillmentCaseEntity locked,
            AuthenticatedActor actor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        MaterializedRoom materializedRoom =
                materializePersistedCurrentRoom(locked, actor.actorId(), now);
        participantService.ensureImportedParties(
                locked,
                actor,
                now);
        recordRoomEpoch(locked, materializedRoom, now);
        initializeHearingIfNeeded(locked, materializedRoom);
        return view(locked);
    }

    private void initializeHearingIfNeeded(
            FulfillmentCaseEntity dispute,
            MaterializedRoom materializedRoom) {
        if (materializedRoom.room().getRoomType() != RoomType.HEARING
                || (dispute.getCaseStatus() != CaseStatus.HEARING_OPEN
                        && dispute.getCaseStatus() != CaseStatus.HEARING)) {
            return;
        }
        hearingInitializer.initialize(dispute);
    }

    private FulfillmentCaseEntity lockExisting(String caseId) {
        return repository
                .findByIdForUpdate(caseId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "imported case disappeared during replay"));
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.startIntakeIfNeeded(FulfillmentCaseEntity,ImportDisputeCommand,ActorRole,String,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.startIntakeIfNeeded(FulfillmentCaseEntity,ImportDisputeCommand,ActorRole,String,String)」：启动接待IfNeeded；实际协作者为 「intakeAgentTurnService.startInitialTurn」、「command.caseStatus」、「command.merchantId」、「command.userId」；处理的关键状态/协议值包括 「intake-initial-turn」、「case_id」、「trace_id」、「request_id」，最终返回「void」。
    // 上游调用：「ExternalCaseImportTransactionService.startIntakeIfNeeded(FulfillmentCaseEntity,ImportDisputeCommand,ActorRole,String,String)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」。
    // 下游影响：「ExternalCaseImportTransactionService.startIntakeIfNeeded(FulfillmentCaseEntity,ImportDisputeCommand,ActorRole,String,String)」向下依次触达 「intakeAgentTurnService.startInitialTurn」、「command.caseStatus」、「command.merchantId」、「command.userId」。
    // 系统意义：「ExternalCaseImportTransactionService.startIntakeIfNeeded(FulfillmentCaseEntity,ImportDisputeCommand,ActorRole,String,String)」负责主链路中的“接待IfNeeded”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private void startIntakeIfNeeded(
            FulfillmentCaseEntity saved,
            ImportDisputeCommand command,
            ActorRole initiatorRole,
            String traceId,
            String requestId,
            RoomEpochAllocation roomEpoch) {
        if (isTerminal(command.caseStatus()) || currentRoom(command) != RoomType.INTAKE) {
            return;
        }
        if (roomEpoch != null
                && roomEpoch.roomType() == ContractTypes.RoomType.INTAKE
                && roomEpoch.writerMode() == ContractTypes.WriterMode.TEMPORAL) {
            return;
        }
        AuthenticatedActor intakeActor =
                new AuthenticatedActor(
                        initiatorRole == ActorRole.MERCHANT
                                ? command.merchantId()
                                : command.userId(),
                        initiatorRole);
        IntakeLobbySeed seed = intakeLobbySeed(command, initiatorRole);
        String caseId = saved.getId();
        postCommit.execute(
                "intake-initial-turn",
                Map.of(
                        "case_id", caseId,
                        "trace_id", traceId,
                        "request_id", requestId),
                () ->
                        intakeAgentTurnService.startInitialTurn(
                                caseId,
                                intakeActor,
                                seed,
                                traceId,
                                requestId));
    }

    private static void bindIntakeSeed(
            FulfillmentCaseEntity dispute,
            ImportDisputeCommand command,
            ActorRole initiatorRole) {
        dispute.bindIntakeSeedMetadata(IntakeCaseSeedMetadata.bind(
                dispute.getMetadataJson(),
                intakeLobbySeed(command, initiatorRole),
                "EXTERNAL_IMPORT"));
    }

    private static IntakeLobbySeed intakeLobbySeed(
            ImportDisputeCommand command, ActorRole initiatorRole) {
        return new IntakeLobbySeed(
                command.orderReference(),
                command.afterSalesReference(),
                command.logisticsReference(),
                initiatorRole.name(),
                command.description(),
                command.requestedOutcomeHint(),
                command.claimResolutionSeed(),
                command.respondentAttitudeSeed());
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,ImportDisputeCommand,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,ImportDisputeCommand,String)」：提供「materializeCurrentRoom」的便捷重载：接收 「dispute」(FulfillmentCaseEntity)、「command」(ImportDisputeCommand)、「actorId」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,ImportDisputeCommand,String)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」、「ExternalCaseImportTransactionService.materializeCurrentRoom」、「ExternalCaseImportTransactionService.materializePersistedCurrentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,ImportDisputeCommand,String)」向下依次触达 「command.caseStatus」、「command.currentRoom」、「command.currentDeadlineAt」、「materializeCurrentRoom」。
    // 系统意义：「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,ImportDisputeCommand,String)」负责主链路中的“materializeCurrent房间”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private MaterializedRoom materializeCurrentRoom(
            FulfillmentCaseEntity dispute,
            ImportDisputeCommand command,
            String actorId,
            OffsetDateTime now) {
        return materializeCurrentRoom(
                dispute,
                command.caseStatus(),
                command.currentRoom(),
                command.currentDeadlineAt(),
                actorId,
                now);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.materializePersistedCurrentRoom(FulfillmentCaseEntity,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.materializePersistedCurrentRoom(FulfillmentCaseEntity,String)」：执行materializePersistedCurrent房间；实际协作者为 「dispute.getCaseStatus」、「dispute.getCurrentRoom」、「dispute.getCurrentDeadlineAt」、「materializeCurrentRoom」，最终返回「void」。
    // 上游调用：「ExternalCaseImportTransactionService.materializePersistedCurrentRoom(FulfillmentCaseEntity,String)」的上游调用点包括 「ExternalCaseImportTransactionService.restoreExisting」。
    // 下游影响：「ExternalCaseImportTransactionService.materializePersistedCurrentRoom(FulfillmentCaseEntity,String)」向下依次触达 「dispute.getCaseStatus」、「dispute.getCurrentRoom」、「dispute.getCurrentDeadlineAt」、「materializeCurrentRoom」。
    // 系统意义：「ExternalCaseImportTransactionService.materializePersistedCurrentRoom(FulfillmentCaseEntity,String)」负责主链路中的“materializePersistedCurrent房间”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private MaterializedRoom materializePersistedCurrentRoom(
            FulfillmentCaseEntity dispute,
            String actorId,
            OffsetDateTime now) {
        return materializeCurrentRoom(
                dispute,
                dispute.getCaseStatus(),
                dispute.getCurrentRoom(),
                dispute.getCurrentDeadlineAt(),
                actorId,
                now);
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,CaseStatus,String,OffsetDateTime,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,CaseStatus,String,OffsetDateTime,String)」：执行materializeCurrent房间：先把新状态写入 PostgreSQL 事实表；实际协作者为 「roomRepository.findByCaseIdAndRoomType」、「roomRepository.save」、「clockRepository.findByCaseIdAndClockType」、「clockRepository.save」，最终返回「void」。
    // 上游调用：「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,CaseStatus,String,OffsetDateTime,String)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」、「ExternalCaseImportTransactionService.materializeCurrentRoom」、「ExternalCaseImportTransactionService.materializePersistedCurrentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,CaseStatus,String,OffsetDateTime,String)」向下依次触达 「roomRepository.findByCaseIdAndRoomType」、「roomRepository.save」、「clockRepository.findByCaseIdAndClockType」、「clockRepository.save」。
    // 系统意义：「ExternalCaseImportTransactionService.materializeCurrentRoom(FulfillmentCaseEntity,CaseStatus,String,OffsetDateTime,String)」负责主链路中的“materializeCurrent房间”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private MaterializedRoom materializeCurrentRoom(
            FulfillmentCaseEntity dispute,
            CaseStatus caseStatus,
            String currentRoom,
            OffsetDateTime currentDeadlineAt,
            String actorId,
            OffsetDateTime now) {
        RoomType roomType = currentRoom(caseStatus, currentRoom);
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(dispute.getId(), roomType)
                        .orElseGet(
                                () ->
                                        roomRepository.save(
                                                isTerminal(caseStatus)
                                                        ? CaseRoomEntity.closed(
                                                                roomId(),
                                                                dispute.getId(),
                                                                roomType,
                                                                now,
                                                                actorId)
                                                        : CaseRoomEntity.open(
                                                                roomId(),
                                                                dispute.getId(),
                                                                roomType,
                                                                now,
                                                                actorId)));
        if (isTerminal(caseStatus)) {
            if (room.getRoomStatus() != com.example.dispute.room.domain.RoomStatus.CLOSED) {
                room.close(now, actorId);
                roomRepository.save(room);
            }
            PhaseClockType terminalClockType = phaseClock(roomType);
            if (terminalClockType != null) {
                clockRepository
                        .findByCaseIdAndClockType(dispute.getId(), terminalClockType)
                        .filter(clock -> clock.getClockStatus() == PhaseClockStatus.RUNNING)
                        .ifPresent(
                                clock -> {
                                    clock.complete(now, "TERMINAL_IMPORT", actorId);
                                    clockRepository.save(clock);
                                });
            }
            dispute.synchronizeImportedCurrentDeadline(null, actorId);
            return new MaterializedRoom(room, null);
        }
        PhaseClockType clockType = phaseClock(roomType);
        if (clockType == null) {
            return new MaterializedRoom(room, currentDeadlineAt);
        }
        var existingClock =
                clockRepository.findByCaseIdAndClockType(dispute.getId(), clockType);
        if (existingClock.isPresent()) {
            OffsetDateTime deadline = existingClock.orElseThrow().getDeadlineAt();
            dispute.synchronizeImportedCurrentDeadline(deadline, actorId);
            return new MaterializedRoom(
                    room, deadline);
        }
        OffsetDateTime deadline =
                currentDeadlineAt == null
                        ? now.plus(
                                clockType == PhaseClockType.EVIDENCE_SUBMISSION
                                        ? properties.evidenceWindow()
                                        : properties.hearingWindow())
                        : currentDeadlineAt;
        if (!deadline.isAfter(now)) {
            deadline =
                    now.plus(
                            clockType == PhaseClockType.EVIDENCE_SUBMISSION
                                    ? properties.evidenceWindow()
                                    : properties.hearingWindow());
        }
        clockRepository.save(
                CasePhaseClockEntity.running(
                        clockId(),
                        dispute.getId(),
                        room.getId(),
                        clockType,
                        now,
                        deadline,
                        workflowId(clockType, dispute.getId()),
                        actorId));
        dispute.synchronizeImportedCurrentDeadline(deadline, actorId);
        return new MaterializedRoom(room, deadline);
    }

    private RoomEpochAllocation recordRoomEpoch(
            FulfillmentCaseEntity dispute,
            MaterializedRoom materializedRoom,
            OffsetDateTime occurredAt) {
        CaseRoomEntity room = materializedRoom.room();
        ContractTypes.RoomType roomType =
                ContractTypes.RoomType.valueOf(room.getRoomType().name());
        if (isTerminal(dispute.getCaseStatus())) {
            roomEpochAllocator.recordTerminal(
                    new TerminalRoomEpoch(
                            dispute.getId(),
                            room.getId(),
                            roomType,
                            dispute.getCaseStatus().name(),
                            room.getRoomStatus().name(),
                            occurredAt));
            return null;
        }
        return roomEpochAllocator.activate(
                new ActivateRoomEpoch(
                        dispute.getId(),
                        room.getId(),
                        roomType,
                        dispute.getCaseStatus().name(),
                        room.getRoomStatus().name(),
                        materializedRoom.projectedDeadlineAt(),
                        occurredAt));
    }

    private record MaterializedRoom(
            CaseRoomEntity room, OffsetDateTime projectedDeadlineAt) {}

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.currentRoom(ImportDisputeCommand)」。
    // 具体功能：「ExternalCaseImportTransactionService.currentRoom(ImportDisputeCommand)」：提供「currentRoom」的便捷重载：接收 「command」(ImportDisputeCommand)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「ExternalCaseImportTransactionService.currentRoom(ImportDisputeCommand)」的上游调用点包括 「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「ExternalCaseImportTransactionService.materializeCurrentRoom」、「ExternalCaseImportTransactionService.currentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.currentRoom(ImportDisputeCommand)」向下依次触达 「command.caseStatus」、「command.currentRoom」、「currentRoom」；计算结果以「RoomType」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.currentRoom(ImportDisputeCommand)」负责主链路中的“current房间”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static RoomType currentRoom(ImportDisputeCommand command) {
        return currentRoom(command.caseStatus(), command.currentRoom());
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.currentRoom(CaseStatus,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.currentRoom(CaseStatus,String)」：构建current房间，最终返回「RoomType」。
    // 上游调用：「ExternalCaseImportTransactionService.currentRoom(CaseStatus,String)」的上游调用点包括 「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「ExternalCaseImportTransactionService.materializeCurrentRoom」、「ExternalCaseImportTransactionService.currentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.currentRoom(CaseStatus,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RoomType」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.currentRoom(CaseStatus,String)」负责主链路中的“current房间”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static RoomType currentRoom(
            CaseStatus caseStatus,
            String currentRoom) {
        if (currentRoom != null && !currentRoom.isBlank()) {
            return RoomType.valueOf(currentRoom);
        }
        return switch (caseStatus) {
            case INTAKE_PENDING, INTAKE_IN_PROGRESS, INTAKE_COMPLETED, NOT_ADMISSIBLE ->
                    RoomType.INTAKE;
            case EVIDENCE_OPEN, EVIDENCE_SEALED, DOSSIER_BUILDING, DOSSIER_BUILT ->
                    RoomType.EVIDENCE;
            case HEARING, HEARING_OPEN, WAITING_EVIDENCE, SETTLEMENT_PENDING,
                    DRAFT_READY, DELIBERATION_RUNNING -> RoomType.HEARING;
            default -> RoomType.REVIEW;
        };
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.phaseClock(RoomType)」。
    // 具体功能：「ExternalCaseImportTransactionService.phaseClock(RoomType)」：构建阶段时钟，最终返回「PhaseClockType」。
    // 上游调用：「ExternalCaseImportTransactionService.phaseClock(RoomType)」的上游调用点包括 「ExternalCaseImportTransactionService.materializeCurrentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.phaseClock(RoomType)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「PhaseClockType」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.phaseClock(RoomType)」负责主链路中的“阶段时钟”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static PhaseClockType phaseClock(RoomType roomType) {
        return switch (roomType) {
            case EVIDENCE -> PhaseClockType.EVIDENCE_SUBMISSION;
            case HEARING -> PhaseClockType.HEARING;
            case INTAKE, REVIEW -> null;
        };
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.isTerminal(CaseStatus)」。
    // 具体功能：「ExternalCaseImportTransactionService.isTerminal(CaseStatus)」：判断是否Terminal，最终返回「boolean」。
    // 上游调用：「ExternalCaseImportTransactionService.isTerminal(CaseStatus)」的上游调用点包括 「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「ExternalCaseImportTransactionService.materializeCurrentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.isTerminal(CaseStatus)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.isTerminal(CaseStatus)」负责主链路中的“Terminal”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static boolean isTerminal(CaseStatus status) {
        return status == CaseStatus.CLOSED
                || status == CaseStatus.CANCELLED
                || status == CaseStatus.NOT_ADMISSIBLE;
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.workflowId(PhaseClockType,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.workflowId(PhaseClockType,String)」：构建工作流标识；处理的关键状态/协议值包括 「evidence-window-」、「hearing-window-」，最终返回「String」。
    // 上游调用：「ExternalCaseImportTransactionService.workflowId(PhaseClockType,String)」的上游调用点包括 「ExternalCaseImportTransactionService.materializeCurrentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.workflowId(PhaseClockType,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.workflowId(PhaseClockType,String)」负责主链路中的“工作流标识”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static String workflowId(PhaseClockType type, String caseId) {
        return type == PhaseClockType.EVIDENCE_SUBMISSION
                ? "evidence-window-" + caseId
                : "hearing-window-" + caseId;
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.roomId()」。
    // 具体功能：「ExternalCaseImportTransactionService.roomId()」：构建房间标识；实际协作者为 「compactUuid」；处理的关键状态/协议值包括 「ROOM_」，最终返回「String」。
    // 上游调用：「ExternalCaseImportTransactionService.roomId()」的上游调用点包括 「ExternalCaseImportTransactionService.materializeCurrentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.roomId()」向下依次触达 「compactUuid」；计算结果以「String」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.roomId()」负责主链路中的“房间标识”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static String roomId() {
        return "ROOM_" + compactUuid();
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.clockId()」。
    // 具体功能：「ExternalCaseImportTransactionService.clockId()」：构建时钟标识；实际协作者为 「compactUuid」；处理的关键状态/协议值包括 「CLOCK_」，最终返回「String」。
    // 上游调用：「ExternalCaseImportTransactionService.clockId()」的上游调用点包括 「ExternalCaseImportTransactionService.materializeCurrentRoom」。
    // 下游影响：「ExternalCaseImportTransactionService.clockId()」向下依次触达 「compactUuid」；计算结果以「String」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.clockId()」负责主链路中的“时钟标识”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static String clockId() {
        return "CLOCK_" + compactUuid();
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.view(FulfillmentCaseEntity)」。
    // 具体功能：「ExternalCaseImportTransactionService.view(FulfillmentCaseEntity)」：构建视图；实际协作者为 「entity.getId」、「entity.getOrderId」、「entity.getAfterSaleId」、「entity.getLogisticsId」，最终返回「ImportedDisputeView」。
    // 上游调用：「ExternalCaseImportTransactionService.view(FulfillmentCaseEntity)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」、「ExternalCaseImportTransactionService.restoreExisting」。
    // 下游影响：「ExternalCaseImportTransactionService.view(FulfillmentCaseEntity)」向下依次触达 「entity.getId」、「entity.getOrderId」、「entity.getAfterSaleId」、「entity.getLogisticsId」；计算结果以「ImportedDisputeView」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.view(FulfillmentCaseEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static ImportedDisputeView view(FulfillmentCaseEntity entity) {
        return new ImportedDisputeView(
                entity.getId(),
                entity.getOrderId(),
                entity.getAfterSaleId(),
                entity.getLogisticsId(),
                entity.getUserId(),
                entity.getMerchantId(),
                entity.getDisputeType(),
                entity.getSourceType().name(),
                entity.getSourceSystem(),
                entity.getExternalCaseRef(),
                entity.getRiskLevel(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getCaseStatus(),
                entity.getCurrentRoom(),
                entity.getCurrentDeadlineAt(),
                pendingAction(entity.getCaseStatus()),
                entity.getInitiatorRole().name());
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.pendingAction(CaseStatus)」。
    // 具体功能：「ExternalCaseImportTransactionService.pendingAction(CaseStatus)」：构建待处理动作；处理的关键状态/协议值包括 「COMPLETE_INTAKE」、「SUBMIT_EVIDENCE」、「ENTER_HEARING」、「PARTICIPATE_HEARING」，最终返回「String」。
    // 上游调用：「ExternalCaseImportTransactionService.pendingAction(CaseStatus)」的上游调用点包括 「ExternalCaseImportTransactionService.view」。
    // 下游影响：「ExternalCaseImportTransactionService.pendingAction(CaseStatus)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.pendingAction(CaseStatus)」负责主链路中的“待处理动作”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
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

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.partyInitiatorRole(String)」。
    // 具体功能：「ExternalCaseImportTransactionService.partyInitiatorRole(String)」：构建当事方发起方角色；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「ActorRole」。
    // 上游调用：「ExternalCaseImportTransactionService.partyInitiatorRole(String)」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」。
    // 下游影响：「ExternalCaseImportTransactionService.partyInitiatorRole(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.partyInitiatorRole(String)」负责主链路中的“当事方发起方角色”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static ActorRole partyInitiatorRole(String role) {
        ActorRole parsed = ActorRole.valueOf(role);
        if (parsed != ActorRole.USER && parsed != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("imported dispute initiator must be USER or MERCHANT");
        }
        return parsed;
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.compactUuid()」。
    // 具体功能：「ExternalCaseImportTransactionService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「ExternalCaseImportTransactionService.compactUuid()」的上游调用点包括 「ExternalCaseImportTransactionService.importDispute」、「ExternalCaseImportTransactionService.roomId」、「ExternalCaseImportTransactionService.clockId」。
    // 下游影响：「ExternalCaseImportTransactionService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.compactUuid()」负责主链路中的“UUID”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.simulatedCommand(SimulateExternalImportCommand,SimulatedExternalDisputeTemplate,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.simulatedCommand(SimulateExternalImportCommand,SimulatedExternalDisputeTemplate,String)」：模拟模拟命令；实际协作者为 「request.initiatorRoleHint」、「template.forInitiator」、「template.templateNo」、「perspective.requestedResolution」；处理的关键状态/协议值包括 「T%02d-」、「SIM-」、「ORDER-」、「AFTER-」，最终返回「ImportDisputeCommand」。
    // 上游调用：「ExternalCaseImportTransactionService.simulatedCommand(SimulateExternalImportCommand,SimulatedExternalDisputeTemplate,String)」的上游调用点包括 「ExternalCaseImportTransactionService.simulateExternalImport」。
    // 下游影响：「ExternalCaseImportTransactionService.simulatedCommand(SimulateExternalImportCommand,SimulatedExternalDisputeTemplate,String)」向下依次触达 「request.initiatorRoleHint」、「template.forInitiator」、「template.templateNo」、「perspective.requestedResolution」；计算结果以「ImportDisputeCommand」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.simulatedCommand(SimulateExternalImportCommand,SimulatedExternalDisputeTemplate,String)」负责主链路中的“模拟命令”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static ImportDisputeCommand simulatedCommand(
            SimulateExternalImportCommand request,
            SimulatedExternalDisputeTemplate template,
            String idempotencyKey) {
        ActorRole initiatorRole = request.initiatorRoleHint();
        SimulatedExternalDisputeTemplate.InitiatorPerspective perspective =
                template.forInitiator(initiatorRole);
        String stableKey = simulationReferenceKey(request, idempotencyKey);
        String templatePrefix = "T%02d-".formatted(template.templateNo());
        IntakeLobbySeed.ClaimResolutionSeed claim =
                new IntakeLobbySeed.ClaimResolutionSeed(
                        initiatorRole.name(),
                        perspective.requestedResolution(),
                        perspective.requestedAmount(),
                        perspective.requestedItems(),
                        perspective.requestReason(),
                        null);
        return new ImportDisputeCommand(
                SimulatedExternalDisputeTemplateCatalog.SOURCE_SYSTEM,
                "SIM-" + templatePrefix + stableKey,
                "ORDER-" + templatePrefix + stableKey,
                "AFTER-" + templatePrefix + stableKey,
                "LOG-" + templatePrefix + stableKey,
                simulationUserId(request),
                simulationMerchantId(request),
                initiatorRole.name(),
                template.disputeType(),
                template.title(),
                template.description(),
                template.riskLevel(),
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                perspective.requestedResolution(),
                claim,
                null);
    }

    private static void assertSimulationReplayMatches(
            FulfillmentCaseEntity existing,
            SimulateExternalImportCommand request,
            String idempotencyKey) {
        String externalReference = existing.getExternalCaseRef();
        String boundSuffix = "-" + simulationReferenceKey(request, idempotencyKey);
        boolean matchingReference = externalReference != null
                && externalReference.endsWith(boundSuffix);
        if (existing.getSourceType() != CaseSourceType.EXTERNAL_IMPORT
                || !Objects.equals(
                        existing.getSourceSystem(),
                        SimulatedExternalDisputeTemplateCatalog.SOURCE_SYSTEM)
                || !matchingReference
                || !Objects.equals(existing.getUserId(), simulationUserId(request))
                || !Objects.equals(
                        existing.getMerchantId(), simulationMerchantId(request))
                || existing.getInitiatorRole() != request.initiatorRoleHint()) {
            throw new IdempotencyConflictException(
                    "simulation idempotency key is bound to another request");
        }
    }

    private static String simulationUserId(SimulateExternalImportCommand request) {
        return request.initiatorRoleHint() == ActorRole.USER
                ? request.currentActorId()
                : request.counterpartyActorId();
    }

    private static String simulationMerchantId(SimulateExternalImportCommand request) {
        return request.initiatorRoleHint() == ActorRole.MERCHANT
                ? request.currentActorId()
                : request.counterpartyActorId();
    }

    private static String simulationReferenceKey(
            SimulateExternalImportCommand request, String idempotencyKey) {
        return stableReferenceKey(
                idempotencyKey,
                Integer.toString(request.count()),
                request.scenario(),
                request.riskLevelHint().name(),
                request.initiatorRoleHint().name(),
                request.currentActorId(),
                request.counterpartyActorId(),
                request.simulationBatchId());
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.stableReferenceKey(String)」。
    // 具体功能：「ExternalCaseImportTransactionService.stableReferenceKey(String)」：构建稳定引用键：先计算稳定哈希以绑定审批快照；实际协作者为 「MessageDigest.getInstance」、「value.append」、「MessageDigest.getInstance("SHA-256").digest」、「"%02x".formatted」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「SHA-256」、「%02x」，最终返回「String」。
    // 上游调用：「ExternalCaseImportTransactionService.stableReferenceKey(String)」的上游调用点包括 「ExternalCaseImportTransactionService.simulatedCommand」。
    // 下游影响：「ExternalCaseImportTransactionService.stableReferenceKey(String)」向下依次触达 「MessageDigest.getInstance」、「value.append」、「MessageDigest.getInstance("SHA-256").digest」、「"%02x".formatted」；计算结果以「String」交给调用方。
    // 系统意义：「ExternalCaseImportTransactionService.stableReferenceKey(String)」负责主链路中的“稳定引用键”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static String stableReferenceKey(String... values) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String valuePart : values) {
                byte[] value = StrictUtf8.encode(valuePart);
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                hash.update(value);
            }
            byte[] digest = hash.digest();
            StringBuilder value = new StringBuilder(24);
            for (int index = 0; index < 12; index++) {
                value.append("%02x".formatted(digest[index]));
            }
            return value.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「ExternalCaseImportTransactionService.requireText(String,String)」。
    // 具体功能：「ExternalCaseImportTransactionService.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「ExternalCaseImportTransactionService.requireText(String,String)」的上游调用点包括 「ExternalCaseImportTransactionService.simulateExternalImport」、「ExternalCaseImportTransactionService.importDispute」。
    // 下游影响：「ExternalCaseImportTransactionService.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ExternalCaseImportTransactionService.requireText(String,String)」在“文本”进入下游前阻断非法状态；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

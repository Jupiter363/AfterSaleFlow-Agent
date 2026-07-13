/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：在 Temporal Activity 边界执行旧版 Temporal Activity 业务副作用适配所需的数据库、Agent 或工具副作用。
 * 业务链路：核心入口/契约为 「initializeHearing」、「analyzeHearing」、「supports」、「finalizeResult」、「recordPartyEvidence」、「recordReviewerSignal」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.application;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.agentstream.application.AgentRunFinalizationContext;
import com.example.dispute.agentstream.application.AgentRunFinalizer;
import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.ParseStatus;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.hearing.application.ActiveCourtroomContextAssembler;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeActivities;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// 所属模块：【Temporal 持久化编排 / 应用编排层】类型「CaseFulfillmentDisputeActivitiesImpl」。
// 类型职责：在 Temporal Activity 边界执行旧版 Temporal Activity 业务副作用适配所需的数据库、Agent 或工具副作用；本类型显式提供 「CaseFulfillmentDisputeActivitiesImpl」、「CaseFulfillmentDisputeActivitiesImpl」、「initializeHearing」、「analyzeHearing」、「analyzeHearingThroughStream」、「awaitAgentRun」。
// 协作关系：主要由 「FinalWorkflowActivitiesAdapter.closeCaseAndEvaluate」、「FinalWorkflowActivitiesAdapter.complete」、「FinalWorkflowActivitiesAdapter.createReviewPacket」、「FinalWorkflowActivitiesAdapter.executeAction」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class CaseFulfillmentDisputeActivitiesImpl
        implements CaseFulfillmentDisputeActivities, AgentRunFinalizer {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("temporal-worker", ActorRole.SYSTEM);

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final PolicyRuleRepository policyRepository;
    private final HearingStateRepository stateRepository;
    private final HearingRecordRepository recordRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final AgentRunRepository agentRunRepository;
    private final PartySubmissionRepository submissionRepository;
    private final ActiveCourtroomContextAssembler courtroomContextAssembler;
    private final HearingAgentClient agentClient;
    private final RemedyApplicationService remedyService;
    private final ReviewApplicationService reviewService;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final ToolExecutorService toolExecutorService;
    private final CaseClosureService closureService;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private AgentRunCoordinator streamRunCoordinator;
    private CaseRoomRepository streamRoomRepository;
    private CaseEventService streamCaseEventService;
    private long streamAwaitTimeoutMs = 150_000L;

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「evidenceRepository」(EvidenceItemRepository)、「policyRepository」(PolicyRuleRepository)、「stateRepository」(HearingStateRepository)、「recordRepository」(HearingRecordRepository)、「draftRepository」(AdjudicationDraftRepository)、「agentRunRepository」(AgentRunRepository)、「submissionRepository」(PartySubmissionRepository)、「courtroomContextAssembler」(ActiveCourtroomContextAssembler)、「agentClient」(HearingAgentClient)、「remedyService」(RemedyApplicationService)、「reviewService」(ReviewApplicationService)、「lifecycleNotifications」(CaseLifecycleNotificationService)、「toolExecutorService」(ToolExecutorService)、「closureService」(CaseClosureService)、「auditRecorder」(AuditRecorder)、「objectMapper」(ObjectMapper)、「transactions」(TransactionTemplate) 并保存为「CaseFulfillmentDisputeActivitiesImpl」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」、「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan」 显式创建。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseFulfillmentDisputeActivitiesImpl(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            PolicyRuleRepository policyRepository,
            HearingStateRepository stateRepository,
            HearingRecordRepository recordRepository,
            AdjudicationDraftRepository draftRepository,
            AgentRunRepository agentRunRepository,
            PartySubmissionRepository submissionRepository,
            ActiveCourtroomContextAssembler courtroomContextAssembler,
            HearingAgentClient agentClient,
            RemedyApplicationService remedyService,
            ReviewApplicationService reviewService,
            CaseLifecycleNotificationService lifecycleNotifications,
            ToolExecutorService toolExecutorService,
            CaseClosureService closureService,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            TransactionTemplate transactions) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.policyRepository = policyRepository;
        this.stateRepository = stateRepository;
        this.recordRepository = recordRepository;
        this.draftRepository = draftRepository;
        this.agentRunRepository = agentRunRepository;
        this.submissionRepository = submissionRepository;
        this.courtroomContextAssembler = courtroomContextAssembler;
        this.agentClient = agentClient;
        this.remedyService = remedyService;
        this.reviewService = reviewService;
        this.lifecycleNotifications = lifecycleNotifications;
        this.toolExecutorService = toolExecutorService;
        this.closureService = closureService;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate,AgentRunCoordinator,CaseRoomRepository,CaseEventService,AppProperties)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate,AgentRunCoordinator,CaseRoomRepository,CaseEventService,AppProperties)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「evidenceRepository」(EvidenceItemRepository)、「policyRepository」(PolicyRuleRepository)、「stateRepository」(HearingStateRepository)、「recordRepository」(HearingRecordRepository)、「draftRepository」(AdjudicationDraftRepository)、「agentRunRepository」(AgentRunRepository)、「submissionRepository」(PartySubmissionRepository)、「courtroomContextAssembler」(ActiveCourtroomContextAssembler)、「agentClient」(HearingAgentClient)、「remedyService」(RemedyApplicationService)、「reviewService」(ReviewApplicationService)、「lifecycleNotifications」(CaseLifecycleNotificationService)、「toolExecutorService」(ToolExecutorService)、「closureService」(CaseClosureService)、「auditRecorder」(AuditRecorder)、「objectMapper」(ObjectMapper)、「transactions」(TransactionTemplate)、「streamRunCoordinator」(AgentRunCoordinator)、「streamRoomRepository」(CaseRoomRepository)、「streamCaseEventService」(CaseEventService)、「properties」(AppProperties) 并保存为「CaseFulfillmentDisputeActivitiesImpl」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate,AgentRunCoordinator,CaseRoomRepository,CaseEventService,AppProperties)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」、「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan」 显式创建。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate,AgentRunCoordinator,CaseRoomRepository,CaseEventService,AppProperties)」向下依次触达 「Math.max」、「properties.agent」、「properties.agent().timeoutMs」。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.CaseFulfillmentDisputeActivitiesImpl(FulfillmentCaseRepository,EvidenceItemRepository,PolicyRuleRepository,HearingStateRepository,HearingRecordRepository,AdjudicationDraftRepository,AgentRunRepository,PartySubmissionRepository,ActiveCourtroomContextAssembler,HearingAgentClient,RemedyApplicationService,ReviewApplicationService,CaseLifecycleNotificationService,ToolExecutorService,CaseClosureService,AuditRecorder,ObjectMapper,TransactionTemplate,AgentRunCoordinator,CaseRoomRepository,CaseEventService,AppProperties)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    @Autowired
    public CaseFulfillmentDisputeActivitiesImpl(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            PolicyRuleRepository policyRepository,
            HearingStateRepository stateRepository,
            HearingRecordRepository recordRepository,
            AdjudicationDraftRepository draftRepository,
            AgentRunRepository agentRunRepository,
            PartySubmissionRepository submissionRepository,
            ActiveCourtroomContextAssembler courtroomContextAssembler,
            HearingAgentClient agentClient,
            RemedyApplicationService remedyService,
            ReviewApplicationService reviewService,
            CaseLifecycleNotificationService lifecycleNotifications,
            ToolExecutorService toolExecutorService,
            CaseClosureService closureService,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            AgentRunCoordinator streamRunCoordinator,
            CaseRoomRepository streamRoomRepository,
            CaseEventService streamCaseEventService,
            AppProperties properties) {
        this(
                caseRepository,
                evidenceRepository,
                policyRepository,
                stateRepository,
                recordRepository,
                draftRepository,
                agentRunRepository,
                submissionRepository,
                courtroomContextAssembler,
                agentClient,
                remedyService,
                reviewService,
                lifecycleNotifications,
                toolExecutorService,
                closureService,
                auditRecorder,
                objectMapper,
                transactions);
        this.streamRunCoordinator = streamRunCoordinator;
        this.streamRoomRepository = streamRoomRepository;
        this.streamCaseEventService = streamCaseEventService;
        this.streamAwaitTimeoutMs =
                Math.max(60_000L, properties.agent().timeoutMs() + 60_000L);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.initializeHearing(CaseWorkflowInput)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.initializeHearing(CaseWorkflowInput)」：初始化庭审：先在显式事务模板中原子写入业务事实，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「stateRepository.findByWorkflowId」、「caseRepository.save」、「stateRepository.save」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「HEARING_」、「HEARING_STARTED」、「HEARING_STATE」、「case_status」，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.initializeHearing(CaseWorkflowInput)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.initialize」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.initializeHearing(CaseWorkflowInput)」向下依次触达 「caseRepository.findByIdForUpdate」、「stateRepository.findByWorkflowId」、「caseRepository.save」、「stateRepository.save」。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.initializeHearing(CaseWorkflowInput)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Override
    public void initializeHearing(CaseWorkflowInput input) {
        transactions.executeWithoutResult(
                ignored -> {
                    FulfillmentCaseEntity disputeCase =
                            caseRepository
                                    .findByIdForUpdate(input.caseId())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "case not found: "
                                                                    + input.caseId()));
                    if (stateRepository.findByWorkflowId(input.workflowId()).isPresent()) {
                        return;
                    }
                    if (disputeCase.getCaseStatus() == CaseStatus.ROUTED) {
                        disputeCase.startHearing(input.workflowId(), SYSTEM.actorId());
                        caseRepository.save(disputeCase);
                    } else if (disputeCase.getCaseStatus() == CaseStatus.HEARING_OPEN) {
                        disputeCase.attachHearingWorkflow(
                                input.workflowId(), SYSTEM.actorId());
                        caseRepository.save(disputeCase);
                    } else if (!input.workflowId().equals(
                            disputeCase.getCurrentWorkflowId())) {
                        throw new IllegalStateException(
                                "case is already controlled by another workflow");
                    }
                    HearingStateEntity state =
                            stateRepository.save(
                                    HearingStateEntity.start(
                                            "HEARING_" + compactUuid(),
                                            input.caseId(),
                                            input.workflowId(),
                                            SYSTEM.actorId()));
                    auditRecorder.record(
                            SYSTEM,
                            "HEARING_STARTED",
                            "HEARING_STATE",
                            state.getId(),
                            input.caseId(),
                            Map.of("case_status", "ROUTED"),
                            Map.of(
                                    "case_status", "HEARING",
                                    "workflow_id", input.workflowId()));
                });
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing(HearingAnalysisActivityCommand)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing(HearingAnalysisActivityCommand)」：分析庭审：先在事务模板中读取并更新同一版本的案件状态，再把新状态写入 PostgreSQL 事实表，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「agentClient.analyze」、「agentRunRepository.saveAndFlush」、「agentRunRepository.save」、「caseRepository.findById」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「TRACE_」、「REQ_」、「_」、「RUN_」，最终返回「HearingAnalysisActivityResult」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing(HearingAnalysisActivityCommand)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.runStage」、「CaseFulfillmentDisputeActivitiesImplTest.finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing(HearingAnalysisActivityCommand)」向下依次触达 「agentClient.analyze」、「agentRunRepository.saveAndFlush」、「agentRunRepository.save」、「caseRepository.findById」；计算结果以「HearingAnalysisActivityResult」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing(HearingAnalysisActivityCommand)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Override
    public HearingAnalysisActivityResult analyzeHearing(
            HearingAnalysisActivityCommand command) {
        if (command.finalConvergence() && command.roundNo() <= 0) {
            throw new IllegalStateException(
                    "final convergence requires sealed hearing rounds and formal jury review report");
        }
        JsonNode request =
                transactions.execute(
                        ignored -> buildAgentRequest(command));
        if (streamRunCoordinator != null) {
            return analyzeHearingThroughStream(command, request);
        }
        long started = System.nanoTime();
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        String traceId = "TRACE_" + command.workflowId();
        HearingAgentResult result =
                agentClient.analyze(
                        request,
                        traceId,
                        "REQ_" + command.workflowId() + "_" + command.roundNo());
        boolean requiresAdditionalEvidence =
                result.requiresAdditionalEvidence()
                        && command.allowSupplementalRequest();
        final HearingAgentResult analysisResult = result;
        final boolean effectiveRequiresAdditionalEvidence =
                requiresAdditionalEvidence;
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String agentRunId = "RUN_" + compactUuid();
        String draftId =
                transactions.execute(
                        ignored -> {
                            AgentRunEntity run =
                                    agentRunRepository.saveAndFlush(
                                            AgentRunEntity.completed(
                                                    agentRunId,
                                                    command.caseId(),
                                                    command.workflowId(),
                                                    "presiding-judge",
                                                    "PRESIDING_JUDGE",
                                                    "presiding-judge-v1",
                                                    analysisResult.promptVersion(),
                                                    "dispute-default-v1",
                                                    "ruleset-current",
                                                    analysisResult.model(),
                                                    inputRefs(request),
                                                    null,
                                                    "{\"schema_valid\":true}",
                                                    jsonOrDefault(
                                                            analysisResult.raw()
                                                                    .path(
                                                                            "manual_review_reasons"),
                                                            "[]"),
                                                    null,
                                                    latencyMs,
                                                    null,
                                                    startedAt,
                                                    traceId,
                                                    SYSTEM.actorId()));
                            String persistedDraftId =
                                    persistAnalysis(
                                            command,
                                            request,
                                            analysisResult,
                                            effectiveRequiresAdditionalEvidence,
                                            latencyMs,
                                            agentRunId);
                            run.attachOutput(persistedDraftId);
                            agentRunRepository.save(run);
                            auditRecorder.record(
                                    SYSTEM,
                                    "AGENT_RUN_COMPLETED",
                                    "AGENT_RUN",
                                    agentRunId,
                                    command.caseId(),
                                    Map.of("run_status", "RUNNING"),
                                    Map.of(
                                            "run_status",
                                            "COMPLETED",
                                            "trace_id",
                                            traceId,
                                            "output_ref",
                                            persistedDraftId));
                            return persistedDraftId;
                        });
        if (requiresAdditionalEvidence) {
            FulfillmentCaseEntity dispute =
                    caseRepository
                            .findById(command.caseId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "case not found: "
                                                            + command.caseId()));
            lifecycleNotifications.supplementRequested(
                    dispute, "hearing-round-" + command.roundNo());
        }
        return new HearingAnalysisActivityResult(
                requiresAdditionalEvidence,
                result.manualRequired(),
                draftId,
                requiresAdditionalEvidence
                        ? "WAITING_EVIDENCE"
                        : "RUNNING");
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream(HearingAnalysisActivityCommand,JsonNode)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream(HearingAnalysisActivityCommand,JsonNode)」：分析庭审Through流：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「streamRoomRepository.findByCaseIdAndRoomType」、「streamRunCoordinator.start」、「streamCaseEventService.recordLifecycleEvent」、「draftRepository.findByCaseIdAndDraftVersion」；处理的关键状态/协议值包括 「TRACE_」、「hearing-analysis:」、「:」、「final」，最终返回「HearingAnalysisActivityResult」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream(HearingAnalysisActivityCommand,JsonNode)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream(HearingAnalysisActivityCommand,JsonNode)」向下依次触达 「streamRoomRepository.findByCaseIdAndRoomType」、「streamRunCoordinator.start」、「streamCaseEventService.recordLifecycleEvent」、「draftRepository.findByCaseIdAndDraftVersion」；计算结果以「HearingAnalysisActivityResult」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream(HearingAnalysisActivityCommand,JsonNode)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private HearingAnalysisActivityResult analyzeHearingThroughStream(
            HearingAnalysisActivityCommand command, JsonNode request) {
        String traceId = "TRACE_" + command.workflowId();
        String roomId =
                streamRoomRepository
                        .findByCaseIdAndRoomType(command.caseId(), RoomType.HEARING)
                        .orElseThrow(() -> new IllegalStateException("hearing room not found"))
                        .getId();
        String idempotencyKey =
                "hearing-analysis:"
                        + command.caseId()
                        + ":"
                        + command.roundNo()
                        + ":"
                        + (command.finalConvergence() ? "final" : "interim");
        AgentRunStartCommand runCommand =
                new AgentRunStartCommand(
                                command.caseId(),
                                roomId,
                                "HEARING_ANALYSIS",
                                request,
                                List.of(
                                        ActorRole.USER.name(),
                                        ActorRole.MERCHANT.name(),
                                        ActorRole.CUSTOMER_SERVICE.name(),
                                        ActorRole.PLATFORM_REVIEWER.name(),
                                        ActorRole.ADMIN.name(),
                                        ActorRole.SYSTEM.name()),
                                List.of(),
                                idempotencyKey,
                                traceId,
                                "REQ_HEARING_ANALYSIS_"
                                        + command.caseId()
                                        + "_"
                                        + command.roundNo(),
                                SYSTEM.actorId());
        AgentRunAcceptedView accepted = streamRunCoordinator.start(runCommand);
        if ("FAILED".equals(accepted.status())) {
            accepted = streamRunCoordinator.retryInfrastructureFailure(runCommand);
        }
        streamCaseEventService.recordLifecycleEvent(
                command.caseId(),
                roomId,
                "AGENT_RUN_STARTED",
                Map.of(
                        "agent_run_id", accepted.runId(),
                        "stream_url", accepted.streamUrl(),
                        "operation", "HEARING_ANALYSIS"),
                "agent-run-started:" + accepted.runId(),
                SYSTEM.actorId());
        AgentRunEntity completed;
        try {
            completed = awaitAgentRun(accepted.runId());
        } catch (AgentExecutionException failure) {
            if (command.finalConvergence()
                    && failure.errorCode() == ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID) {
                return persistFinalSchemaFailureFallback(command, request, accepted.runId());
            }
            throw failure;
        }
        HearingAgentResult result = parseHearingAgentResult(readJson(completed.getStreamResultJson()));
        boolean requiresAdditionalEvidence =
                result.requiresAdditionalEvidence() && command.allowSupplementalRequest();
        String draftId = completed.getOutputRef();
        if (draftId == null || draftId.isBlank()) {
            draftId =
                    draftRepository
                            .findByCaseIdAndDraftVersion(
                                    command.caseId(), command.roundNo() + 1)
                            .map(AdjudicationDraftEntity::getId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "validated hearing stream did not persist a draft"));
        }
        if (requiresAdditionalEvidence) {
            FulfillmentCaseEntity dispute =
                    caseRepository
                            .findById(command.caseId())
                            .orElseThrow(() -> new IllegalStateException("case not found"));
            lifecycleNotifications.supplementRequested(
                    dispute, "hearing-round-" + command.roundNo());
        }
        return new HearingAnalysisActivityResult(
                requiresAdditionalEvidence,
                result.manualRequired(),
                draftId,
                requiresAdditionalEvidence ? "WAITING_EVIDENCE" : "RUNNING");
    }

    private HearingAnalysisActivityResult persistFinalSchemaFailureFallback(
            HearingAnalysisActivityCommand command, JsonNode request, String failedRunId) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("case_id", command.caseId());
        raw.put("workflow_id", command.workflowId());
        raw.put("workflow_status", "MANUAL_REVIEW_REQUIRED");
        raw.putArray("executed_nodes");
        ObjectNode draft = raw.putObject("adjudication_draft").putObject("draft");
        draft.put("draft_status", "PENDING_HUMAN_REVIEW");
        draft.put("recommended_outcome", "UNDETERMINED");
        draft.put(
                "reasoning_summary",
                "Structured agent output could not be validated. No automated finding was accepted.");
        draft.putArray("issue_findings");
        draft.put("confidence", 0);
        draft.put("risk_level", "HIGH");
        draft.putArray("review_focus")
                .add("Review the failed final-convergence structured output manually.");
        draft.put("requires_human_review", true);
        draft.put("is_final_decision", false);
        raw.putArray("manual_review_reasons").add("AGENT_OUTPUT_SCHEMA_INVALID");
        raw.put("prompt_version", "deterministic-fallback-v1");
        raw.put("model", "manual-review-fallback");

        HearingAgentResult result = parseHearingAgentResult(raw);
        String draftId = persistAnalysis(command, request, result, false, 0L, failedRunId);
        return new HearingAnalysisActivityResult(false, true, draftId, "RUNNING");
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.awaitAgentRun(String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.awaitAgentRun(String)」：等待Agent运行：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「agentRunRepository.findById」、「System.nanoTime」、「Thread.sleep」、「Thread.currentThread」；不满足前置条件时抛出 「AgentExecutionException」；处理的关键状态/协议值包括 「COMPLETED」、「FAILED」、「AGENT_OUTPUT_SCHEMA_INVALID」、「agent_run_id」，最终返回「AgentRunEntity」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.awaitAgentRun(String)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.awaitAgentRun(String)」向下依次触达 「agentRunRepository.findById」、「System.nanoTime」、「Thread.sleep」、「Thread.currentThread」；计算结果以「AgentRunEntity」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.awaitAgentRun(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private AgentRunEntity awaitAgentRun(String runId) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(streamAwaitTimeoutMs);
        while (System.nanoTime() < deadline) {
            AgentRunEntity run =
                    agentRunRepository
                            .findById(runId)
                            .orElseThrow(() -> new IllegalStateException("agent run not found"));
            if ("COMPLETED".equals(run.getRunStatus())) {
                return run;
            }
            if ("FAILED".equals(run.getRunStatus())) {
                ErrorCode errorCode =
                        "AGENT_OUTPUT_SCHEMA_INVALID".equals(run.getErrorCode())
                                ? ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID
                                : ErrorCode.AGENT_SERVICE_UNAVAILABLE;
                throw new AgentExecutionException(
                        errorCode,
                        "hearing agent stream failed",
                        Map.of(
                                "agent_run_id", runId,
                                "agent_error_code",
                                        run.getErrorCode() == null
                                                ? "AGENT_STREAM_FAILED"
                                                : run.getErrorCode(),
                                "retryable", Boolean.TRUE.equals(run.getErrorRetryable())));
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AgentExecutionException(
                        ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                        "hearing agent stream wait was interrupted",
                        Map.of("agent_run_id", runId));
            }
        }
        throw new AgentExecutionException(
                ErrorCode.EXTERNAL_SERVICE_TIMEOUT,
                "hearing agent stream timed out",
                Map.of("agent_run_id", runId));
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.supports(String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.supports(String)」：判断是否支持；处理的关键状态/协议值包括 「HEARING_ANALYSIS」，最终返回「boolean」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.supports(String)」由使用「CaseFulfillmentDisputeActivitiesImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.supports(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.supports(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public boolean supports(String operation) {
        return "HEARING_ANALYSIS".equals(operation);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.finalizeResult(AgentRunFinalizationContext,JsonNode)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.finalizeResult(AgentRunFinalizationContext,JsonNode)」：执行finalize结果：先把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「agentRunRepository.findByIdForUpdate」、「agentRunRepository.save」、「finalization.request」、「finalization.caseId」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「hearing_context」、「completed_statement_rounds」、「max_statement_rounds」、「final_convergence」，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.finalizeResult(AgentRunFinalizationContext,JsonNode)」由使用「CaseFulfillmentDisputeActivitiesImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.finalizeResult(AgentRunFinalizationContext,JsonNode)」向下依次触达 「agentRunRepository.findByIdForUpdate」、「agentRunRepository.save」、「finalization.request」、「finalization.caseId」。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.finalizeResult(AgentRunFinalizationContext,JsonNode)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Override
    public void finalizeResult(AgentRunFinalizationContext finalization, JsonNode rawResult) {
        JsonNode request = finalization.request();
        JsonNode hearingContext = request.path("hearing_context");
        int roundNo = hearingContext.path("completed_statement_rounds").asInt(-1);
        int maxStatementRounds = hearingContext.path("max_statement_rounds").asInt(0);
        boolean finalConvergence = hearingContext.path("final_convergence").asBoolean(false);
        if (!finalization.caseId().equals(request.path("case_id").asText())
                || roundNo < 0
                || (finalConvergence && roundNo < 1)) {
            throw new IllegalStateException("hearing stream request no longer matches the case");
        }
        HearingAnalysisActivityCommand command =
                new HearingAnalysisActivityCommand(
                        finalization.caseId(),
                        request.path("workflow_id").asText(),
                        roundNo,
                        request.path("evidence_timeout").asBoolean(false),
                        roundNo > 0,
                        finalConvergence,
                        maxStatementRounds);
        HearingAgentResult result = parseHearingAgentResult(rawResult);
        boolean requiresAdditionalEvidence =
                result.requiresAdditionalEvidence() && command.allowSupplementalRequest();
        String draftId =
                persistAnalysis(
                        command,
                        request,
                        result,
                        requiresAdditionalEvidence,
                        0L,
                        finalization.runId());
        AgentRunEntity run =
                agentRunRepository
                        .findByIdForUpdate(finalization.runId())
                        .orElseThrow(() -> new IllegalStateException("agent run not found"));
        run.attachOutput(draftId);
        agentRunRepository.save(run);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.parseHearingAgentResult(JsonNode)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.parseHearingAgentResult(JsonNode)」：解析庭审Agent结果；实际协作者为 「response.isObject」、「node.asText」、「response.path("workflow_status").asText」、「response.path("executed_nodes").isArray」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「workflow_status」、「executed_nodes」、「adjudication_draft」、「draft」，最终返回「HearingAgentResult」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.parseHearingAgentResult(JsonNode)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「CaseFulfillmentDisputeActivitiesImpl.finalizeResult」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.parseHearingAgentResult(JsonNode)」向下依次触达 「response.isObject」、「node.asText」、「response.path("workflow_status").asText」、「response.path("executed_nodes").isArray」；计算结果以「HearingAgentResult」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.parseHearingAgentResult(JsonNode)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private HearingAgentResult parseHearingAgentResult(JsonNode response) {
        if (response == null
                || !response.isObject()
                || response.path("workflow_status").asText().isBlank()
                || !response.path("executed_nodes").isArray()
                || !response.path("adjudication_draft").path("draft").isObject()
                || response.path("prompt_version").asText().isBlank()
                || response.path("model").asText().isBlank()) {
            throw new IllegalStateException("hearing stream returned an invalid schema");
        }
        JsonNode draft = response.path("adjudication_draft").path("draft");
        if (!draft.path("requires_human_review").asBoolean(false)
                || draft.path("is_final_decision").asBoolean(true)) {
            throw new IllegalStateException("hearing stream attempted to create a final decision");
        }
        List<String> nodes = new ArrayList<>();
        response.path("executed_nodes").forEach(node -> nodes.add(node.asText()));
        boolean requiresEvidence =
                response.path("evidence_gap")
                        .path("requires_supplemental_evidence")
                        .asBoolean(false);
        boolean manual =
                "MANUAL_REVIEW_REQUIRED".equals(response.path("workflow_status").asText())
                        || !response.path("manual_review_reasons").isEmpty();
        return new HearingAgentResult(
                response,
                requiresEvidence,
                manual,
                List.copyOf(nodes),
                response.path("prompt_version").asText(),
                response.path("model").asText());
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.readJson(String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.readJson(String)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.readJson(String)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.readJson(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.readJson(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("invalid persisted agent stream result", failure);
        }
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest(HearingAnalysisActivityCommand)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest(HearingAnalysisActivityCommand)」：组装Agent请求：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「policyRepository.findActive」、「policy.getRuleCode」；处理的关键状态/协议值包括 「case_id」、「workflow_id」、「user_id」、「evidence_timeout」，最终返回「JsonNode」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest(HearingAnalysisActivityCommand)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest(HearingAnalysisActivityCommand)」向下依次触达 「caseRepository.findById」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「policyRepository.findActive」、「policy.getRuleCode」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest(HearingAnalysisActivityCommand)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private JsonNode buildAgentRequest(HearingAnalysisActivityCommand command) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findById(command.caseId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "case not found: " + command.caseId()));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("case_id", command.caseId());
        request.put("workflow_id", command.workflowId());
        request.put("user_id", disputeCase.getUserId());
        request.put("evidence_timeout", command.evidenceTimedOut());
        ObjectNode hearingContext = request.putObject("hearing_context");
        hearingContext.put("completed_statement_rounds", command.roundNo());
        hearingContext.put("max_statement_rounds", command.maxStatementRounds());
        hearingContext.put("final_convergence", command.finalConvergence());
        hearingContext.put(
                "must_produce_final_plan", command.mustProduceFinalPlan());
        hearingContext.put(
                "allow_supplemental_request",
                command.allowSupplementalRequest());
        if (command.finalConvergence()) {
            hearingContext.set(
                    "courtroom_context",
                    courtroomContextAssembler.assembleFinalConvergence(
                            command.caseId(), command.roundNo()));
            hearingContext.set(
                    "sealed_rounds",
                    courtroomContextAssembler.sealedRounds(
                            command.caseId(), command.roundNo()));
        }
        ArrayNode claims = request.putArray("claims");
        claims.addObject()
                .put("claim_id", "CLAIM_" + command.caseId())
                .put("party_type", "USER")
                .put("statement", disputeCase.getDescription());
        ArrayNode evidence = request.putArray("evidence");
        evidenceRepository
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        command.caseId())
                .stream()
                .filter(item -> item.getParseStatus() == ParseStatus.SUCCEEDED)
                .filter(item -> item.getParsedText() != null && !item.getParsedText().isBlank())
                .limit(100)
                .forEach(
                        item ->
                                evidence.addObject()
                                        .put("evidence_id", item.getId())
                                        .put("evidence_type", item.getEvidenceType())
                                        .put("source_type", item.getSourceType())
                                        .put("content", item.getParsedText()));
        ArrayNode policies = request.putArray("policy_candidates");
        policyRepository
                .findActive(
                        disputeCase.getCaseType(),
                        OffsetDateTime.now(ZoneOffset.UTC))
                .stream()
                .limit(30)
                .forEach(
                        policy ->
                                policies.addObject()
                                        .put("rule_code", policy.getRuleCode())
                                        .put("rule_version", policy.getRuleVersion())
                                        .put(
                                                "rule_text",
                                                policy.getRuleName()
                                                        + "\n"
                                                        + policy.getConditionJson()
                                                        + "\n"
                                                        + policy.getOutcomeJson()));
        return request;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis(HearingAnalysisActivityCommand,JsonNode,HearingAgentResult,boolean,long,String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis(HearingAnalysisActivityCommand,JsonNode,HearingAgentResult,boolean,long,String)」：持久化Analysis：先把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「stateRepository.findByWorkflowId」、「stateRepository.save」、「recordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType」、「recordRepository.save」；处理的关键状态/协议值包括 「adjudication_draft」、「draft」、「confidence」、「evidence_gap」，最终返回「String」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis(HearingAnalysisActivityCommand,JsonNode,HearingAgentResult,boolean,long,String)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」、「CaseFulfillmentDisputeActivitiesImpl.finalizeResult」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis(HearingAnalysisActivityCommand,JsonNode,HearingAgentResult,boolean,long,String)」向下依次触达 「stateRepository.findByWorkflowId」、「stateRepository.save」、「recordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType」、「recordRepository.save」；计算结果以「String」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis(HearingAnalysisActivityCommand,JsonNode,HearingAgentResult,boolean,long,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private String persistAnalysis(
            HearingAnalysisActivityCommand command,
            JsonNode request,
            HearingAgentResult result,
            boolean requiresAdditionalEvidence,
            long latencyMs,
            String agentRunId) {
        HearingStateEntity state =
                stateRepository
                        .findByWorkflowId(command.workflowId())
                        .orElseThrow(() -> new IllegalStateException("hearing state not found"));
        JsonNode raw = result.raw();
        JsonNode draft = raw.path("adjudication_draft").path("draft");
        BigDecimal confidence =
                BigDecimal.valueOf(draft.path("confidence").asDouble(0));
        state.applyAnalysis(
                command.roundNo(),
                lastNode(result.executedNodes()),
                confidence,
                requiresAdditionalEvidence,
                result.manualRequired(),
                raw.toString(),
                jsonOrDefault(raw.path("evidence_gap").path("gaps"), "[]"),
                jsonOrDefault(raw.path("manual_review_reasons"), "[]"),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(72),
                SYSTEM.actorId());
        stateRepository.save(state);
        for (String node : result.executedNodes()) {
            if (!recordRepository
                    .existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
                            command.workflowId(), node, command.roundNo(), "AGENT_NODE")) {
                recordRepository.save(
                        HearingRecordEntity.record(
                                "HREC_" + compactUuid(),
                                command.caseId(),
                                state.getId(),
                                command.workflowId(),
                                node,
                                command.roundNo(),
                                "AGENT_NODE",
                                request.toString(),
                                nodeOutput(raw, node).toString(),
                                objectMapper
                                        .valueToTree(
                                                Map.of(
                                                        "evidence_received",
                                                        command.evidenceReceived(),
                                                        "evidence_timed_out",
                                                        command.evidenceTimedOut()))
                                        .toString(),
                                result.promptVersion(),
                                result.model(),
                                latencyMs,
                                null,
                                SYSTEM.actorId()));
            }
        }
        int version = command.roundNo() + 1;
        return draftRepository
                .findByCaseIdAndDraftVersion(command.caseId(), version)
                .map(AdjudicationDraftEntity::getId)
                .orElseGet(
                        () ->
                                draftRepository
                                        .save(
                                                AdjudicationDraftEntity.create(
                                                        "DRAFT_" + compactUuid(),
                                                        command.caseId(),
                                                        state.getId(),
                                                        version,
                                                        jsonOrDefault(
                                                                draft.path("issue_findings"),
                                                                "[]"),
                                                        jsonOrDefault(
                                                                raw.path(
                                                                                "evidence_cross_check")
                                                                        .path("findings"),
                                                                "[]"),
                                                        jsonOrDefault(
                                                                raw.path("rule_application")
                                                                        .path("applications"),
                                                                "[]"),
                                                        jsonOrDefault(
                                                                draft.path("review_focus"),
                                                                "[]"),
                                                        truncate(
                                                                draft.path(
                                                                                "recommended_outcome")
                                                                        .asText(),
                                                                128),
                                                        confidence,
                                                        draft.path("reasoning_summary")
                                                                .asText(),
                                                        "python-agent-service/"
                                                                + result.model(),
                                                        agentRunId,
                                                        draft.path("draft_status").asText(
                                                                "PENDING_HUMAN_REVIEW"),
                                                        SYSTEM.actorId()))
                                        .getId());
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.recordPartyEvidence(PartyEvidenceSignal)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.recordPartyEvidence(PartyEvidenceSignal)」：记录当事方证据：先在显式事务模板中原子写入业务事实，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「submissionRepository.findById」、「stateRepository.findByCaseId」、「SYSTEM.actorId」、「transactions.executeWithoutResult」，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.recordPartyEvidence(PartyEvidenceSignal)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.recordEvidence」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.recordPartyEvidence(PartyEvidenceSignal)」向下依次触达 「submissionRepository.findById」、「stateRepository.findByCaseId」、「SYSTEM.actorId」、「transactions.executeWithoutResult」。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.recordPartyEvidence(PartyEvidenceSignal)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Override
    public void recordPartyEvidence(PartyEvidenceSignal signal) {
        transactions.executeWithoutResult(
                ignored -> {
                    var submission =
                            submissionRepository
                                    .findById(signal.submissionId())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "party submission not found"));
                    stateRepository
                            .findByCaseId(submission.getCaseId())
                            .ifPresent(state -> state.markRunning(SYSTEM.actorId()));
                });
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.recordReviewerSignal(ReviewerWorkflowSignal)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.recordReviewerSignal(ReviewerWorkflowSignal)」：记录审核员信号，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.recordReviewerSignal(ReviewerWorkflowSignal)」由使用「CaseFulfillmentDisputeActivitiesImpl」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.recordReviewerSignal(ReviewerWorkflowSignal)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.recordReviewerSignal(ReviewerWorkflowSignal)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void recordReviewerSignal(ReviewerWorkflowSignal signal) {
        // The reviewer-facing API persists and audits the decision before signalling.
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.completeHearing(String,String,boolean,boolean)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.completeHearing(String,String,boolean,boolean)」：完成庭审：先在显式事务模板中原子写入业务事实，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「stateRepository.findByWorkflowId」、「stateRepository.save」、「SYSTEM.actorId」、「transactions.executeWithoutResult」；处理的关键状态/协议值包括 「HEARING_COMPLETED」、「HEARING_STATE」、「workflow_id」、「next_stage」，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.completeHearing(String,String,boolean,boolean)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.complete」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.completeHearing(String,String,boolean,boolean)」向下依次触达 「stateRepository.findByWorkflowId」、「stateRepository.save」、「SYSTEM.actorId」、「transactions.executeWithoutResult」。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.completeHearing(String,String,boolean,boolean)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Override
    public void completeHearing(
            String caseId,
            String workflowId,
            boolean manualRequired,
            boolean evidenceTimedOut) {
        transactions.executeWithoutResult(
                ignored -> {
                    HearingStateEntity state =
                            stateRepository
                                    .findByWorkflowId(workflowId)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "hearing state not found"));
                    if (state.getHearingStatus()
                            != com.example.dispute.domain.model.HearingStatus.COMPLETED) {
                        state.complete(manualRequired, SYSTEM.actorId());
                        stateRepository.save(state);
                        auditRecorder.record(
                                SYSTEM,
                                "HEARING_COMPLETED",
                                "HEARING_STATE",
                                state.getId(),
                                caseId,
                                Map.of("workflow_id", workflowId),
                                Map.of(
                                        "next_stage", "REMEDY_PLANNER",
                                        "manual_required", manualRequired,
                                        "evidence_timed_out", evidenceTimedOut));
                    }
                });
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.planRemedy(String,String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.planRemedy(String,String)」：规划补救；实际协作者为 「remedyService.generateForWorkflow」，最终返回「String」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.planRemedy(String,String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.planRemedy」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.planRemedy(String,String)」向下依次触达 「remedyService.generateForWorkflow」；计算结果以「String」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.planRemedy(String,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public String planRemedy(String caseId, String workflowId) {
        return remedyService.generateForWorkflow(caseId, workflowId);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.createReviewTask(String,String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.createReviewTask(String,String)」：创建审核任务；实际协作者为 「reviewService.createForWorkflow」，最终返回「String」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.createReviewTask(String,String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.createReviewPacket」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.createReviewTask(String,String)」向下依次触达 「reviewService.createForWorkflow」；计算结果以「String」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.createReviewTask(String,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public String createReviewTask(String caseId, String remedyPlanId) {
        return reviewService.createForWorkflow(caseId, remedyPlanId);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.executeApprovedPlan(String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.executeApprovedPlan(String)」：执行已审批方案；实际协作者为 「toolExecutorService.executeApprovedActions」；处理的关键状态/协议值包括 「WORKFLOW_EXECUTE:」，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.executeApprovedPlan(String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.executeAction」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.executeApprovedPlan(String)」向下依次触达 「toolExecutorService.executeApprovedActions」。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.executeApprovedPlan(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void executeApprovedPlan(String caseId) {
        toolExecutorService.executeApprovedActions(
                caseId, "WORKFLOW_EXECUTE:" + caseId, SYSTEM);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.closeCaseAndEvaluate(String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.closeCaseAndEvaluate(String)」：关闭案件并且Evaluate；实际协作者为 「closureService.close」；处理的关键状态/协议值包括 「WORKFLOW_CLOSE:」、「TRACE_EVALUATION_」、「REQ_EVALUATION_」，最终返回「void」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.closeCaseAndEvaluate(String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.closeCaseAndEvaluate」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.closeCaseAndEvaluate(String)」向下依次触达 「closureService.close」。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.closeCaseAndEvaluate(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void closeCaseAndEvaluate(String caseId) {
        closureService.close(
                caseId,
                "WORKFLOW_CLOSE:" + caseId,
                SYSTEM,
                "TRACE_EVALUATION_" + caseId,
                "REQ_EVALUATION_" + caseId);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.nodeOutput(JsonNode,String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.nodeOutput(JsonNode,String)」：构建节点输出；处理的关键状态/协议值包括 「issue_framing_node」、「issue_framing」、「evidence_gap_request_node」、「evidence_gap」，最终返回「JsonNode」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.nodeOutput(JsonNode,String)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.nodeOutput(JsonNode,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「JsonNode」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.nodeOutput(JsonNode,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private static JsonNode nodeOutput(JsonNode raw, String node) {
        return switch (node) {
            case "issue_framing_node" -> raw.path("issue_framing");
            case "evidence_gap_request_node" -> raw.path("evidence_gap");
            case "party_liaison_node" -> raw.path("party_liaison");
            case "evidence_cross_check_node" -> raw.path("evidence_cross_check");
            case "rule_application_node" -> raw.path("rule_application");
            case "adjudication_draft_node" -> raw.path("adjudication_draft");
            default -> raw;
        };
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.lastNode(List)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.lastNode(List)」：构建最后节点；处理的关键状态/协议值包括 「C0_HEARING_CONTROLLER」，最终返回「String」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.lastNode(List)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.lastNode(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.lastNode(List)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private static String lastNode(List<String> nodes) {
        return nodes.isEmpty() ? "C0_HEARING_CONTROLLER" : nodes.get(nodes.size() - 1);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.truncate(String,int)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.truncate(String,int)」：截断字符串，最终返回「String」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.truncate(String,int)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.truncate(String,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.truncate(String,int)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.jsonOrDefault(JsonNode,String)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.jsonOrDefault(JsonNode,String)」：构建JSON或者默认；实际协作者为 「value.isMissingNode」、「value.isNull」，最终返回「String」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.jsonOrDefault(JsonNode,String)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.jsonOrDefault(JsonNode,String)」向下依次触达 「value.isMissingNode」、「value.isNull」；计算结果以「String」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.jsonOrDefault(JsonNode,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private static String jsonOrDefault(JsonNode value, String fallback) {
        return value == null || value.isMissingNode() || value.isNull()
                ? fallback
                : value.toString();
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.inputRefs(JsonNode)」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.inputRefs(JsonNode)」：构建输入引用列表；实际协作者为 「objectMapper.createArrayNode」、「node.path("evidence_id").asText」、「node.path("rule_code").asText」；处理的关键状态/协议值包括 「evidence」、「evidence_id」、「policy_candidates」、「rule_code」，最终返回「String」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.inputRefs(JsonNode)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.inputRefs(JsonNode)」向下依次触达 「objectMapper.createArrayNode」、「node.path("evidence_id").asText」、「node.path("rule_code").asText」；计算结果以「String」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.inputRefs(JsonNode)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private String inputRefs(JsonNode request) {
        ArrayNode refs = objectMapper.createArrayNode();
        request.path("evidence")
                .forEach(node -> refs.add(node.path("evidence_id").asText()));
        request.path("policy_candidates")
                .forEach(node -> refs.add(node.path("rule_code").asText()));
        return refs.toString();
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「CaseFulfillmentDisputeActivitiesImpl.compactUuid()」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImpl.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImpl.compactUuid()」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.initializeHearing」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImpl.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImpl.compactUuid()」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

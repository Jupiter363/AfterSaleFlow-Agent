/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：编排案件 Temporal Workflow 的启动、Signal 和查询规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「start」、「getHearing」、「getLatestDraft」、「submitPartyEvidence」、「submitReviewerSignal」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.PartySubmissionEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.DeliberationInterventionMode;
import com.example.dispute.workflow.domain.FulfillmentDisputeCommand;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import com.example.dispute.workflow.temporal.FulfillmentDisputeWorkflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 所属模块：【Temporal 持久化编排 / 应用编排层】类型「WorkflowApplicationService」。
// 类型职责：编排案件 Temporal Workflow 的启动、Signal 和查询规则、权限校验与事实读写；本类型显式提供 「WorkflowApplicationService」、「start」、「getHearing」、「getLatestDraft」、「submitPartyEvidence」、「submitReviewerSignal」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class WorkflowApplicationService {

    private final WorkflowClient workflowClient;
    private final AppProperties properties;
    private final FulfillmentCaseRepository caseRepository;
    private final HearingStateRepository stateRepository;
    private final PartySubmissionRepository submissionRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Duration evidenceWaitTimeout;
    private final int maxEvidenceRounds;
    private final DeliberationInterventionMode deliberationMode;
    private final String deliberationMinimumRiskLevel;
    private final int deliberationScoreThreshold;
    private final int deliberationMaxRegenerations;

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.WorkflowApplicationService(WorkflowClient,AppProperties,FulfillmentCaseRepository,HearingStateRepository,PartySubmissionRepository,EvidenceItemRepository,AdjudicationDraftRepository,AuditRecorder,ObjectMapper,TransactionTemplate,long,int,String,String,int,int)」。
    // 具体功能：「WorkflowApplicationService.WorkflowApplicationService(WorkflowClient,AppProperties,FulfillmentCaseRepository,HearingStateRepository,PartySubmissionRepository,EvidenceItemRepository,AdjudicationDraftRepository,AuditRecorder,ObjectMapper,TransactionTemplate,long,int,String,String,int,int)」：通过构造器接收 「workflowClient」(WorkflowClient)、「properties」(AppProperties)、「caseRepository」(FulfillmentCaseRepository)、「stateRepository」(HearingStateRepository)、「submissionRepository」(PartySubmissionRepository)、「evidenceRepository」(EvidenceItemRepository)、「draftRepository」(AdjudicationDraftRepository)、「auditRecorder」(AuditRecorder)、「objectMapper」(ObjectMapper)、「transactions」(TransactionTemplate)、「evidenceWaitHours」(long)、「maxEvidenceRounds」(int)、「deliberationMode」(String)、「deliberationMinimumRiskLevel」(String)、「deliberationScoreThreshold」(int)、「deliberationMaxRegenerations」(int) 并保存为「WorkflowApplicationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「WorkflowApplicationService.WorkflowApplicationService(WorkflowClient,AppProperties,FulfillmentCaseRepository,HearingStateRepository,PartySubmissionRepository,EvidenceItemRepository,AdjudicationDraftRepository,AuditRecorder,ObjectMapper,TransactionTemplate,long,int,String,String,int,int)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「WorkflowApplicationService.WorkflowApplicationService(WorkflowClient,AppProperties,FulfillmentCaseRepository,HearingStateRepository,PartySubmissionRepository,EvidenceItemRepository,AdjudicationDraftRepository,AuditRecorder,ObjectMapper,TransactionTemplate,long,int,String,String,int,int)」向下依次触达 「Duration.ofHours」、「DeliberationInterventionMode.from」。
    // 系统意义：「WorkflowApplicationService.WorkflowApplicationService(WorkflowClient,AppProperties,FulfillmentCaseRepository,HearingStateRepository,PartySubmissionRepository,EvidenceItemRepository,AdjudicationDraftRepository,AuditRecorder,ObjectMapper,TransactionTemplate,long,int,String,String,int,int)」负责主链路中的“工作流应用服务”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public WorkflowApplicationService(
            WorkflowClient workflowClient,
            AppProperties properties,
            FulfillmentCaseRepository caseRepository,
            HearingStateRepository stateRepository,
            PartySubmissionRepository submissionRepository,
            EvidenceItemRepository evidenceRepository,
            AdjudicationDraftRepository draftRepository,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            @Value("${app.temporal.evidence-wait-hours:72}") long evidenceWaitHours,
            @Value("${app.temporal.max-evidence-rounds:2}") int maxEvidenceRounds,
            @Value("${app.temporal.deliberation-mode:FINAL_ONLY}") String deliberationMode,
            @Value("${app.temporal.deliberation-min-risk-level:HIGH}") String deliberationMinimumRiskLevel,
            @Value("${app.temporal.deliberation-score-threshold:80}") int deliberationScoreThreshold,
            @Value("${app.temporal.deliberation-max-regenerations:2}") int deliberationMaxRegenerations) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.caseRepository = caseRepository;
        this.stateRepository = stateRepository;
        this.submissionRepository = submissionRepository;
        this.evidenceRepository = evidenceRepository;
        this.draftRepository = draftRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.evidenceWaitTimeout = Duration.ofHours(evidenceWaitHours);
        this.maxEvidenceRounds = maxEvidenceRounds;
        this.deliberationMode = DeliberationInterventionMode.from(deliberationMode);
        this.deliberationMinimumRiskLevel = deliberationMinimumRiskLevel;
        this.deliberationScoreThreshold = deliberationScoreThreshold;
        this.deliberationMaxRegenerations = deliberationMaxRegenerations;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.start(String,AuthenticatedActor,String)」。
    // 具体功能：「WorkflowApplicationService.start(String,AuthenticatedActor,String)」：启动Start：先在显式事务模板中原子写入业务事实，再在事务模板中读取并更新同一版本的案件状态，再按稳定 Workflow ID 获取或创建 Temporal 句柄；实际协作者为 「workflowClient.newWorkflowStub」、「WorkflowClient.start」、「WorkflowOptions.newBuilder」、「Duration.ofDays」；不满足前置条件时抛出 「BusinessException」；处理的关键状态/协议值包括 「case_status」、「case_id」、「WORKFLOW_START_REQUESTED」、「CASE_WORKFLOW」，最终返回「WorkflowStartView」。
    // 上游调用：「WorkflowApplicationService.start(String,AuthenticatedActor,String)」由使用「WorkflowApplicationService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「WorkflowApplicationService.start(String,AuthenticatedActor,String)」向下依次触达 「workflowClient.newWorkflowStub」、「WorkflowClient.start」、「WorkflowOptions.newBuilder」、「Duration.ofDays」；计算结果以「WorkflowStartView」交给调用方。
    // 系统意义：「WorkflowApplicationService.start(String,AuthenticatedActor,String)」负责主链路中的“Start”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public WorkflowStartView start(
            String caseId, AuthenticatedActor actor, String idempotencyKey) {
        FulfillmentCaseEntity disputeCase =
                transactions.execute(
                        ignored -> {
                            FulfillmentCaseEntity entity = authorizedCase(caseId, actor);
                            if (entity.getCaseStatus() != CaseStatus.ROUTED
                                    && entity.getCaseStatus() != CaseStatus.HEARING) {
                                throw new BusinessException(
                                        ErrorCode.CASE_STATUS_INVALID,
                                        "case must be routed before workflow start",
                                        Map.of(
                                                "case_status",
                                                entity.getCaseStatus().name()));
                            }
                            return entity;
                        });
        String workflowId = workflowId(caseId);
        FulfillmentDisputeWorkflow workflow =
                workflowClient.newWorkflowStub(
                        FulfillmentDisputeWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId)
                                .setTaskQueue(properties.temporal().taskQueue())
                                .setWorkflowExecutionTimeout(Duration.ofDays(30))
                                .build());
        try {
            WorkflowClient.start(
                    workflow::run,
                    new FulfillmentDisputeCommand(
                            caseId,
                            workflowId,
                            disputeCase.getRouteType(),
                            1,
                            evidenceWaitTimeout,
                            Duration.ofDays(7),
                            maxEvidenceRounds,
                            disputeCase.getRiskLevel().name(),
                            deliberationMode,
                            deliberationMinimumRiskLevel,
                            deliberationScoreThreshold,
                            deliberationMaxRegenerations));
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // Deterministic workflow IDs make repeated starts idempotent.
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_START_FAILED,
                    "failed to start case workflow",
                    Map.of("case_id", caseId));
        }
        transactions.executeWithoutResult(
                ignored ->
                        auditRecorder.record(
                                actor,
                                "WORKFLOW_START_REQUESTED",
                                "CASE_WORKFLOW",
                                workflowId,
                                caseId,
                                Map.of(),
                                Map.of(
                                        "idempotency_key", idempotencyKey,
                                        "route_type",
                                                disputeCase.getRouteType().name())));
        return new WorkflowStartView(
                caseId,
                workflowId,
                "STARTED",
                disputeCase.getRouteType().name());
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.getHearing(String,AuthenticatedActor)」。
    // 具体功能：「WorkflowApplicationService.getHearing(String,AuthenticatedActor)」：读取庭审：先把 Optional 空值转换为明确业务异常；实际协作者为 「stateRepository.findByCaseId」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「state.getId」、「state.getCaseId」；处理的关键状态/协议值包括 「case_id」，最终返回「HearingView」。
    // 上游调用：「WorkflowApplicationService.getHearing(String,AuthenticatedActor)」由使用「WorkflowApplicationService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「WorkflowApplicationService.getHearing(String,AuthenticatedActor)」向下依次触达 「stateRepository.findByCaseId」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「state.getId」、「state.getCaseId」；计算结果以「HearingView」交给调用方。
    // 系统意义：「WorkflowApplicationService.getHearing(String,AuthenticatedActor)」负责主链路中的“庭审”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    public HearingView getHearing(String caseId, AuthenticatedActor actor) {
        authorizedCaseReadOnly(caseId, actor);
        var state =
                stateRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "hearing not found",
                                                Map.of("case_id", caseId)));
        String draftId =
                draftRepository
                        .findFirstByCaseIdOrderByDraftVersionDesc(caseId)
                        .map(AdjudicationDraftEntity::getId)
                        .orElse(null);
        return new HearingView(
                state.getId(),
                state.getCaseId(),
                state.getWorkflowId(),
                state.getHearingStatus().name(),
                state.getCurrentNode(),
                state.getRoundNo(),
                state.getConfidence(),
                state.isManualRequired(),
                state.getPendingRequestsJson(),
                state.getWaitingUntil(),
                state.getCompletedAt(),
                draftId);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.getLatestDraft(String,AuthenticatedActor)」。
    // 具体功能：「WorkflowApplicationService.getLatestDraft(String,AuthenticatedActor)」：读取草案：先把 Optional 空值转换为明确业务异常；实际协作者为 「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「draft.getId」、「draft.getCaseId」、「draft.getDraftVersion」；处理的关键状态/协议值包括 「case_id」，最终返回「AdjudicationDraftView」。
    // 上游调用：「WorkflowApplicationService.getLatestDraft(String,AuthenticatedActor)」由使用「WorkflowApplicationService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「WorkflowApplicationService.getLatestDraft(String,AuthenticatedActor)」向下依次触达 「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「draft.getId」、「draft.getCaseId」、「draft.getDraftVersion」；计算结果以「AdjudicationDraftView」交给调用方。
    // 系统意义：「WorkflowApplicationService.getLatestDraft(String,AuthenticatedActor)」负责主链路中的“草案”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    public AdjudicationDraftView getLatestDraft(
            String caseId, AuthenticatedActor actor) {
        authorizedCaseReadOnly(caseId, actor);
        AdjudicationDraftEntity draft =
                draftRepository
                        .findFirstByCaseIdOrderByDraftVersionDesc(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "adjudication draft not found",
                                                Map.of("case_id", caseId)));
        return new AdjudicationDraftView(
                draft.getId(),
                draft.getCaseId(),
                draft.getDraftVersion(),
                draft.getRecommendedDecision(),
                draft.getConfidence(),
                draft.getDraftText(),
                readJson(draft.getFactFindingsJson()),
                readJson(draft.getEvidenceAssessmentJson()),
                readJson(draft.getPolicyApplicationJson()),
                readJson(draft.getReviewerAttentionJson()),
                draft.getDraftStatus());
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.submitPartyEvidence(String,String,String,List,AuthenticatedActor,String)」。
    // 具体功能：「WorkflowApplicationService.submitPartyEvidence(String,String,String,List,AuthenticatedActor,String)」：提交当事方证据：先在事务模板中读取并更新同一版本的案件状态，再把新状态写入 PostgreSQL 事实表，再按主键读取现有事实并显式处理不存在分支；实际协作者为 「submissionRepository.findById」、「submissionRepository.save」、「workflow.submitEvidence」、「UUID.nameUUIDFromBytes」；不满足前置条件时抛出 「com」；处理的关键状态/协议值包括 「SUB_」、「:」、「-」、「SUPPLEMENTAL_EVIDENCE」，最终返回「PartySubmissionView」。
    // 上游调用：「WorkflowApplicationService.submitPartyEvidence(String,String,String,List,AuthenticatedActor,String)」由使用「WorkflowApplicationService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「WorkflowApplicationService.submitPartyEvidence(String,String,String,List,AuthenticatedActor,String)」向下依次触达 「submissionRepository.findById」、「submissionRepository.save」、「workflow.submitEvidence」、「UUID.nameUUIDFromBytes」；计算结果以「PartySubmissionView」交给调用方。
    // 系统意义：「WorkflowApplicationService.submitPartyEvidence(String,String,String,List,AuthenticatedActor,String)」负责主链路中的“当事方证据”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public PartySubmissionView submitPartyEvidence(
            String caseId,
            String expectedParty,
            String text,
            List<String> evidenceIds,
            AuthenticatedActor actor,
            String idempotencyKey) {
        List<String> safeEvidenceIds =
                evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        String submissionId =
                "SUB_"
                        + UUID.nameUUIDFromBytes(
                                        (caseId
                                                        + ":"
                                                        + expectedParty
                                                        + ":"
                                                        + idempotencyKey)
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        PartySubmissionEntity submission =
                transactions.execute(
                        ignored -> {
                            FulfillmentCaseEntity disputeCase =
                                    authorizedCase(caseId, actor);
                            assertParty(expectedParty, disputeCase, actor);
                            assertEvidenceBelongsToCase(caseId, safeEvidenceIds);
                            var existing = submissionRepository.findById(submissionId);
                            if (existing.isPresent()) {
                                if (!java.util.Objects.equals(
                                                existing.get().getSubmissionText(), text)
                                        || !existing
                                                .get()
                                                .getAttachmentIdsJson()
                                                .equals(writeJson(safeEvidenceIds))) {
                                    throw new com.example.dispute.common.exception
                                            .IdempotencyConflictException(
                                            "submission idempotency key was reused");
                                }
                                return existing.get();
                            }
                            PartySubmissionEntity saved =
                                    submissionRepository.save(
                                            PartySubmissionEntity.submit(
                                                    submissionId,
                                                    caseId,
                                                    null,
                                                    expectedParty,
                                                    actor.actorId(),
                                                    "SUPPLEMENTAL_EVIDENCE",
                                                    text,
                                                    writeJson(
                                                            Map.of(
                                                                    "idempotency_key",
                                                                    idempotencyKey)),
                                                    writeJson(safeEvidenceIds)));
                            auditRecorder.record(
                                    actor,
                                    "PARTY_EVIDENCE_SUBMITTED",
                                    "PARTY_SUBMISSION",
                                    saved.getId(),
                                    caseId,
                                    Map.of(),
                                    Map.of(
                                            "party_type", expectedParty,
                                            "evidence_ids", safeEvidenceIds));
                            return saved;
                        });
        String workflowId = workflowIdForCase(caseId);
        signal(
                workflowId,
                workflow ->
                        workflow.submitEvidence(
                                new EvidenceSubmissionSignal(
                                        submission.getId(),
                                        expectedParty,
                                        safeEvidenceIds)));
        return new PartySubmissionView(
                submission.getId(),
                caseId,
                expectedParty,
                safeEvidenceIds,
                workflowId,
                "SIGNALLED");
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.submitReviewerSignal(String,String,String,AuthenticatedActor)」。
    // 具体功能：「WorkflowApplicationService.submitReviewerSignal(String,String,String,AuthenticatedActor)」：提交审核员信号：先在显式事务模板中原子写入业务事实；实际协作者为 「workflow.submitReviewDecision」、「actor.role」、「transactions.executeWithoutResult」、「auditRecorder.record」；不满足前置条件时抛出 「ForbiddenException」；处理的关键状态/协议值包括 「REVIEWER_WORKFLOW_SIGNALLED」、「CASE_WORKFLOW」、「decision」、「reason」，最终返回「void」。
    // 上游调用：「WorkflowApplicationService.submitReviewerSignal(String,String,String,AuthenticatedActor)」由使用「WorkflowApplicationService」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「WorkflowApplicationService.submitReviewerSignal(String,String,String,AuthenticatedActor)」向下依次触达 「workflow.submitReviewDecision」、「actor.role」、「transactions.executeWithoutResult」、「auditRecorder.record」。
    // 系统意义：「WorkflowApplicationService.submitReviewerSignal(String,String,String,AuthenticatedActor)」负责主链路中的“审核员信号”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public void submitReviewerSignal(
            String caseId,
            String decision,
            String reason,
            AuthenticatedActor actor) {
        if (actor.role() != ActorRole.PLATFORM_REVIEWER
                && actor.role() != ActorRole.ADMIN
                && actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException("reviewer role is required");
        }
        authorizedCaseReadOnly(caseId, actor);
        String workflowId = workflowIdForCase(caseId);
        transactions.executeWithoutResult(
                ignored ->
                        auditRecorder.record(
                                actor,
                                "REVIEWER_WORKFLOW_SIGNALLED",
                                "CASE_WORKFLOW",
                                workflowId,
                                caseId,
                                Map.of(),
                                Map.of("decision", decision, "reason", reason)));
        signal(
                workflowId,
                workflow ->
                        workflow.submitReviewDecision(
                                new HumanReviewSignal(
                                        actor.actorId(),
                                        "PLATFORM_REVIEWER",
                                        decision,
                                        1,
                                        "ACTION_HASH_PENDING",
                                        null,
                                        reason)));
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.signal(String,Consumer)」。
    // 具体功能：「WorkflowApplicationService.signal(String,Consumer)」：发送信号案件 Temporal Workflow 的启动、Signal 和查询：先按稳定 Workflow ID 获取或创建 Temporal 句柄；实际协作者为 「workflowClient.newWorkflowStub」、「action.accept」；不满足前置条件时抛出 「BusinessException」；处理的关键状态/协议值包括 「workflow_id」，最终返回「void」。
    // 上游调用：「WorkflowApplicationService.signal(String,Consumer)」的上游调用点包括 「WorkflowApplicationService.submitPartyEvidence」、「WorkflowApplicationService.submitReviewerSignal」。
    // 下游影响：「WorkflowApplicationService.signal(String,Consumer)」向下依次触达 「workflowClient.newWorkflowStub」、「action.accept」。
    // 系统意义：「WorkflowApplicationService.signal(String,Consumer)」负责主链路中的“案件 Temporal Workflow 的启动、Signal 和查询”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private void signal(
            String workflowId,
            java.util.function.Consumer<FulfillmentDisputeWorkflow> action) {
        try {
            action.accept(
                    workflowClient.newWorkflowStub(
                            FulfillmentDisputeWorkflow.class, workflowId));
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_SIGNAL_FAILED,
                    "failed to signal case workflow",
                    Map.of("workflow_id", workflowId));
        }
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.workflowIdForCase(String)」。
    // 具体功能：「WorkflowApplicationService.workflowIdForCase(String)」：构建工作流标识面向案件；实际协作者为 「stateRepository.findByCaseId」、「state.getWorkflowId」、「workflowId」，最终返回「String」。
    // 上游调用：「WorkflowApplicationService.workflowIdForCase(String)」的上游调用点包括 「WorkflowApplicationService.submitPartyEvidence」、「WorkflowApplicationService.submitReviewerSignal」。
    // 下游影响：「WorkflowApplicationService.workflowIdForCase(String)」向下依次触达 「stateRepository.findByCaseId」、「state.getWorkflowId」、「workflowId」；计算结果以「String」交给调用方。
    // 系统意义：「WorkflowApplicationService.workflowIdForCase(String)」负责主链路中的“工作流标识面向案件”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private String workflowIdForCase(String caseId) {
        return stateRepository
                .findByCaseId(caseId)
                .map(state -> state.getWorkflowId())
                .orElse(workflowId(caseId));
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.authorizedCase(String,AuthenticatedActor)」。
    // 具体功能：「WorkflowApplicationService.authorizedCase(String,AuthenticatedActor)」：授权authorized案件：先把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「assertCanAccess」；处理的关键状态/协议值包括 「case_id」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「WorkflowApplicationService.authorizedCase(String,AuthenticatedActor)」的上游调用点包括 「WorkflowApplicationService.start」、「WorkflowApplicationService.submitPartyEvidence」。
    // 下游影响：「WorkflowApplicationService.authorizedCase(String,AuthenticatedActor)」向下依次触达 「caseRepository.findByIdForUpdate」、「assertCanAccess」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「WorkflowApplicationService.authorizedCase(String,AuthenticatedActor)」负责主链路中的“authorized案件”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private FulfillmentCaseEntity authorizedCase(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity entity =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        assertCanAccess(entity, actor);
        return entity;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.authorizedCaseReadOnly(String,AuthenticatedActor)」。
    // 具体功能：「WorkflowApplicationService.authorizedCaseReadOnly(String,AuthenticatedActor)」：授权authorized案件ReadOnly：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「assertCanAccess」；处理的关键状态/协议值包括 「case_id」，最终返回「void」。
    // 上游调用：「WorkflowApplicationService.authorizedCaseReadOnly(String,AuthenticatedActor)」的上游调用点包括 「WorkflowApplicationService.getHearing」、「WorkflowApplicationService.getLatestDraft」、「WorkflowApplicationService.submitReviewerSignal」。
    // 下游影响：「WorkflowApplicationService.authorizedCaseReadOnly(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「assertCanAccess」。
    // 系统意义：「WorkflowApplicationService.authorizedCaseReadOnly(String,AuthenticatedActor)」负责主链路中的“authorized案件ReadOnly”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void authorizedCaseReadOnly(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity entity =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        assertCanAccess(entity, actor);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「WorkflowApplicationService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」：断言Can访问；实际协作者为 「actor.role」、「actor.actorId」、「entity.getUserId」、「entity.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「WorkflowApplicationService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「WorkflowApplicationService.authorizedCase」、「WorkflowApplicationService.authorizedCaseReadOnly」。
    // 下游影响：「WorkflowApplicationService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「entity.getUserId」、「entity.getMerchantId」。
    // 系统意义：「WorkflowApplicationService.assertCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」在“Can访问”进入下游前阻断非法状态；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static void assertCanAccess(
            FulfillmentCaseEntity entity, AuthenticatedActor actor) {
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

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.assertParty(String,FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「WorkflowApplicationService.assertParty(String,FulfillmentCaseEntity,AuthenticatedActor)」：断言当事方；实际协作者为 「actor.role」、「actor.actorId」、「entity.getUserId」、「entity.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」；处理的关键状态/协议值包括 「USER」、「MERCHANT」，最终返回「void」。
    // 上游调用：「WorkflowApplicationService.assertParty(String,FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「WorkflowApplicationService.submitPartyEvidence」。
    // 下游影响：「WorkflowApplicationService.assertParty(String,FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「entity.getUserId」、「entity.getMerchantId」。
    // 系统意义：「WorkflowApplicationService.assertParty(String,FulfillmentCaseEntity,AuthenticatedActor)」在“当事方”进入下游前阻断非法状态；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static void assertParty(
            String expectedParty,
            FulfillmentCaseEntity entity,
            AuthenticatedActor actor) {
        boolean valid =
                ("USER".equals(expectedParty)
                                && actor.role() == ActorRole.USER
                                && actor.actorId().equals(entity.getUserId()))
                        || ("MERCHANT".equals(expectedParty)
                                && actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(entity.getMerchantId()));
        if (!valid) {
            throw new ForbiddenException(
                    "submission endpoint does not match authenticated party");
        }
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.assertEvidenceBelongsToCase(String,List)」。
    // 具体功能：「WorkflowApplicationService.assertEvidenceBelongsToCase(String,List)」：断言证据Belongs案件；实际协作者为 「evidenceRepository.findAllById」、「item.getCaseId」、「count」；不满足前置条件时抛出 「BusinessException」；处理的关键状态/协议值包括 「case_id」，最终返回「void」。
    // 上游调用：「WorkflowApplicationService.assertEvidenceBelongsToCase(String,List)」的上游调用点包括 「WorkflowApplicationService.submitPartyEvidence」。
    // 下游影响：「WorkflowApplicationService.assertEvidenceBelongsToCase(String,List)」向下依次触达 「evidenceRepository.findAllById」、「item.getCaseId」、「count」。
    // 系统意义：「WorkflowApplicationService.assertEvidenceBelongsToCase(String,List)」在“证据Belongs案件”进入下游前阻断非法状态；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private void assertEvidenceBelongsToCase(
            String caseId, List<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return;
        }
        long matches =
                evidenceRepository.findAllById(evidenceIds).stream()
                        .filter(item -> caseId.equals(item.getCaseId()))
                        .count();
        if (matches != evidenceIds.size()) {
            throw new BusinessException(
                    ErrorCode.EVIDENCE_NOT_FOUND,
                    "one or more evidence items do not belong to this case",
                    Map.of("case_id", caseId));
        }
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.writeJson(Object)」。
    // 具体功能：「WorkflowApplicationService.writeJson(Object)」：写入JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「WorkflowApplicationService.writeJson(Object)」的上游调用点包括 「WorkflowApplicationService.submitPartyEvidence」。
    // 下游影响：「WorkflowApplicationService.writeJson(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「WorkflowApplicationService.writeJson(Object)」负责主链路中的“JSON”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize submission", exception);
        }
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.readJson(String)」。
    // 具体功能：「WorkflowApplicationService.readJson(String)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「com.fasterxml.jackson.databind.JsonNode」。
    // 上游调用：「WorkflowApplicationService.readJson(String)」的上游调用点包括 「WorkflowApplicationService.getLatestDraft」。
    // 下游影响：「WorkflowApplicationService.readJson(String)」向下依次触达 「objectMapper.readTree」；计算结果以「com.fasterxml.jackson.databind.JsonNode」交给调用方。
    // 系统意义：「WorkflowApplicationService.readJson(String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private com.fasterxml.jackson.databind.JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot parse persisted workflow json", exception);
        }
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「WorkflowApplicationService.workflowId(String)」。
    // 具体功能：「WorkflowApplicationService.workflowId(String)」：构建工作流标识；处理的关键状态/协议值包括 「CASEWORKFLOW_」，最终返回「String」。
    // 上游调用：「WorkflowApplicationService.workflowId(String)」的上游调用点包括 「WorkflowApplicationService.start」、「WorkflowApplicationService.workflowIdForCase」。
    // 下游影响：「WorkflowApplicationService.workflowId(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「WorkflowApplicationService.workflowId(String)」负责主链路中的“工作流标识”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static String workflowId(String caseId) {
        return "CASEWORKFLOW_" + caseId;
    }
}

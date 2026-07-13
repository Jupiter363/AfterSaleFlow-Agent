/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：在 Temporal Activity 边界执行最终五段 Workflow 到现有领域服务的 Activity 适配所需的数据库、Agent 或工具副作用。
 * 业务链路：核心入口/契约为 「markTransferred」、「planRemedy」、「createReviewPacket」、「closeCaseAndEvaluate」、「initialize」、「runStage」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.application;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.hearing.application.HearingOutcomeOrchestrationService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.workflow.domain.ApprovalValidationResult;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.CriticActivityResult;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.ExecutionAction;
import com.example.dispute.workflow.domain.ExecutionActionActivityResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.FrozenDeliberationSnapshot;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.HearingStageActivityResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewGateSnapshot;
import com.example.dispute.workflow.temporal.DeliberationPanelActivities;
import com.example.dispute.workflow.temporal.DisputeHearingActivities;
import com.example.dispute.workflow.temporal.ExecutionActivities;
import com.example.dispute.workflow.temporal.FulfillmentDisputeActivities;
import com.example.dispute.workflow.temporal.HumanReviewActivities;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.example.dispute.infrastructure.persistence.entity.DeliberationReportEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.DeliberationReportRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Transitional production adapter for the new workflow types.
 *
 * <p>It reuses the validated persistence, remedy, review, executor and closure
 * services while the old monolithic workflow type remains registered only for
 * already-running histories. New orchestration never invokes those services
 * directly from workflow code.
 */
// 所属模块：【Temporal 持久化编排 / 应用编排层】类型「FinalWorkflowActivitiesAdapter」。
// 类型职责：在 Temporal Activity 边界执行最终五段 Workflow 到现有领域服务的 Activity 适配所需的数据库、Agent 或工具副作用；本类型显式提供 「FinalWorkflowActivitiesAdapter」、「markTransferred」、「planRemedy」、「createReviewPacket」、「closeCaseAndEvaluate」、「initialize」。
// 协作关系：主要由 「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate」、「FinalWorkflowActivitiesAdapterTest.finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class FinalWorkflowActivitiesAdapter
        implements FulfillmentDisputeActivities,
                DisputeHearingActivities,
                DeliberationPanelActivities,
                HumanReviewActivities,
                ExecutionActivities {

    private final CaseFulfillmentDisputeActivitiesImpl legacy;
    private final ApprovalRecordRepository approvalRepository;
    private final ReviewPacketRepository packetRepository;
    private final ReviewTaskRepository taskRepository;
    private final DeliberationReportRepository deliberationRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingStateRepository hearingStateRepository;
    private final SettlementProposalRepository settlementProposalRepository;
    private final ObjectMapper objectMapper;
    private final HearingRoundService hearingRoundService;
    private final HearingOutcomeOrchestrationService hearingOutcomeOrchestration;
    private final CasePhaseClockRepository phaseClockRepository;
    private final Clock clock;
    private final ConcurrentMap<String, HearingAnalysisActivityResult>
            hearingRounds = new ConcurrentHashMap<>();

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.FinalWorkflowActivitiesAdapter(CaseFulfillmentDisputeActivitiesImpl,ApprovalRecordRepository,ReviewPacketRepository,ReviewTaskRepository,DeliberationReportRepository,AdjudicationDraftRepository,HearingStateRepository,SettlementProposalRepository,ObjectMapper,HearingRoundService,HearingOutcomeOrchestrationService,CasePhaseClockRepository,Clock)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.FinalWorkflowActivitiesAdapter(CaseFulfillmentDisputeActivitiesImpl,ApprovalRecordRepository,ReviewPacketRepository,ReviewTaskRepository,DeliberationReportRepository,AdjudicationDraftRepository,HearingStateRepository,SettlementProposalRepository,ObjectMapper,HearingRoundService,HearingOutcomeOrchestrationService,CasePhaseClockRepository,Clock)」：通过构造器接收 「legacy」(CaseFulfillmentDisputeActivitiesImpl)、「approvalRepository」(ApprovalRecordRepository)、「packetRepository」(ReviewPacketRepository)、「taskRepository」(ReviewTaskRepository)、「deliberationRepository」(DeliberationReportRepository)、「draftRepository」(AdjudicationDraftRepository)、「hearingStateRepository」(HearingStateRepository)、「settlementProposalRepository」(SettlementProposalRepository)、「objectMapper」(ObjectMapper)、「hearingRoundService」(HearingRoundService)、「hearingOutcomeOrchestration」(HearingOutcomeOrchestrationService)、「phaseClockRepository」(CasePhaseClockRepository)、「clock」(Clock) 并保存为「FinalWorkflowActivitiesAdapter」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「FinalWorkflowActivitiesAdapter.FinalWorkflowActivitiesAdapter(CaseFulfillmentDisputeActivitiesImpl,ApprovalRecordRepository,ReviewPacketRepository,ReviewTaskRepository,DeliberationReportRepository,AdjudicationDraftRepository,HearingStateRepository,SettlementProposalRepository,ObjectMapper,HearingRoundService,HearingOutcomeOrchestrationService,CasePhaseClockRepository,Clock)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「FinalWorkflowActivitiesAdapterTest.finalConvergenceInvokesLegacyAnalysisOnlyAtC6DraftGeneration」、「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate」 显式创建。
    // 下游影响：「FinalWorkflowActivitiesAdapter.FinalWorkflowActivitiesAdapter(CaseFulfillmentDisputeActivitiesImpl,ApprovalRecordRepository,ReviewPacketRepository,ReviewTaskRepository,DeliberationReportRepository,AdjudicationDraftRepository,HearingStateRepository,SettlementProposalRepository,ObjectMapper,HearingRoundService,HearingOutcomeOrchestrationService,CasePhaseClockRepository,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FinalWorkflowActivitiesAdapter.FinalWorkflowActivitiesAdapter(CaseFulfillmentDisputeActivitiesImpl,ApprovalRecordRepository,ReviewPacketRepository,ReviewTaskRepository,DeliberationReportRepository,AdjudicationDraftRepository,HearingStateRepository,SettlementProposalRepository,ObjectMapper,HearingRoundService,HearingOutcomeOrchestrationService,CasePhaseClockRepository,Clock)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public FinalWorkflowActivitiesAdapter(
            CaseFulfillmentDisputeActivitiesImpl legacy,
            ApprovalRecordRepository approvalRepository,
            ReviewPacketRepository packetRepository,
            ReviewTaskRepository taskRepository,
            DeliberationReportRepository deliberationRepository,
            AdjudicationDraftRepository draftRepository,
            HearingStateRepository hearingStateRepository,
            SettlementProposalRepository settlementProposalRepository,
            ObjectMapper objectMapper,
            HearingRoundService hearingRoundService,
            HearingOutcomeOrchestrationService hearingOutcomeOrchestration,
            CasePhaseClockRepository phaseClockRepository,
            Clock clock) {
        this.legacy = legacy;
        this.approvalRepository = approvalRepository;
        this.packetRepository = packetRepository;
        this.taskRepository = taskRepository;
        this.deliberationRepository = deliberationRepository;
        this.draftRepository = draftRepository;
        this.hearingStateRepository = hearingStateRepository;
        this.settlementProposalRepository = settlementProposalRepository;
        this.objectMapper = objectMapper;
        this.hearingRoundService = hearingRoundService;
        this.hearingOutcomeOrchestration = hearingOutcomeOrchestration;
        this.phaseClockRepository = phaseClockRepository;
        this.clock = clock;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.markTransferred(String,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.markTransferred(String,String)」：标记Transferred，最终返回「void」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.markTransferred(String,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.markTransferred(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FinalWorkflowActivitiesAdapter.markTransferred(String,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void markTransferred(String caseId, String workflowId) {
        // Routing already persists TRANSFERRED before workflow start.
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.planRemedy(String,String,String,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.planRemedy(String,String,String,String)」：规划补救；实际协作者为 「legacy.planRemedy」，最终返回「String」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.planRemedy(String,String,String,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.planRemedy(String,String,String,String)」向下依次触达 「legacy.planRemedy」；计算结果以「String」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.planRemedy(String,String,String,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public String planRemedy(
            String caseId,
            String workflowId,
            String draftId,
            String deliberationId) {
        return legacy.planRemedy(caseId, workflowId);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.createReviewPacket(String,String,String,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.createReviewPacket(String,String,String,String)」：创建审核审核包：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「taskRepository.findById」、「packetRepository.findById」、「legacy.createReviewTask」、「task.getPacketId」，最终返回「ReviewGateSnapshot」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.createReviewPacket(String,String,String,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.createReviewPacket(String,String,String,String)」向下依次触达 「taskRepository.findById」、「packetRepository.findById」、「legacy.createReviewTask」、「task.getPacketId」；计算结果以「ReviewGateSnapshot」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.createReviewPacket(String,String,String,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Override
    public ReviewGateSnapshot createReviewPacket(
            String caseId,
            String draftId,
            String deliberationId,
            String remedyPlanId) {
        String taskId = legacy.createReviewTask(caseId, remedyPlanId);
        var task =
                taskRepository
                        .findById(taskId)
                        .orElseThrow(() -> new IllegalStateException("review task not found"));
        var packet =
                packetRepository
                        .findById(task.getPacketId())
                        .orElseThrow(() -> new IllegalStateException("review packet not found"));
        return new ReviewGateSnapshot(
                taskId,
                packet.getId(),
                packet.getPacketVersion(),
                packet.getActionHash(),
                packet.getExpiresAt().toInstant().toEpochMilli(),
                task.getRequiredRole());
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.closeCaseAndEvaluate(String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.closeCaseAndEvaluate(String)」：关闭案件并且Evaluate；实际协作者为 「legacy.closeCaseAndEvaluate」，最终返回「void」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.closeCaseAndEvaluate(String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.closeCaseAndEvaluate(String)」向下依次触达 「legacy.closeCaseAndEvaluate」。
    // 系统意义：「FinalWorkflowActivitiesAdapter.closeCaseAndEvaluate(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void closeCaseAndEvaluate(String caseId) {
        legacy.closeCaseAndEvaluate(caseId);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.initialize(HearingWorkflowCommand)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.initialize(HearingWorkflowCommand)」：初始化最终五段 Workflow 到现有领域服务的 Activity 适配；实际协作者为 「legacy.initializeHearing」、「command.caseId」、「command.workflowId」、「command.evidenceWaitTimeout」，最终返回「void」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.initialize(HearingWorkflowCommand)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.initialize(HearingWorkflowCommand)」向下依次触达 「legacy.initializeHearing」、「command.caseId」、「command.workflowId」、「command.evidenceWaitTimeout」。
    // 系统意义：「FinalWorkflowActivitiesAdapter.initialize(HearingWorkflowCommand)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void initialize(HearingWorkflowCommand command) {
        legacy.initializeHearing(
                new CaseWorkflowInput(
                        command.caseId(),
                        command.workflowId(),
                        RouteType.FULL_HEARING,
                        command.evidenceWaitTimeout(),
                        command.maxEvidenceRounds()));
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.runStage(String,String,String,int,long,boolean,boolean,int)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.runStage(String,String,String,int,long,boolean,boolean,int)」：运行Stage；实际协作者为 「hearingRounds.computeIfAbsent」、「legacy.analyzeHearing」、「analysis.requiresAdditionalEvidence」、「analysis.manualRequired」；处理的关键状态/协议值包括 「C6_DRAFT_GENERATION」、「FINAL_CONVERGENCE_DEFERRED_TO_C6_」、「:」、「C2_EVIDENCE_GAP」，最终返回「HearingStageActivityResult」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.runStage(String,String,String,int,long,boolean,boolean,int)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.runStage(String,String,String,int,long,boolean,boolean,int)」向下依次触达 「hearingRounds.computeIfAbsent」、「legacy.analyzeHearing」、「analysis.requiresAdditionalEvidence」、「analysis.manualRequired」；计算结果以「HearingStageActivityResult」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.runStage(String,String,String,int,long,boolean,boolean,int)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public HearingStageActivityResult runStage(
            String caseId,
            String workflowId,
            String stage,
            int round,
            long dossierVersion,
            boolean evidenceTimedOut,
            boolean finalConvergence,
            int maxHearingRounds) {
        if (finalConvergence && !"C6_DRAFT_GENERATION".equals(stage)) {
            return new HearingStageActivityResult(
                    stage,
                    true,
                    false,
                    false,
                    null,
                    "FINAL_CONVERGENCE_DEFERRED_TO_C6_" + stage);
        }
        String key = workflowId + ":" + round + ":" + finalConvergence;
        HearingAnalysisActivityResult analysis =
                hearingRounds.computeIfAbsent(
                        key,
                        ignored ->
                                legacy.analyzeHearing(
                                        new HearingAnalysisActivityCommand(
                                                caseId,
                                                workflowId,
                                                round,
                                                evidenceTimedOut,
                                                round > 0,
                                                finalConvergence,
                                                maxHearingRounds)));
        return new HearingStageActivityResult(
                stage,
                true,
                "C2_EVIDENCE_GAP".equals(stage)
                        && analysis.requiresAdditionalEvidence(),
                analysis.manualRequired(),
                "C6_DRAFT_GENERATION".equals(stage)
                        ? analysis.draftId()
                        : null,
                "LEGACY_MIGRATION_" + round + "_" + stage);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.recordEvidence(EvidenceSubmissionSignal)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.recordEvidence(EvidenceSubmissionSignal)」：记录证据；实际协作者为 「legacy.recordPartyEvidence」、「signal.partyRole」、「signal.submissionId」、「signal.evidenceRefs」，最终返回「long」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.recordEvidence(EvidenceSubmissionSignal)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.recordEvidence(EvidenceSubmissionSignal)」向下依次触达 「legacy.recordPartyEvidence」、「signal.partyRole」、「signal.submissionId」、「signal.evidenceRefs」；计算结果以「long」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.recordEvidence(EvidenceSubmissionSignal)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public long recordEvidence(EvidenceSubmissionSignal signal) {
        legacy.recordPartyEvidence(
                new PartyEvidenceSignal(
                        signal.partyRole(),
                        signal.submissionId(),
                        signal.evidenceRefs()));
        return 0;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.persistStageTrace(String,String,String,int,long,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.persistStageTrace(String,String,String,int,long,String)」：持久化Stage链路标识，最终返回「void」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.persistStageTrace(String,String,String,int,long,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.persistStageTrace(String,String,String,int,long,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FinalWorkflowActivitiesAdapter.persistStageTrace(String,String,String,int,long,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void persistStageTrace(
            String caseId,
            String workflowId,
            String stage,
            int round,
            long dossierVersion,
            String outputVersion) {
        // Legacy analysis persistence already records every C1-C6 node.
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.complete(String,String,String,boolean,boolean,long,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.complete(String,String,String,boolean,boolean,long,String)」：完成最终五段 Workflow 到现有领域服务的 Activity 适配：先把新状态写入 PostgreSQL 事实表；实际协作者为 「hearingRoundService.expire」、「phaseClockRepository.findByCaseIdAndClockType」、「phaseClockRepository.save」、「Math.toIntExact」；处理的关键状态/协议值包括 「DEADLINE_EXPIRED」、「temporal-worker」、「SETTLEMENT_CONFIRMED」、「:」，最终返回「void」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.complete(String,String,String,boolean,boolean,long,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.complete(String,String,String,boolean,boolean,long,String)」向下依次触达 「hearingRoundService.expire」、「phaseClockRepository.findByCaseIdAndClockType」、「phaseClockRepository.save」、「Math.toIntExact」。
    // 系统意义：「FinalWorkflowActivitiesAdapter.complete(String,String,String,boolean,boolean,long,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void complete(
            String caseId,
            String workflowId,
            String status,
            boolean manualRequired,
            boolean evidenceTimedOut,
            long dossierVersion,
            String stopReason) {
        if ("DEADLINE_EXPIRED".equals(stopReason)) {
            hearingRoundService.expire(
                    caseId, Math.toIntExact(dossierVersion), "temporal-worker");
        }
        phaseClockRepository
                .findByCaseIdAndClockType(caseId, PhaseClockType.HEARING)
                .ifPresent(
                        phaseClock -> {
                            if ("DEADLINE_EXPIRED".equals(stopReason)) {
                                phaseClock.expire(
                                        OffsetDateTime.now(clock),
                                        "temporal-worker");
                            } else if (stopReason != null) {
                                phaseClock.complete(
                                        OffsetDateTime.now(clock),
                                        stopReason,
                                        "temporal-worker");
                            }
                            phaseClockRepository.save(phaseClock);
                        });
        legacy.completeHearing(
                caseId,
                workflowId,
                manualRequired,
                evidenceTimedOut);
        if (shouldOpenHumanReviewGate(status)) {
            if ("SETTLEMENT_CONFIRMED".equals(stopReason)) {
                ensureSettlementDraft(caseId);
            }
            hearingOutcomeOrchestration.orchestrate(caseId, "temporal-worker");
        }
        hearingRounds.keySet().removeIf(key -> key.startsWith(workflowId + ":"));
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.ensureSettlementDraft(String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.ensureSettlementDraft(String)」：确保和解草案：先把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「settlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc」、「hearingStateRepository.findByCaseId」、「draftRepository.save」；处理的关键状态/协议值包括 「SETTLEMENT_CONFIRMED」、「DRAFT_」、「-」、「双方已确认一致方案」，最终返回「void」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.ensureSettlementDraft(String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.complete」。
    // 下游影响：「FinalWorkflowActivitiesAdapter.ensureSettlementDraft(String)」向下依次触达 「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「settlementProposalRepository.findTopByCaseIdOrderByProposalVersionDesc」、「hearingStateRepository.findByCaseId」、「draftRepository.save」。
    // 系统意义：「FinalWorkflowActivitiesAdapter.ensureSettlementDraft(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void ensureSettlementDraft(String caseId) {
        var latestDraft = draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(caseId);
        if (latestDraft
                .map(AdjudicationDraftEntity::getDraftStatus)
                .filter("SETTLEMENT_CONFIRMED"::equals)
                .isPresent()) {
            return;
        }
        var settlement =
                settlementProposalRepository
                        .findTopByCaseIdOrderByProposalVersionDesc(caseId)
                        .filter(
                                proposal ->
                                        proposal.getProposalStatus()
                                                == SettlementStatus.CONFIRMED)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "confirmed settlement is required"));
        var hearingState =
                hearingStateRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "hearing state not found"));
        int nextVersion =
                latestDraft.map(draft -> draft.getDraftVersion() + 1).orElse(1);
        String proposalText = settlement.getProposalText();
        draftRepository.save(
                AdjudicationDraftEntity.create(
                        "DRAFT_" + UUID.randomUUID().toString().replace("-", ""),
                        caseId,
                        hearingState.getId(),
                        nextVersion,
                        write(List.of("双方已确认一致方案", proposalText)),
                        write(List.of("一致方案由用户与商家共同确认，作为非最终裁决草案来源。")),
                        write(List.of("平台审核员需核验一致方案合法性、可执行性和履约风险。")),
                        write(List.of("核对一致方案对应的订单、库存、物流和售后执行条件")),
                        recommendationFromSettlement(proposalText),
                        new BigDecimal("0.9500"),
                        "双方已达成一致方案：" + proposalText,
                        "deterministic-settlement-draft",
                        null,
                        "SETTLEMENT_CONFIRMED",
                        "temporal-worker"));
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.freeze(DeliberationPanelCommand)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.freeze(DeliberationPanelCommand)」：冻结冻结评议快照；实际协作者为 「UUID.nameUUIDFromBytes」、「command.caseId」、「command.draftId」、「command.dossierVersion」；处理的关键状态/协议值包括 「:」、「-」、「MIGRATION_RULESET」，最终返回「FrozenDeliberationSnapshot」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.freeze(DeliberationPanelCommand)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.freeze(DeliberationPanelCommand)」向下依次触达 「UUID.nameUUIDFromBytes」、「command.caseId」、「command.draftId」、「command.dossierVersion」；计算结果以「FrozenDeliberationSnapshot」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.freeze(DeliberationPanelCommand)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public FrozenDeliberationSnapshot freeze(
            DeliberationPanelCommand command) {
        String source =
                command.caseId()
                        + ":"
                        + command.draftId()
                        + ":"
                        + command.dossierVersion();
        String fingerprint =
                UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
        return new FrozenDeliberationSnapshot(
                command.caseId(),
                1,
                command.dossierVersion(),
                1,
                "MIGRATION_RULESET",
                1,
                fingerprint);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.runCritic(FrozenDeliberationSnapshot,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.runCritic(FrozenDeliberationSnapshot,String)」：运行评审角色；实际协作者为 「snapshot.fingerprint」；处理的关键状态/协议值包括 「FAILED」、「BLOCKER」、「CRITIC_ADAPTER_MIGRATION_PENDING」，最终返回「CriticActivityResult」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.runCritic(FrozenDeliberationSnapshot,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.runCritic(FrozenDeliberationSnapshot,String)」向下依次触达 「snapshot.fingerprint」；计算结果以「CriticActivityResult」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.runCritic(FrozenDeliberationSnapshot,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public CriticActivityResult runCritic(
            FrozenDeliberationSnapshot snapshot,
            String critic) {
        // Safe migration default: unavailable criticism always forces review.
        return new CriticActivityResult(
                critic,
                "FAILED",
                "BLOCKER",
                List.of("CRITIC_ADAPTER_MIGRATION_PENDING"),
                snapshot.fingerprint());
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」：持久化Report：先把新状态写入 PostgreSQL 事实表；实际协作者为 「deliberationRepository.existsById」、「deliberationRepository.findFirstByCaseIdOrderByReportVersionDesc」、「deliberationRepository.save」、「UUID.nameUUIDFromBytes」；处理的关键状态/协议值包括 「DELIBERATION_」、「:」、「-」、「HIGH」，最终返回「String」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」向下依次触达 「deliberationRepository.existsById」、「deliberationRepository.findFirstByCaseIdOrderByReportVersionDesc」、「deliberationRepository.save」、「UUID.nameUUIDFromBytes」；计算结果以「String」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @Override
    public String persistReport(
            DeliberationPanelCommand command,
            FrozenDeliberationSnapshot snapshot,
            List<CriticActivityResult> reports,
            String panelResult) {
        String id =
                "DELIBERATION_"
                        + UUID.nameUUIDFromBytes(
                                        (command.workflowId()
                                                        + ":"
                                                        + snapshot.fingerprint())
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        if (deliberationRepository.existsById(id)) {
            return id;
        }
        int version =
                deliberationRepository
                                .findFirstByCaseIdOrderByReportVersionDesc(
                                        command.caseId())
                                .map(report -> report.getReportVersion() + 1)
                                .orElse(1);
        List<String> majorRisks =
                reports.stream()
                        .filter(
                                report ->
                                        "HIGH".equals(report.severity())
                                                || "BLOCKER".equals(
                                                        report.severity()))
                        .flatMap(report -> report.blockingIssues().stream())
                        .distinct()
                        .toList();
        deliberationRepository.save(
                DeliberationReportEntity.record(
                        id,
                        command.caseId(),
                        version,
                        command.draftId(),
                        Math.toIntExact(snapshot.dossierVersion()),
                        write(
                                java.util.Map.of(
                                        "panel_result",
                                        panelResult,
                                        "trigger_reasons",
                                        command.triggerReasons(),
                                        "frozen_input_fingerprint",
                                        snapshot.fingerprint(),
                                        "critic_reports",
                                        reports)),
                        write(majorRisks),
                        "[]",
                        write(reports),
                        "[]",
                        "TRACE_" + command.workflowId(),
                        "temporal-worker"));
        return id;
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」：记录非法决定，最终返回「void」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FinalWorkflowActivitiesAdapter.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public void recordInvalidDecision(
            HumanReviewCommand command,
            HumanReviewSignal signal,
            String reason) {
        // The reviewer API records its own rejected decision attempt.
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」：持久化决定；实际协作者为 「signal.humanReviewRecordId」、「command.reviewPacketId」；处理的关键状态/协议值包括 「REVIEW_」，最终返回「String」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」向下依次触达 「signal.humanReviewRecordId」、「command.reviewPacketId」；计算结果以「String」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public String persistDecision(
            HumanReviewCommand command,
            HumanReviewSignal signal,
            String status) {
        // The reviewer API persists the authoritative decision before Signal.
        return signal.humanReviewRecordId() == null
                ? "REVIEW_" + command.reviewPacketId()
                : signal.humanReviewRecordId();
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.validateApproval(ExecutionCommand)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.validateApproval(ExecutionCommand)」：校验审批：先按主键读取现有事实并显式处理不存在分支；实际协作者为 「approvalRepository.findById」、「command.approved」、「command.reviewId」、「record.getActionSnapshotHash」；处理的关键状态/协议值包括 「APPROVAL_REQUIRED」、「HUMAN_REVIEW_RECORD_NOT_FOUND」、「ACTION_HASH_MISMATCH」、「STALE_REVIEW_PACKET」，最终返回「ApprovalValidationResult」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.validateApproval(ExecutionCommand)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.validateApproval(ExecutionCommand)」向下依次触达 「approvalRepository.findById」、「command.approved」、「command.reviewId」、「record.getActionSnapshotHash」；计算结果以「ApprovalValidationResult」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.validateApproval(ExecutionCommand)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public ApprovalValidationResult validateApproval(
            ExecutionCommand command) {
        if (!command.approved()) {
            return new ApprovalValidationResult(false, "APPROVAL_REQUIRED");
        }
        var approval = approvalRepository.findById(command.reviewId());
        if (approval.isEmpty()) {
            return new ApprovalValidationResult(false, "HUMAN_REVIEW_RECORD_NOT_FOUND");
        }
        var record = approval.get();
        if (!record.getActionSnapshotHash().equals(command.actionHash())) {
            return new ApprovalValidationResult(false, "ACTION_HASH_MISMATCH");
        }
        if (record.getReviewPacketVersion() != command.reviewPacketVersion()) {
            return new ApprovalValidationResult(false, "STALE_REVIEW_PACKET");
        }
        if (!"PLATFORM_REVIEWER".equals(record.getReviewerRole())) {
            return new ApprovalValidationResult(false, "UNAUTHORIZED_REVIEWER_ROLE");
        }
        return new ApprovalValidationResult(true, null);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.executeAction(String,ExecutionAction)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.executeAction(String,ExecutionAction)」：执行动作；实际协作者为 「legacy.executeApprovedPlan」、「action.actionId」；处理的关键状态/协议值包括 「SUCCEEDED」，最终返回「ExecutionActionActivityResult」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.executeAction(String,ExecutionAction)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.executeAction(String,ExecutionAction)」向下依次触达 「legacy.executeApprovedPlan」、「action.actionId」；计算结果以「ExecutionActionActivityResult」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.executeAction(String,ExecutionAction)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public ExecutionActionActivityResult executeAction(
            String caseId,
            ExecutionAction action) {
        legacy.executeApprovedPlan(caseId);
        return new ExecutionActionActivityResult(
                action.actionId(), "SUCCEEDED", null);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.lookupAction(String,ExecutionAction)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.lookupAction(String,ExecutionAction)」：构建lookup动作；实际协作者为 「action.actionId」；处理的关键状态/协议值包括 「UNKNOWN」，最终返回「ExecutionActionActivityResult」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.lookupAction(String,ExecutionAction)」由使用「FinalWorkflowActivitiesAdapter」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FinalWorkflowActivitiesAdapter.lookupAction(String,ExecutionAction)」向下依次触达 「action.actionId」；计算结果以「ExecutionActionActivityResult」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.lookupAction(String,ExecutionAction)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    @Override
    public ExecutionActionActivityResult lookupAction(
            String caseId,
            ExecutionAction action) {
        return new ExecutionActionActivityResult(
                action.actionId(), "UNKNOWN", null);
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.write(Object)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.write(Object)」：写入字符串：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.write(Object)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.ensureSettlementDraft」、「FinalWorkflowActivitiesAdapter.persistReport」。
    // 下游影响：「FinalWorkflowActivitiesAdapter.write(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.write(Object)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "cannot serialize deliberation report", exception);
        }
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.recommendationFromSettlement(String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.recommendationFromSettlement(String)」：构建recommendationFrom和解；处理的关键状态/协议值包括 「REFUND」、「退款」、「退费」、「返款」，最终返回「String」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.recommendationFromSettlement(String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.ensureSettlementDraft」。
    // 下游影响：「FinalWorkflowActivitiesAdapter.recommendationFromSettlement(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.recommendationFromSettlement(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private static String recommendationFromSettlement(String proposalText) {
        String upper = proposalText.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("REFUND")
                || proposalText.contains("退款")
                || proposalText.contains("退费")
                || proposalText.contains("返款")) {
            return "REFUND_BY_CONFIRMED_SETTLEMENT";
        }
        if (upper.contains("RESHIP")
                || upper.contains("RESEND")
                || proposalText.contains("补发")
                || proposalText.contains("重发")
                || proposalText.contains("重新发")
                || proposalText.contains("再次发")) {
            return "RESHIP_BY_CONFIRMED_SETTLEMENT";
        }
        if (upper.contains("REPLACE")
                || upper.contains("EXCHANGE")
                || proposalText.contains("换货")
                || proposalText.contains("更换")
                || proposalText.contains("调换")) {
            return "REPLACE_BY_CONFIRMED_SETTLEMENT";
        }
        return "SETTLEMENT_CONFIRMED";
    }

    // 所属模块：【Temporal 持久化编排 / 应用编排层】「FinalWorkflowActivitiesAdapter.shouldOpenHumanReviewGate(String)」。
    // 具体功能：「FinalWorkflowActivitiesAdapter.shouldOpenHumanReviewGate(String)」：计算是否需要Open人工审核门禁；处理的关键状态/协议值包括 「ACTIVITY_INTERRUPTED」、「VALIDATION_INTERRUPTED」，最终返回「boolean」。
    // 上游调用：「FinalWorkflowActivitiesAdapter.shouldOpenHumanReviewGate(String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.complete」。
    // 下游影响：「FinalWorkflowActivitiesAdapter.shouldOpenHumanReviewGate(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「FinalWorkflowActivitiesAdapter.shouldOpenHumanReviewGate(String)」把不可重放 I/O 隔离在 Activity 中，允许 Temporal 按策略重试，同时要求下游写入具备幂等性。
    private static boolean shouldOpenHumanReviewGate(String status) {
        return !"ACTIVITY_INTERRUPTED".equals(status)
                && !"VALIDATION_INTERRUPTED".equals(status);
    }
}

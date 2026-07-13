/*
 * 所属模块：裁决结果查询。
 * 文件职责：编排案件结果规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「get」、「confirmDraft」、「modifyDraft」；聚合人工终审、非最终草案、补救执行和案件时间线形成角色可见结果页。
 * 关键边界：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
 */
package com.example.dispute.outcome.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.PlatformReviewerAuthorization;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.review.application.ReviewDecisionCommand;
import com.example.dispute.review.application.ReviewDecisionView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【裁决结果查询 / 应用编排层】类型「CaseOutcomeService」。
// 类型职责：编排案件结果规则、权限校验与事实读写；本类型显式提供 「CaseOutcomeService」、「get」、「confirmDraft」、「modifyDraft」、「adjudicationDraft」、「approvedPlan」。
// 协作关系：主要由 「CaseOutcomeController.confirmDraft」、「CaseOutcomeController.get」、「CaseOutcomeController.modifyDraft」、「CaseOutcomeControllerTest.returnsTheHumanConfirmedDecisionAndExecutionReceipts」 使用。
// 边界意义：对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class CaseOutcomeService {

    private final FulfillmentCaseRepository caseRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final FlowConclusionRepository conclusionRepository;
    private final ToolExecutorService executorService;
    private final RemedyPlanRepository remedyPlanRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final ReviewApplicationService reviewApplicationService;
    private final ObjectMapper objectMapper;

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.CaseOutcomeService(FulfillmentCaseRepository,ApprovalRecordRepository,AdjudicationDraftRepository,FlowConclusionRepository,ToolExecutorService,RemedyPlanRepository,ReviewTaskRepository,ReviewApplicationService,ObjectMapper)」。
    // 具体功能：「CaseOutcomeService.CaseOutcomeService(FulfillmentCaseRepository,ApprovalRecordRepository,AdjudicationDraftRepository,FlowConclusionRepository,ToolExecutorService,RemedyPlanRepository,ReviewTaskRepository,ReviewApplicationService,ObjectMapper)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「approvalRepository」(ApprovalRecordRepository)、「draftRepository」(AdjudicationDraftRepository)、「conclusionRepository」(FlowConclusionRepository)、「executorService」(ToolExecutorService)、「remedyPlanRepository」(RemedyPlanRepository)、「reviewTaskRepository」(ReviewTaskRepository)、「reviewApplicationService」(ReviewApplicationService)、「objectMapper」(ObjectMapper) 并保存为「CaseOutcomeService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseOutcomeService.CaseOutcomeService(FulfillmentCaseRepository,ApprovalRecordRepository,AdjudicationDraftRepository,FlowConclusionRepository,ToolExecutorService,RemedyPlanRepository,ReviewTaskRepository,ReviewApplicationService,ObjectMapper)」的上游创建点包括 「CaseOutcomeServiceTest.setUp」。
    // 下游影响：「CaseOutcomeService.CaseOutcomeService(FulfillmentCaseRepository,ApprovalRecordRepository,AdjudicationDraftRepository,FlowConclusionRepository,ToolExecutorService,RemedyPlanRepository,ReviewTaskRepository,ReviewApplicationService,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseOutcomeService.CaseOutcomeService(FulfillmentCaseRepository,ApprovalRecordRepository,AdjudicationDraftRepository,FlowConclusionRepository,ToolExecutorService,RemedyPlanRepository,ReviewTaskRepository,ReviewApplicationService,ObjectMapper)」负责主链路中的“案件结果服务”；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseOutcomeService(
            FulfillmentCaseRepository caseRepository,
            ApprovalRecordRepository approvalRepository,
            AdjudicationDraftRepository draftRepository,
            FlowConclusionRepository conclusionRepository,
            ToolExecutorService executorService,
            RemedyPlanRepository remedyPlanRepository,
            ReviewTaskRepository reviewTaskRepository,
            ReviewApplicationService reviewApplicationService,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.approvalRepository = approvalRepository;
        this.draftRepository = draftRepository;
        this.conclusionRepository = conclusionRepository;
        this.executorService = executorService;
        this.remedyPlanRepository = remedyPlanRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.reviewApplicationService = reviewApplicationService;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.get(String,AuthenticatedActor)」。
    // 具体功能：「CaseOutcomeService.get(String,AuthenticatedActor)」：读取案件结果：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「approvalRepository.findAllByCaseIdOrderByCreatedAtAsc」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「conclusionRepository.findByCaseId」；处理的关键状态/协议值包括 「case_id」，最终返回「CaseOutcomeView」。
    // 上游调用：「CaseOutcomeService.get(String,AuthenticatedActor)」的上游调用点包括 「CaseOutcomeController.get」、「CaseOutcomeControllerTest.returnsTheHumanConfirmedDecisionAndExecutionReceipts」、「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft」、「CaseOutcomeServiceTest.exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill」。
    // 下游影响：「CaseOutcomeService.get(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「approvalRepository.findAllByCaseIdOrderByCreatedAtAsc」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「conclusionRepository.findByCaseId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseOutcomeService.get(String,AuthenticatedActor)」定义原子提交边界；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public CaseOutcomeView get(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        assertCanRead(dispute, actor);
        ApprovalRecordEntity approval =
                latest(approvalRepository.findAllByCaseIdOrderByCreatedAtAsc(caseId));
        AdjudicationDraftEntity draft =
                draftRepository
                        .findFirstByCaseIdOrderByDraftVersionDesc(caseId)
                        .orElse(null);
        FlowConclusionEntity flowConclusion =
                conclusionRepository.findByCaseId(caseId).orElse(null);
        RemedyPlanEntity remedyPlan =
                remedyPlanRepository
                        .findFirstByCaseIdOrderByPlanVersionDesc(caseId)
                        .orElse(null);
        var reviewTask =
                reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(caseId).orElse(null);

        return new CaseOutcomeView(
                caseId,
                dispute.getTitle(),
                dispute.getCaseStatus(),
                dispute.getClosedAt(),
                finalDecision(dispute, approval, draft, flowConclusion),
                adjudicationDraft(draft, remedyPlan),
                reviewTask == null ? null : reviewTask.getId(),
                reviewTask == null ? null : reviewTask.getTaskStatus().name(),
                executorService.actions(caseId, actor));
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.confirmDraft(String,String,String,AuthenticatedActor)」。
    // 具体功能：「CaseOutcomeService.confirmDraft(String,String,String,AuthenticatedActor)」：确认草案；实际协作者为 「reviewApplicationService.decide」、「PlatformReviewerAuthorization.requireDecisionAccess」、「latestReviewTaskId」，最终返回「ReviewDecisionView」。
    // 上游调用：「CaseOutcomeService.confirmDraft(String,String,String,AuthenticatedActor)」的上游调用点包括 「CaseOutcomeController.confirmDraft」、「CaseOutcomeControllerTest.reviewerConfirmsTheOutcomeDraftThroughCaseEndpoint」、「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToConfirm」、「CaseOutcomeServiceTest.reviewerConfirmsLatestDraftByCaseReviewTask」。
    // 下游影响：「CaseOutcomeService.confirmDraft(String,String,String,AuthenticatedActor)」向下依次触达 「reviewApplicationService.decide」、「PlatformReviewerAuthorization.requireDecisionAccess」、「latestReviewTaskId」；计算结果以「ReviewDecisionView」交给调用方。
    // 系统意义：「CaseOutcomeService.confirmDraft(String,String,String,AuthenticatedActor)」负责主链路中的“草案”；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    public ReviewDecisionView confirmDraft(
            String caseId,
            String reason,
            String idempotencyKey,
            AuthenticatedActor actor) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        String taskId = latestReviewTaskId(caseId);
        return reviewApplicationService.decide(
                taskId,
                new ReviewDecisionCommand(
                        ApprovalDecisionType.APPROVE,
                        reason,
                        null,
                        idempotencyKey),
                actor);
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.modifyDraft(String,String,JsonNode,String,AuthenticatedActor)」。
    // 具体功能：「CaseOutcomeService.modifyDraft(String,String,JsonNode,String,AuthenticatedActor)」：构建modify草案；实际协作者为 「reviewApplicationService.decide」、「PlatformReviewerAuthorization.requireDecisionAccess」、「latestReviewTaskId」，最终返回「ReviewDecisionView」。
    // 上游调用：「CaseOutcomeService.modifyDraft(String,String,JsonNode,String,AuthenticatedActor)」的上游调用点包括 「CaseOutcomeController.modifyDraft」、「CaseOutcomeControllerTest.reviewerModifiesTheOutcomeDraftThroughCaseEndpoint」、「CaseOutcomeServiceTest.rejectsAnotherPlatformReviewerBeforeLookingUpTheDraftToModify」、「CaseOutcomeServiceTest.reviewerModifiesLatestDraftByCaseReviewTask」。
    // 下游影响：「CaseOutcomeService.modifyDraft(String,String,JsonNode,String,AuthenticatedActor)」向下依次触达 「reviewApplicationService.decide」、「PlatformReviewerAuthorization.requireDecisionAccess」、「latestReviewTaskId」；计算结果以「ReviewDecisionView」交给调用方。
    // 系统意义：「CaseOutcomeService.modifyDraft(String,String,JsonNode,String,AuthenticatedActor)」负责主链路中的“modify草案”；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    public ReviewDecisionView modifyDraft(
            String caseId,
            String reason,
            JsonNode approvedPlan,
            String idempotencyKey,
            AuthenticatedActor actor) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        String taskId = latestReviewTaskId(caseId);
        return reviewApplicationService.decide(
                taskId,
                new ReviewDecisionCommand(
                        ApprovalDecisionType.MODIFY_AND_APPROVE,
                        reason,
                        approvedPlan,
                        idempotencyKey),
                actor);
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.adjudicationDraft(AdjudicationDraftEntity,RemedyPlanEntity)」。
    // 具体功能：「CaseOutcomeService.adjudicationDraft(AdjudicationDraftEntity,RemedyPlanEntity)」：构建adjudication草案；实际协作者为 「draft.getId」、「draft.getDraftVersion」、「draft.getRecommendedDecision」、「draft.getConfidence」，最终返回「AdjudicationDraftView」。
    // 上游调用：「CaseOutcomeService.adjudicationDraft(AdjudicationDraftEntity,RemedyPlanEntity)」的上游调用点包括 「CaseOutcomeService.get」。
    // 下游影响：「CaseOutcomeService.adjudicationDraft(AdjudicationDraftEntity,RemedyPlanEntity)」向下依次触达 「draft.getId」、「draft.getDraftVersion」、「draft.getRecommendedDecision」、「draft.getConfidence」；计算结果以「AdjudicationDraftView」交给调用方。
    // 系统意义：「CaseOutcomeService.adjudicationDraft(AdjudicationDraftEntity,RemedyPlanEntity)」负责主链路中的“adjudication草案”；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    private AdjudicationDraftView adjudicationDraft(
            AdjudicationDraftEntity draft, RemedyPlanEntity remedyPlan) {
        if (draft == null) {
            return null;
        }
        return new AdjudicationDraftView(
                draft.getId(),
                draft.getDraftVersion(),
                draft.getRecommendedDecision(),
                draft.getConfidence(),
                draft.getDraftText(),
                draft.getDraftStatus(),
                json(draft.getFactFindingsJson()),
                json(draft.getEvidenceAssessmentJson()),
                json(draft.getPolicyApplicationJson()),
                json(draft.getReviewerAttentionJson()),
                approvedPlan(remedyPlan));
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.approvedPlan(RemedyPlanEntity)」。
    // 具体功能：「CaseOutcomeService.approvedPlan(RemedyPlanEntity)」：批准已审批方案；实际协作者为 「objectMapper.valueToTree」、「plan.getId」、「plan.getPlanVersion」、「plan.getActionsJson」；处理的关键状态/协议值包括 「id」、「version」、「actions」、「preconditions」，最终返回「JsonNode」。
    // 上游调用：「CaseOutcomeService.approvedPlan(RemedyPlanEntity)」的上游调用点包括 「CaseOutcomeService.adjudicationDraft」。
    // 下游影响：「CaseOutcomeService.approvedPlan(RemedyPlanEntity)」向下依次触达 「objectMapper.valueToTree」、「plan.getId」、「plan.getPlanVersion」、「plan.getActionsJson」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「CaseOutcomeService.approvedPlan(RemedyPlanEntity)」负责主链路中的“已审批方案”；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    private JsonNode approvedPlan(RemedyPlanEntity plan) {
        if (plan == null) {
            return null;
        }
        return objectMapper.valueToTree(
                Map.of(
                        "id",
                        plan.getId(),
                        "version",
                        plan.getPlanVersion(),
                        "actions",
                        json(plan.getActionsJson()),
                        "preconditions",
                        json(plan.getPreconditionsJson()),
                        "notifications",
                        json(plan.getNotificationPlanJson())));
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.finalDecision(FulfillmentCaseEntity,ApprovalRecordEntity,AdjudicationDraftEntity,FlowConclusionEntity)」。
    // 具体功能：「CaseOutcomeService.finalDecision(FulfillmentCaseEntity,ApprovalRecordEntity,AdjudicationDraftEntity,FlowConclusionEntity)」：构建终态决定；实际协作者为 「draft.getRecommendedDecision」、「flowConclusion.getConclusionCode」、「approval.getDecisionType」、「draft.getDraftText」；处理的关键状态/协议值包括 「历史结案记录」、「平台终审驳回裁决草案」、「HUMAN_REVIEW」，最终返回「FinalDecisionView」。
    // 上游调用：「CaseOutcomeService.finalDecision(FulfillmentCaseEntity,ApprovalRecordEntity,AdjudicationDraftEntity,FlowConclusionEntity)」的上游调用点包括 「CaseOutcomeService.get」。
    // 下游影响：「CaseOutcomeService.finalDecision(FulfillmentCaseEntity,ApprovalRecordEntity,AdjudicationDraftEntity,FlowConclusionEntity)」向下依次触达 「draft.getRecommendedDecision」、「flowConclusion.getConclusionCode」、「approval.getDecisionType」、「draft.getDraftText」；计算结果以「FinalDecisionView」交给调用方。
    // 系统意义：「CaseOutcomeService.finalDecision(FulfillmentCaseEntity,ApprovalRecordEntity,AdjudicationDraftEntity,FlowConclusionEntity)」负责主链路中的“终态决定”；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    private FinalDecisionView finalDecision(
            FulfillmentCaseEntity dispute,
            ApprovalRecordEntity approval,
            AdjudicationDraftEntity draft,
            FlowConclusionEntity flowConclusion) {
        String conclusion =
                draft != null
                        ? draft.getRecommendedDecision()
                        : flowConclusion != null
                                ? flowConclusion.getConclusionCode()
                                : "历史结案记录";
        if (approval != null
                && approval.getDecisionType() == ApprovalDecisionType.REJECT) {
            conclusion = "平台终审驳回裁决草案";
        }
        String explanation =
                draft != null
                        ? draft.getDraftText()
                        : flowConclusion != null
                                ? flowConclusion.getSummary()
                                : dispute.getDescription();
        return new FinalDecisionView(
                conclusion,
                explanation,
                approval == null ? null : approval.getDecisionReason(),
                approval != null
                        ? "HUMAN_REVIEW"
                        : dispute.getSourceType().name(),
                approval != null,
                approval == null
                        ? null
                        : json(approval.getApprovedPlanJson()));
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.json(String)」。
    // 具体功能：「CaseOutcomeService.json(String)」：解析JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「CaseOutcomeService.json(String)」的上游调用点包括 「CaseOutcomeService.adjudicationDraft」、「CaseOutcomeService.approvedPlan」、「CaseOutcomeService.finalDecision」。
    // 下游影响：「CaseOutcomeService.json(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「CaseOutcomeService.json(String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "invalid approved plan JSON", exception);
        }
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.latest(List)」。
    // 具体功能：「CaseOutcomeService.latest(List)」：构建最新版本，最终返回「ApprovalRecordEntity」。
    // 上游调用：「CaseOutcomeService.latest(List)」的上游调用点包括 「CaseOutcomeService.get」。
    // 下游影响：「CaseOutcomeService.latest(List)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ApprovalRecordEntity」交给调用方。
    // 系统意义：「CaseOutcomeService.latest(List)」负责主链路中的“最新版本”；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    private static ApprovalRecordEntity latest(
            List<ApprovalRecordEntity> records) {
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「CaseOutcomeService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」：断言CanRead；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「CaseOutcomeService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「CaseOutcomeService.get」。
    // 下游影响：「CaseOutcomeService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「CaseOutcomeService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」在“CanRead”进入下游前阻断非法状态；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    private static void assertCanRead(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT ->
                            actor.actorId().equals(dispute.getMerchantId());
                    case CUSTOMER_SERVICE,
                            PLATFORM_REVIEWER,
                            ADMIN,
                            SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot view case outcome");
        }
    }

    // 所属模块：【裁决结果查询 / 应用编排层】「CaseOutcomeService.latestReviewTaskId(String)」。
    // 具体功能：「CaseOutcomeService.latestReviewTaskId(String)」：构建最新版本审核任务标识：先把 Optional 空值转换为明确业务异常；实际协作者为 「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」、「getId」；处理的关键状态/协议值包括 「case_id」，最终返回「String」。
    // 上游调用：「CaseOutcomeService.latestReviewTaskId(String)」的上游调用点包括 「CaseOutcomeService.confirmDraft」、「CaseOutcomeService.modifyDraft」。
    // 下游影响：「CaseOutcomeService.latestReviewTaskId(String)」向下依次触达 「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」、「getId」；计算结果以「String」交给调用方。
    // 系统意义：「CaseOutcomeService.latestReviewTaskId(String)」负责主链路中的“最新版本审核任务标识”；对外结果必须以人工决定和真实执行记录为准，不能展示内部审核材料
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private String latestReviewTaskId(String caseId) {
        return reviewTaskRepository
                .findFirstByCaseIdOrderByCreatedAtDesc(caseId)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        ErrorCode.CASE_NOT_FOUND,
                                        "review task not found",
                                        Map.of("case_id", caseId)))
                .getId();
    }
}

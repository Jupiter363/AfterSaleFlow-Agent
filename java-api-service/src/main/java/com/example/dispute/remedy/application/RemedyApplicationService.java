/*
 * 所属模块：确定性补救规划。
 * 文件职责：编排补救应用规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「generateForWorkflow」、「get」；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.HearingStatus;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.remedy.domain.PlannedRemedyAction;
import com.example.dispute.remedy.domain.RemedyPlanDraft;
import com.example.dispute.remedy.domain.RemedyPlanner;
import com.example.dispute.remedy.domain.RemedyPlanningSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【确定性补救规划 / 应用编排层】类型「RemedyApplicationService」。
// 类型职责：编排补救应用规则、权限校验与事实读写；本类型显式提供 「RemedyApplicationService」、「generateForWorkflow」、「get」、「generate」、「resolveSourceRoute」、「sourceFor」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.planRemedy」、「HearingOutcomeOrchestrationService.orchestrate」、「RemedyController.get」、「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources」 使用。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class RemedyApplicationService {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("temporal-worker", ActorRole.SYSTEM);

    private final FulfillmentCaseRepository caseRepository;
    private final FlowConclusionRepository conclusionRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingStateRepository hearingRepository;
    private final RemedyPlanRepository planRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final RemedyPlanner planner = new RemedyPlanner();

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.RemedyApplicationService(FulfillmentCaseRepository,FlowConclusionRepository,AdjudicationDraftRepository,HearingStateRepository,RemedyPlanRepository,AuditRecorder,ObjectMapper)」。
    // 具体功能：「RemedyApplicationService.RemedyApplicationService(FulfillmentCaseRepository,FlowConclusionRepository,AdjudicationDraftRepository,HearingStateRepository,RemedyPlanRepository,AuditRecorder,ObjectMapper)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「conclusionRepository」(FlowConclusionRepository)、「draftRepository」(AdjudicationDraftRepository)、「hearingRepository」(HearingStateRepository)、「planRepository」(RemedyPlanRepository)、「auditRecorder」(AuditRecorder)、「objectMapper」(ObjectMapper) 并保存为「RemedyApplicationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RemedyApplicationService.RemedyApplicationService(FulfillmentCaseRepository,FlowConclusionRepository,AdjudicationDraftRepository,HearingStateRepository,RemedyPlanRepository,AuditRecorder,ObjectMapper)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「RemedyApplicationService.RemedyApplicationService(FulfillmentCaseRepository,FlowConclusionRepository,AdjudicationDraftRepository,HearingStateRepository,RemedyPlanRepository,AuditRecorder,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RemedyApplicationService.RemedyApplicationService(FulfillmentCaseRepository,FlowConclusionRepository,AdjudicationDraftRepository,HearingStateRepository,RemedyPlanRepository,AuditRecorder,ObjectMapper)」负责主链路中的“补救应用服务”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RemedyApplicationService(
            FulfillmentCaseRepository caseRepository,
            FlowConclusionRepository conclusionRepository,
            AdjudicationDraftRepository draftRepository,
            HearingStateRepository hearingRepository,
            RemedyPlanRepository planRepository,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.conclusionRepository = conclusionRepository;
        this.draftRepository = draftRepository;
        this.hearingRepository = hearingRepository;
        this.planRepository = planRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.generateForWorkflow(String,String)」。
    // 具体功能：「RemedyApplicationService.generateForWorkflow(String,String)」：生成面向工作流：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「disputeCase.getCurrentWorkflowId」、「caseNotFound」、「resolveSourceRoute」；不满足前置条件时抛出 「BusinessException」；处理的关键状态/协议值包括 「case_id」，最终返回「String」。
    // 上游调用：「RemedyApplicationService.generateForWorkflow(String,String)」的上游调用点包括 「HearingOutcomeOrchestrationService.orchestrate」、「CaseFulfillmentDisputeActivitiesImpl.planRemedy」、「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources」、「RemedyApplicationServiceIntegrationTest.legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft」。
    // 下游影响：「RemedyApplicationService.generateForWorkflow(String,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「disputeCase.getCurrentWorkflowId」、「caseNotFound」、「resolveSourceRoute」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「RemedyApplicationService.generateForWorkflow(String,String)」定义原子提交边界；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public String generateForWorkflow(String caseId, String workflowId) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        RouteType sourceRoute = resolveSourceRoute(disputeCase, workflowId);
        if (sourceRoute == RouteType.FULL_HEARING
                && !workflowId.equals(disputeCase.getCurrentWorkflowId())) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "workflow does not own this hearing case",
                    Map.of("case_id", caseId));
        }
        return generate(disputeCase, sourceRoute, SYSTEM).getId();
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.get(String,AuthenticatedActor)」。
    // 具体功能：「RemedyApplicationService.get(String,AuthenticatedActor)」：读取补救方案：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「planRepository.findFirstByCaseIdOrderByPlanVersionDesc」、「caseNotFound」、「assertCanRead」；处理的关键状态/协议值包括 「case_id」，最终返回「RemedyPlanView」。
    // 上游调用：「RemedyApplicationService.get(String,AuthenticatedActor)」的上游调用点包括 「RemedyController.get」、「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources」、「RemedyControllerTest.returnsApprovalGatedPlanDto」。
    // 下游影响：「RemedyApplicationService.get(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「planRepository.findFirstByCaseIdOrderByPlanVersionDesc」、「caseNotFound」、「assertCanRead」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「RemedyApplicationService.get(String,AuthenticatedActor)」定义原子提交边界；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public RemedyPlanView get(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase =
                caseRepository.findById(caseId).orElseThrow(() -> caseNotFound(caseId));
        assertCanRead(disputeCase, actor);
        RemedyPlanEntity plan =
                planRepository
                        .findFirstByCaseIdOrderByPlanVersionDesc(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "remedy plan not found",
                                                Map.of("case_id", caseId)));
        return toView(plan);
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.generate(FulfillmentCaseEntity,RouteType,AuthenticatedActor)」。
    // 具体功能：「RemedyApplicationService.generate(FulfillmentCaseEntity,RouteType,AuthenticatedActor)」：生成补救方案：先把新状态写入 PostgreSQL 事实表；实际协作者为 「planRepository.findFirstByCaseIdOrderByPlanVersionDesc」、「planRepository.save」、「caseRepository.save」、「RemedyPlanEntity.pendingApproval」；处理的关键状态/协议值包括 「REMEDY_」、「REMEDY_PLAN_CREATED」、「REMEDY_PLAN」、「case_status」，最终返回「RemedyPlanEntity」。
    // 上游调用：「RemedyApplicationService.generate(FulfillmentCaseEntity,RouteType,AuthenticatedActor)」的上游调用点包括 「RemedyApplicationService.generateForWorkflow」。
    // 下游影响：「RemedyApplicationService.generate(FulfillmentCaseEntity,RouteType,AuthenticatedActor)」向下依次触达 「planRepository.findFirstByCaseIdOrderByPlanVersionDesc」、「planRepository.save」、「caseRepository.save」、「RemedyPlanEntity.pendingApproval」；计算结果以「RemedyPlanEntity」交给调用方。
    // 系统意义：「RemedyApplicationService.generate(FulfillmentCaseEntity,RouteType,AuthenticatedActor)」负责主链路中的“补救方案”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private RemedyPlanEntity generate(
            FulfillmentCaseEntity disputeCase,
            RouteType sourceRoute,
            AuthenticatedActor actor) {
        var existing =
                planRepository.findFirstByCaseIdOrderByPlanVersionDesc(
                        disputeCase.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        int version = 1;
        SourceData source = sourceFor(disputeCase, sourceRoute);
        RemedyPlanDraft planned =
                planner.plan(
                        new RemedyPlanningSource(
                                disputeCase.getId(),
                                sourceRoute,
                                disputeCase.getRiskLevel(),
                                source.conclusionCode(),
                                source.actions(),
                                source.draftId(),
                                source.draftRecommendation(),
                                version));
        RemedyPlanEntity plan =
                planRepository.save(
                        RemedyPlanEntity.pendingApproval(
                                "REMEDY_" + compactUuid(),
                                disputeCase.getId(),
                                planned.sourceDraftId(),
                                version,
                                sourceRoute,
                                planned.riskLevel(),
                                writeJson(planned.actions()),
                                writeJson(planned.preconditions()),
                                writeJson(planned.notificationPlan()),
                                actor.actorId()));
        disputeCase.markRemedyPlanned(actor.actorId());
        caseRepository.save(disputeCase);
        auditRecorder.record(
                actor,
                "REMEDY_PLAN_CREATED",
                "REMEDY_PLAN",
                plan.getId(),
                disputeCase.getId(),
                Map.of("case_status", source.previousCaseStatus()),
                Map.of(
                        "case_status", "REMEDY_PLANNED",
                        "source_route", sourceRoute.name(),
                        "risk_level", planned.riskLevel().name(),
                        "requires_human_review", true,
                        "action_count", planned.actions().size()));
        return plan;
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.resolveSourceRoute(FulfillmentCaseEntity,String)」。
    // 具体功能：「RemedyApplicationService.resolveSourceRoute(FulfillmentCaseEntity,String)」：解析来源路由：先把新状态写入 PostgreSQL 事实表；实际协作者为 「hearingRepository.findByCaseId」、「caseRepository.save」、「SYSTEM.actorId」、「disputeCase.getRouteType」；不满足前置条件时抛出 「BusinessException」；处理的关键状态/协议值包括 「hearing-window-」、「case_id」，最终返回「RouteType」。
    // 上游调用：「RemedyApplicationService.resolveSourceRoute(FulfillmentCaseEntity,String)」的上游调用点包括 「RemedyApplicationService.generateForWorkflow」。
    // 下游影响：「RemedyApplicationService.resolveSourceRoute(FulfillmentCaseEntity,String)」向下依次触达 「hearingRepository.findByCaseId」、「caseRepository.save」、「SYSTEM.actorId」、「disputeCase.getRouteType」；计算结果以「RouteType」交给调用方。
    // 系统意义：「RemedyApplicationService.resolveSourceRoute(FulfillmentCaseEntity,String)」负责主链路中的“来源路由”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private RouteType resolveSourceRoute(
            FulfillmentCaseEntity disputeCase, String workflowId) {
        RouteType routeType = disputeCase.getRouteType();
        if (routeType != null) {
            return routeType;
        }
        boolean hearingWorkflowOwnsCase =
                workflowId != null
                        && workflowId.equals(disputeCase.getCurrentWorkflowId())
                        && workflowId.startsWith("hearing-window-");
        boolean hasHearingState =
                hearingRepository.findByCaseId(disputeCase.getId()).isPresent();
        if (hearingWorkflowOwnsCase || hasHearingState) {
            disputeCase.ensureFullHearingRoute(SYSTEM.actorId());
            caseRepository.save(disputeCase);
            return RouteType.FULL_HEARING;
        }
        throw new BusinessException(
                ErrorCode.CASE_STATUS_INVALID,
                "case route is required before remedy planning",
                Map.of("case_id", disputeCase.getId()));
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.sourceFor(FulfillmentCaseEntity,RouteType)」。
    // 具体功能：「RemedyApplicationService.sourceFor(FulfillmentCaseEntity,RouteType)」：构建来源面向：先把 Optional 空值转换为明确业务异常；实际协作者为 「hearingRepository.findByCaseId」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「conclusionRepository.findByCaseId」、「disputeCase.getId」；不满足前置条件时抛出 「BusinessException」；处理的关键状态/协议值包括 「case_id」、「hearing_status」、「ADJUDICATION_DRAFT」，最终返回「SourceData」。
    // 上游调用：「RemedyApplicationService.sourceFor(FulfillmentCaseEntity,RouteType)」的上游调用点包括 「RemedyApplicationService.generate」。
    // 下游影响：「RemedyApplicationService.sourceFor(FulfillmentCaseEntity,RouteType)」向下依次触达 「hearingRepository.findByCaseId」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「conclusionRepository.findByCaseId」、「disputeCase.getId」；计算结果以「SourceData」交给调用方。
    // 系统意义：「RemedyApplicationService.sourceFor(FulfillmentCaseEntity,RouteType)」负责主链路中的“来源面向”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private SourceData sourceFor(
            FulfillmentCaseEntity disputeCase, RouteType sourceRoute) {
        if (sourceRoute == RouteType.FULL_HEARING) {
            var hearing =
                    hearingRepository
                            .findByCaseId(disputeCase.getId())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.CASE_STATUS_INVALID,
                                                    "hearing state is required",
                                                    Map.of(
                                                            "case_id",
                                                            disputeCase.getId())));
            if (hearing.getHearingStatus() != HearingStatus.COMPLETED) {
                throw new BusinessException(
                        ErrorCode.CASE_STATUS_INVALID,
                        "hearing must complete before remedy planning",
                        Map.of(
                                "hearing_status",
                                hearing.getHearingStatus().name()));
            }
            AdjudicationDraftEntity draft =
                    draftRepository
                            .findFirstByCaseIdOrderByDraftVersionDesc(
                                    disputeCase.getId())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.CASE_STATUS_INVALID,
                                                    "adjudication draft is required",
                                                    Map.of(
                                                            "case_id",
                                                            disputeCase.getId())));
            return new SourceData(
                    "ADJUDICATION_DRAFT",
                    List.of(),
                    draft.getId(),
                    draft.getRecommendedDecision(),
                    disputeCase.getCaseStatus().name());
        }
        FlowConclusionEntity conclusion =
                conclusionRepository
                        .findByCaseId(disputeCase.getId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.CASE_STATUS_INVALID,
                                                "flow conclusion is required",
                                                Map.of(
                                                        "case_id",
                                                        disputeCase.getId())));
        if (!conclusion.isRequiresRemedyPlanning()) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "flow conclusion is not ready for remedy planning",
                    Map.of("case_id", disputeCase.getId()));
        }
        return new SourceData(
                conclusion.getConclusionCode(),
                readActions(conclusion.getRecommendedActionsJson()),
                null,
                null,
                disputeCase.getCaseStatus().name());
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.toView(RemedyPlanEntity)」。
    // 具体功能：「RemedyApplicationService.toView(RemedyPlanEntity)」：转换视图；实际协作者为 「plan.getId」、「plan.getCaseId」、「plan.getAdjudicationDraftId」、「plan.getPlanVersion」；不满足前置条件时抛出 「IllegalStateException」，最终返回「RemedyPlanView」。
    // 上游调用：「RemedyApplicationService.toView(RemedyPlanEntity)」的上游调用点包括 「RemedyApplicationService.get」。
    // 下游影响：「RemedyApplicationService.toView(RemedyPlanEntity)」向下依次触达 「plan.getId」、「plan.getCaseId」、「plan.getAdjudicationDraftId」、「plan.getPlanVersion」；计算结果以「RemedyPlanView」交给调用方。
    // 系统意义：「RemedyApplicationService.toView(RemedyPlanEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private RemedyPlanView toView(RemedyPlanEntity plan) {
        try {
            return new RemedyPlanView(
                    plan.getId(),
                    plan.getCaseId(),
                    plan.getAdjudicationDraftId(),
                    plan.getPlanVersion(),
                    plan.getSourceRoute(),
                    plan.getPlanStatus(),
                    plan.getRiskLevel(),
                    plan.getTotalAmount(),
                    plan.getCurrency(),
                    objectMapper.readValue(
                            plan.getActionsJson(),
                            new TypeReference<List<PlannedRemedyAction>>() {}),
                    objectMapper.readValue(
                            plan.getPreconditionsJson(),
                            new TypeReference<List<String>>() {}),
                    objectMapper.readValue(
                            plan.getNotificationPlanJson(),
                            new TypeReference<List<String>>() {}),
                    plan.isRequiresHumanReview(),
                    plan.getCreatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted remedy plan JSON", exception);
        }
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.readActions(String)」。
    // 具体功能：「RemedyApplicationService.readActions(String)」：读取动作列表；实际协作者为 「objectMapper.readValue」；不满足前置条件时抛出 「IllegalStateException」，最终返回「List<String>」。
    // 上游调用：「RemedyApplicationService.readActions(String)」的上游调用点包括 「RemedyApplicationService.sourceFor」。
    // 下游影响：「RemedyApplicationService.readActions(String)」向下依次触达 「objectMapper.readValue」；计算结果以「List<String>」交给调用方。
    // 系统意义：「RemedyApplicationService.readActions(String)」统一“动作列表”的跨层表示，避免不同入口产生不兼容字段；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private List<String> readActions(String json) {
        try {
            return objectMapper.readValue(
                    json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "invalid persisted recommended actions", exception);
        }
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.writeJson(Object)」。
    // 具体功能：「RemedyApplicationService.writeJson(Object)」：写入JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「RemedyApplicationService.writeJson(Object)」的上游调用点包括 「RemedyApplicationService.generate」。
    // 下游影响：「RemedyApplicationService.writeJson(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「RemedyApplicationService.writeJson(Object)」负责主链路中的“JSON”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize remedy plan", exception);
        }
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「RemedyApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」：断言CanRead；实际协作者为 「actor.role」、「actor.actorId」、「disputeCase.getUserId」、「disputeCase.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「RemedyApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「RemedyApplicationService.get」。
    // 下游影响：「RemedyApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「disputeCase.getUserId」、「disputeCase.getMerchantId」。
    // 系统意义：「RemedyApplicationService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」在“CanRead”进入下游前阻断非法状态；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private static void assertCanRead(
            FulfillmentCaseEntity disputeCase, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(disputeCase.getUserId());
                    case MERCHANT -> actor.actorId().equals(disputeCase.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot read this remedy plan");
        }
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.caseNotFound(String)」。
    // 具体功能：「RemedyApplicationService.caseNotFound(String)」：构建案件不Found；处理的关键状态/协议值包括 「case_id」，最终返回「NotFoundException」。
    // 上游调用：「RemedyApplicationService.caseNotFound(String)」的上游调用点包括 「RemedyApplicationService.generateForWorkflow」、「RemedyApplicationService.get」。
    // 下游影响：「RemedyApplicationService.caseNotFound(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「NotFoundException」交给调用方。
    // 系统意义：「RemedyApplicationService.caseNotFound(String)」负责主链路中的“案件不Found”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private static NotFoundException caseNotFound(String caseId) {
        return new NotFoundException(
                ErrorCode.CASE_NOT_FOUND,
                "case not found",
                Map.of("case_id", caseId));
    }

    // 所属模块：【确定性补救规划 / 应用编排层】「RemedyApplicationService.compactUuid()」。
    // 具体功能：「RemedyApplicationService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「RemedyApplicationService.compactUuid()」的上游调用点包括 「RemedyApplicationService.generate」。
    // 下游影响：「RemedyApplicationService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「RemedyApplicationService.compactUuid()」负责主链路中的“UUID”；规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【确定性补救规划 / 应用编排层】类型「SourceData」。
    // 类型职责：定义来源Data跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record SourceData(
            String conclusionCode,
            List<String> actions,
            String draftId,
            String draftRecommendation,
            String previousCaseStatus) {}
}

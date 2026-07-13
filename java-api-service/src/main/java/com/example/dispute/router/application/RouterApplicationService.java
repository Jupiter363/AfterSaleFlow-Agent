/*
 * 所属模块：争议路由应用层。
 * 文件职责：编排Router应用规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「route」；汇集案件、证据和规则上下文并选择常规、规则或听证路线。
 * 关键边界：路由只决定下一条处理路径，不拥有终审或工具执行权限
 */
package com.example.dispute.router.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.example.dispute.infrastructure.persistence.entity.RouteDecisionEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.infrastructure.persistence.repository.RouteDecisionRepository;
import com.example.dispute.router.domain.DisputeRouter;
import com.example.dispute.router.domain.RoutingContext;
import com.example.dispute.router.domain.RoutingOutcome;
import com.example.dispute.regularflow.application.RegularFlowService;
import com.example.dispute.ruleflow.application.RuleFlowService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【争议路由应用层 / 应用编排层】类型「RouterApplicationService」。
// 类型职责：编排Router应用规则、权限校验与事实读写；本类型显式提供 「RouterApplicationService」、「route」、「createConclusion」、「authorizedCase」、「toView」、「toView」。
// 协作关系：主要由 「RouterController.route」、「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion」、「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview」、「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion」 使用。
// 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class RouterApplicationService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceDossierRepository dossierRepository;
    private final PolicyRuleRepository policyRepository;
    private final RouteDecisionRepository decisionRepository;
    private final FlowConclusionRepository conclusionRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RegularFlowService regularFlowService;
    private final RuleFlowService ruleFlowService;
    private final DisputeRouter router = new DisputeRouter();

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.RouterApplicationService(FulfillmentCaseRepository,EvidenceDossierRepository,PolicyRuleRepository,RouteDecisionRepository,FlowConclusionRepository,AuditRecorder,ObjectMapper,Clock,RegularFlowService,RuleFlowService)」。
    // 具体功能：「RouterApplicationService.RouterApplicationService(FulfillmentCaseRepository,EvidenceDossierRepository,PolicyRuleRepository,RouteDecisionRepository,FlowConclusionRepository,AuditRecorder,ObjectMapper,Clock,RegularFlowService,RuleFlowService)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「dossierRepository」(EvidenceDossierRepository)、「policyRepository」(PolicyRuleRepository)、「decisionRepository」(RouteDecisionRepository)、「conclusionRepository」(FlowConclusionRepository)、「auditRecorder」(AuditRecorder)、「objectMapper」(ObjectMapper)、「clock」(Clock)、「regularFlowService」(RegularFlowService)、「ruleFlowService」(RuleFlowService) 并保存为「RouterApplicationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RouterApplicationService.RouterApplicationService(FulfillmentCaseRepository,EvidenceDossierRepository,PolicyRuleRepository,RouteDecisionRepository,FlowConclusionRepository,AuditRecorder,ObjectMapper,Clock,RegularFlowService,RuleFlowService)」的上游创建点包括 「RouterApplicationServiceTest.setUp」。
    // 下游影响：「RouterApplicationService.RouterApplicationService(FulfillmentCaseRepository,EvidenceDossierRepository,PolicyRuleRepository,RouteDecisionRepository,FlowConclusionRepository,AuditRecorder,ObjectMapper,Clock,RegularFlowService,RuleFlowService)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RouterApplicationService.RouterApplicationService(FulfillmentCaseRepository,EvidenceDossierRepository,PolicyRuleRepository,RouteDecisionRepository,FlowConclusionRepository,AuditRecorder,ObjectMapper,Clock,RegularFlowService,RuleFlowService)」负责主链路中的“Router应用服务”；路由只决定下一条处理路径，不拥有终审或工具执行权限
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RouterApplicationService(
            FulfillmentCaseRepository caseRepository,
            EvidenceDossierRepository dossierRepository,
            PolicyRuleRepository policyRepository,
            RouteDecisionRepository decisionRepository,
            FlowConclusionRepository conclusionRepository,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            Clock clock,
            RegularFlowService regularFlowService,
            RuleFlowService ruleFlowService) {
        this.caseRepository = caseRepository;
        this.dossierRepository = dossierRepository;
        this.policyRepository = policyRepository;
        this.decisionRepository = decisionRepository;
        this.conclusionRepository = conclusionRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.regularFlowService = regularFlowService;
        this.ruleFlowService = ruleFlowService;
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.route(String,AuthenticatedActor,String)」。
    // 具体功能：「RouterApplicationService.route(String,AuthenticatedActor,String)」：路由路由决定：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「decisionRepository.findByCaseId」、「conclusionRepository.findByCaseId」、「dossierRepository.findByCaseId」、「policyRepository.findActive」；不满足前置条件时抛出 「IllegalArgumentException」、「IdempotencyConflictException」、「BusinessException」；处理的关键状态/协议值包括 「case_id」、「case_status」、「evidence_count」、「pending_parse_count」，最终返回「RouteDecisionView」。
    // 上游调用：「RouterApplicationService.route(String,AuthenticatedActor,String)」的上游调用点包括 「RouterController.route」、「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview」、「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion」、「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion」。
    // 下游影响：「RouterApplicationService.route(String,AuthenticatedActor,String)」向下依次触达 「decisionRepository.findByCaseId」、「conclusionRepository.findByCaseId」、「dossierRepository.findByCaseId」、「policyRepository.findActive」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「RouterApplicationService.route(String,AuthenticatedActor,String)」定义原子提交边界；路由只决定下一条处理路径，不拥有终审或工具执行权限
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public RouteDecisionView route(
            String caseId, AuthenticatedActor actor, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        FulfillmentCaseEntity disputeCase = authorizedCase(caseId, actor);
        var existing = decisionRepository.findByCaseId(caseId);
        if (existing.isPresent()) {
            if (!existing.get().getIdempotencyKey().equals(idempotencyKey)) {
                throw new IdempotencyConflictException(
                        "case already has a route decision");
            }
            return toView(
                    existing.get(),
                    conclusionRepository.findByCaseId(caseId).orElse(null));
        }

        EvidenceDossierEntity dossier =
                dossierRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.EVIDENCE_NOT_FOUND,
                                                "evidence dossier not found",
                                                Map.of("case_id", caseId)));
        if (disputeCase.getCaseStatus()
                != com.example.dispute.domain.model.CaseStatus.DOSSIER_BUILT) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "case must have a built dossier before routing",
                    Map.of("case_status", disputeCase.getCaseStatus().name()));
        }

        JsonNode dossierSummary = readTree(dossier.getSummaryJson());
        int evidenceCount = dossierSummary.path("evidence_count").asInt(0);
        int pendingParseCount =
                dossierSummary.path("pending_parse_count").asInt(evidenceCount);
        boolean evidenceSufficient = evidenceCount > 0 && pendingParseCount == 0;
        List<PolicyRuleEntity> policies =
                policyRepository.findActive(
                        disputeCase.getCaseType(), OffsetDateTime.now(clock));
        PolicyRuleEntity matchedPolicy = policies.isEmpty() ? null : policies.get(0);
        RoutingOutcome outcome =
                router.decide(
                        new RoutingContext(
                                disputeCase.getCaseType(),
                                disputeCase.getDisputeType(),
                                disputeCase.getRiskLevel(),
                                evidenceSufficient,
                                disputeCase.getDisputeType() != null
                                        && !disputeCase.getDisputeType().isBlank(),
                                matchedPolicy != null));
        PolicyRuleEntity appliedPolicy =
                outcome.routeType() == RouteType.SIMPLE_HEARING
                        ? matchedPolicy
                        : null;
        RouteDecisionEntity decision =
                RouteDecisionEntity.record(
                        "ROUTE_" + compactUuid(),
                        caseId,
                        idempotencyKey,
                        outcome.routeType(),
                        outcome.reasonCode(),
                        reasonDetail(outcome.reasonCode()),
                        outcome.requiresAdditionalEvidence(),
                        dossier.getDossierVersion(),
                        appliedPolicy == null ? null : appliedPolicy.getId(),
                        writeJson(
                                Map.of(
                                        "case_type", disputeCase.getCaseType(),
                                        "risk_level", disputeCase.getRiskLevel().name(),
                                        "evidence_count", evidenceCount,
                                        "pending_parse_count", pendingParseCount,
                                        "policy_matched", matchedPolicy != null)),
                        actor.actorId());
        RouteDecisionEntity savedDecision = decisionRepository.save(decision);
        disputeCase.applyRoute(outcome.routeType(), actor.actorId());
        caseRepository.save(disputeCase);
        FlowConclusionEntity conclusion =
                createConclusion(disputeCase, savedDecision, appliedPolicy, actor);
        auditRecorder.record(
                actor,
                "ROUTE_DECIDED",
                "ROUTE_DECISION",
                savedDecision.getId(),
                caseId,
                Map.of("case_status", "DOSSIER_BUILT"),
                Map.of(
                        "case_status", "ROUTED",
                        "route_type", outcome.routeType().name(),
                        "reason_code", outcome.reasonCode()));
        return toView(savedDecision, conclusion);
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.createConclusion(FulfillmentCaseEntity,RouteDecisionEntity,PolicyRuleEntity,AuthenticatedActor)」。
    // 具体功能：「RouterApplicationService.createConclusion(FulfillmentCaseEntity,RouteDecisionEntity,PolicyRuleEntity,AuthenticatedActor)」：创建Conclusion：先把新状态写入 PostgreSQL 事实表；实际协作者为 「regularFlowService.conclude」、「ruleFlowService.conclude」、「policy.getId」、「policy.getRuleVersion」；处理的关键状态/协议值包括 「CONCLUSION_」、「REGULAR_FLOW」、「RULE_FLOW」，最终返回「FlowConclusionEntity」。
    // 上游调用：「RouterApplicationService.createConclusion(FulfillmentCaseEntity,RouteDecisionEntity,PolicyRuleEntity,AuthenticatedActor)」的上游调用点包括 「RouterApplicationService.route」。
    // 下游影响：「RouterApplicationService.createConclusion(FulfillmentCaseEntity,RouteDecisionEntity,PolicyRuleEntity,AuthenticatedActor)」向下依次触达 「regularFlowService.conclude」、「ruleFlowService.conclude」、「policy.getId」、「policy.getRuleVersion」；计算结果以「FlowConclusionEntity」交给调用方。
    // 系统意义：「RouterApplicationService.createConclusion(FulfillmentCaseEntity,RouteDecisionEntity,PolicyRuleEntity,AuthenticatedActor)」负责主链路中的“Conclusion”；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private FlowConclusionEntity createConclusion(
            FulfillmentCaseEntity disputeCase,
            RouteDecisionEntity decision,
            PolicyRuleEntity policy,
            AuthenticatedActor actor) {
        if (decision.getRouteType() == RouteType.FULL_HEARING) {
            return null;
        }
        ConclusionData data;
        if (decision.getRouteType() == RouteType.TRANSFERRED) {
            var regular = regularFlowService.conclude(disputeCase.getCaseType());
            data =
                    new ConclusionData(
                            regular.conclusionCode(),
                            regular.summary(),
                            regular.recommendedActions());
        } else {
            var rule = ruleFlowService.conclude(policy);
            data =
                    new ConclusionData(
                            rule.conclusionCode(),
                            rule.summary(),
                            rule.recommendedActions());
        }
        FlowConclusionEntity conclusion =
                FlowConclusionEntity.readyForRemedyPlanning(
                        "CONCLUSION_" + compactUuid(),
                        disputeCase.getId(),
                        decision.getId(),
                        decision.getRouteType() == RouteType.TRANSFERRED
                                ? "REGULAR_FLOW"
                                : "RULE_FLOW",
                        data.code(),
                        data.summary(),
                        writeJson(data.actions()),
                        policy == null ? null : policy.getId(),
                        policy == null ? null : policy.getRuleVersion(),
                        disputeCase.getRiskLevel(),
                        actor.actorId());
        return conclusionRepository.save(conclusion);
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.authorizedCase(String,AuthenticatedActor)」。
    // 具体功能：「RouterApplicationService.authorizedCase(String,AuthenticatedActor)」：授权authorized案件：先把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「actor.role」、「actor.actorId」、「entity.getUserId」；不满足前置条件时抛出 「ForbiddenException」；处理的关键状态/协议值包括 「case_id」，最终返回「FulfillmentCaseEntity」。
    // 上游调用：「RouterApplicationService.authorizedCase(String,AuthenticatedActor)」的上游调用点包括 「RouterApplicationService.route」。
    // 下游影响：「RouterApplicationService.authorizedCase(String,AuthenticatedActor)」向下依次触达 「caseRepository.findByIdForUpdate」、「actor.role」、「actor.actorId」、「entity.getUserId」；计算结果以「FulfillmentCaseEntity」交给调用方。
    // 系统意义：「RouterApplicationService.authorizedCase(String,AuthenticatedActor)」负责主链路中的“authorized案件”；路由只决定下一条处理路径，不拥有终审或工具执行权限
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
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(entity.getUserId());
                    case MERCHANT -> actor.actorId().equals(entity.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot route this case");
        }
        return entity;
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.toView(RouteDecisionEntity,FlowConclusionEntity)」。
    // 具体功能：「RouterApplicationService.toView(RouteDecisionEntity,FlowConclusionEntity)」：提供「toView」的便捷重载：接收 「decision」(RouteDecisionEntity)、「conclusion」(FlowConclusionEntity)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「RouterApplicationService.toView(RouteDecisionEntity,FlowConclusionEntity)」的上游调用点包括 「RouterApplicationService.route」、「RouterApplicationService.toView」。
    // 下游影响：「RouterApplicationService.toView(RouteDecisionEntity,FlowConclusionEntity)」向下依次触达 「decision.getId」、「decision.getCaseId」、「decision.getRouteType」、「decision.getReasonCode」；计算结果以「RouteDecisionView」交给调用方。
    // 系统意义：「RouterApplicationService.toView(RouteDecisionEntity,FlowConclusionEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private RouteDecisionView toView(
            RouteDecisionEntity decision, FlowConclusionEntity conclusion) {
        return new RouteDecisionView(
                decision.getId(),
                decision.getCaseId(),
                decision.getRouteType(),
                decision.getReasonCode(),
                decision.getReasonDetail(),
                decision.isRequiresAdditionalEvidence(),
                decision.getDossierVersion(),
                decision.getPolicyRuleId(),
                conclusion == null ? null : toView(conclusion),
                decision.getCreatedAt());
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.toView(FlowConclusionEntity)」。
    // 具体功能：「RouterApplicationService.toView(FlowConclusionEntity)」：转换视图；实际协作者为 「conclusion.getConclusionType」、「conclusion.getConclusionStatus」、「conclusion.getConclusionCode」、「conclusion.getSummary」，最终返回「FlowConclusionView」。
    // 上游调用：「RouterApplicationService.toView(FlowConclusionEntity)」的上游调用点包括 「RouterApplicationService.route」、「RouterApplicationService.toView」。
    // 下游影响：「RouterApplicationService.toView(FlowConclusionEntity)」向下依次触达 「conclusion.getConclusionType」、「conclusion.getConclusionStatus」、「conclusion.getConclusionCode」、「conclusion.getSummary」；计算结果以「FlowConclusionView」交给调用方。
    // 系统意义：「RouterApplicationService.toView(FlowConclusionEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private FlowConclusionView toView(FlowConclusionEntity conclusion) {
        return new FlowConclusionView(
                conclusion.getConclusionType(),
                conclusion.getConclusionStatus(),
                conclusion.getConclusionCode(),
                conclusion.getSummary(),
                readStringList(conclusion.getRecommendedActionsJson()),
                conclusion.getPolicyRuleId(),
                conclusion.getPolicyVersion(),
                conclusion.getRiskLevel(),
                conclusion.isRequiresRemedyPlanning(),
                conclusion.isRequiresHumanReview());
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.readTree(String)」。
    // 具体功能：「RouterApplicationService.readTree(String)」：读取Tree：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「RouterApplicationService.readTree(String)」的上游调用点包括 「RouterApplicationService.route」。
    // 下游影响：「RouterApplicationService.readTree(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「RouterApplicationService.readTree(String)」统一“Tree”的跨层表示，避免不同入口产生不兼容字段；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted routing JSON", exception);
        }
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.readStringList(String)」。
    // 具体功能：「RouterApplicationService.readStringList(String)」：读取字符串列表；实际协作者为 「objectMapper.readValue」、「objectMapper.getTypeFactory」、「objectMapper.getTypeFactory().constructCollectionType」；不满足前置条件时抛出 「IllegalStateException」，最终返回「List<String>」。
    // 上游调用：「RouterApplicationService.readStringList(String)」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「RouterApplicationService.readStringList(String)」向下依次触达 「objectMapper.readValue」、「objectMapper.getTypeFactory」、「objectMapper.getTypeFactory().constructCollectionType」；计算结果以「List<String>」交给调用方。
    // 系统意义：「RouterApplicationService.readStringList(String)」统一“字符串列表”的跨层表示，避免不同入口产生不兼容字段；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper
                            .getTypeFactory()
                            .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted action list", exception);
        }
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.writeJson(Object)」。
    // 具体功能：「RouterApplicationService.writeJson(Object)」：写入JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「RouterApplicationService.writeJson(Object)」的上游调用点包括 「RouterApplicationService.route」、「RouterApplicationService.createConclusion」。
    // 下游影响：「RouterApplicationService.writeJson(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「RouterApplicationService.writeJson(Object)」负责主链路中的“JSON”；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize routing data", exception);
        }
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.reasonDetail(String)」。
    // 具体功能：「RouterApplicationService.reasonDetail(String)」：构建原因详情；处理的关键状态/协议值包括 「ORDINARY_FULFILLMENT_REQUEST」、「POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT」、「HIGH_RISK_REQUIRES_HEARING」、「PARTY_STATEMENTS_CONFLICT」，最终返回「String」。
    // 上游调用：「RouterApplicationService.reasonDetail(String)」的上游调用点包括 「RouterApplicationService.route」。
    // 下游影响：「RouterApplicationService.reasonDetail(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RouterApplicationService.reasonDetail(String)」负责主链路中的“原因详情”；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private static String reasonDetail(String reasonCode) {
        return switch (reasonCode) {
            case "ORDINARY_FULFILLMENT_REQUEST" ->
                    "No material dispute was detected; use the regular fulfillment flow.";
            case "POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT" ->
                    "Evidence is sufficient and an effective versioned policy matched.";
            case "HIGH_RISK_REQUIRES_HEARING" ->
                    "High-risk cases require dispute hearing and cannot use rule flow.";
            case "PARTY_STATEMENTS_CONFLICT" ->
                    "Conflicting party statements require dispute hearing.";
            case "KEY_EVIDENCE_INSUFFICIENT" ->
                    "Key evidence is insufficient; dispute hearing may request evidence.";
            default -> "The case requires dispute hearing for fact and policy review.";
        };
    }

    // 所属模块：【争议路由应用层 / 应用编排层】「RouterApplicationService.compactUuid()」。
    // 具体功能：「RouterApplicationService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「RouterApplicationService.compactUuid()」的上游调用点包括 「RouterApplicationService.route」、「RouterApplicationService.createConclusion」。
    // 下游影响：「RouterApplicationService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「RouterApplicationService.compactUuid()」负责主链路中的“UUID”；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【争议路由应用层 / 应用编排层】类型「ConclusionData」。
    // 类型职责：定义ConclusionData跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record ConclusionData(String code, String summary, List<String> actions) {}
}

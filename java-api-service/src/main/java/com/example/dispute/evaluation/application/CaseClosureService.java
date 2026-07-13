/*
 * 所属模块：结案与离线评估。
 * 文件职责：编排执行完成后的案件关闭与离线评估规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「close」、「evaluation」、「metrics」；关闭已执行案件并调用评估 Agent 生成质量指标和离线报告。
 * 关键边界：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
 */
package com.example.dispute.evaluation.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.CaseClosureException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.EvaluationTraceEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.EvaluationTraceRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// 所属模块：【结案与离线评估 / 应用编排层】类型「CaseClosureService」。
// 类型职责：编排执行完成后的案件关闭与离线评估规则、权限校验与事实读写；本类型显式提供 「CaseClosureService」、「close」、「evaluation」、「metrics」、「prepareClosure」、「latestApproval」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.closeCaseAndEvaluate」、「ClosureController.close」、「ClosureController.evaluation」、「ClosureController.metrics」 使用。
// 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class CaseClosureService {

    private static final List<ApprovalDecisionType> EXECUTABLE_DECISIONS =
            List.of(
                    ApprovalDecisionType.APPROVE,
                    ApprovalDecisionType.MODIFY_AND_APPROVE);

    private final FulfillmentCaseRepository caseRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final ActionRecordRepository actionRepository;
    private final EvaluationTraceRepository evaluationRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvaluationAgentClient evaluationAgent;
    private final AuditRecorder auditRecorder;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.CaseClosureService(FulfillmentCaseRepository,ApprovalRecordRepository,ActionRecordRepository,EvaluationTraceRepository,AdjudicationDraftRepository,EvidenceItemRepository,EvaluationAgentClient,AuditRecorder,CaseLifecycleNotificationService,ObjectMapper,TransactionTemplate)」。
    // 具体功能：「CaseClosureService.CaseClosureService(FulfillmentCaseRepository,ApprovalRecordRepository,ActionRecordRepository,EvaluationTraceRepository,AdjudicationDraftRepository,EvidenceItemRepository,EvaluationAgentClient,AuditRecorder,CaseLifecycleNotificationService,ObjectMapper,TransactionTemplate)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「approvalRepository」(ApprovalRecordRepository)、「actionRepository」(ActionRecordRepository)、「evaluationRepository」(EvaluationTraceRepository)、「draftRepository」(AdjudicationDraftRepository)、「evidenceRepository」(EvidenceItemRepository)、「evaluationAgent」(EvaluationAgentClient)、「auditRecorder」(AuditRecorder)、「lifecycleNotifications」(CaseLifecycleNotificationService)、「objectMapper」(ObjectMapper)、「transactions」(TransactionTemplate) 并保存为「CaseClosureService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseClosureService.CaseClosureService(FulfillmentCaseRepository,ApprovalRecordRepository,ActionRecordRepository,EvaluationTraceRepository,AdjudicationDraftRepository,EvidenceItemRepository,EvaluationAgentClient,AuditRecorder,CaseLifecycleNotificationService,ObjectMapper,TransactionTemplate)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「CaseClosureService.CaseClosureService(FulfillmentCaseRepository,ApprovalRecordRepository,ActionRecordRepository,EvaluationTraceRepository,AdjudicationDraftRepository,EvidenceItemRepository,EvaluationAgentClient,AuditRecorder,CaseLifecycleNotificationService,ObjectMapper,TransactionTemplate)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseClosureService.CaseClosureService(FulfillmentCaseRepository,ApprovalRecordRepository,ActionRecordRepository,EvaluationTraceRepository,AdjudicationDraftRepository,EvidenceItemRepository,EvaluationAgentClient,AuditRecorder,CaseLifecycleNotificationService,ObjectMapper,TransactionTemplate)」负责主链路中的“案件结案服务”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseClosureService(
            FulfillmentCaseRepository caseRepository,
            ApprovalRecordRepository approvalRepository,
            ActionRecordRepository actionRepository,
            EvaluationTraceRepository evaluationRepository,
            AdjudicationDraftRepository draftRepository,
            EvidenceItemRepository evidenceRepository,
            EvaluationAgentClient evaluationAgent,
            AuditRecorder auditRecorder,
            CaseLifecycleNotificationService lifecycleNotifications,
            ObjectMapper objectMapper,
            TransactionTemplate transactions) {
        this.caseRepository = caseRepository;
        this.approvalRepository = approvalRepository;
        this.actionRepository = actionRepository;
        this.evaluationRepository = evaluationRepository;
        this.draftRepository = draftRepository;
        this.evidenceRepository = evidenceRepository;
        this.evaluationAgent = evaluationAgent;
        this.auditRecorder = auditRecorder;
        this.lifecycleNotifications = lifecycleNotifications;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.close(String,String,AuthenticatedActor,String,String)」。
    // 具体功能：「CaseClosureService.close(String,String,AuthenticatedActor,String,String)」：关闭结案：先在显式事务模板中原子写入业务事实，再在事务模板中读取并更新同一版本的案件状态，再由 Spring 事务代理统一提交数据库变化；实际协作者为 「transactions.execute」、「pending.invokeEvaluation」、「evaluationAgent.analyze」、「pending.snapshot」；处理的关键状态/协议值包括 「idempotencyKey」，最终返回「ClosureView」。
    // 上游调用：「CaseClosureService.close(String,String,AuthenticatedActor,String,String)」的上游调用点包括 「ClosureController.close」、「CaseFulfillmentDisputeActivitiesImpl.closeCaseAndEvaluate」、「CaseClosureServiceIntegrationTest.closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation」、「CaseClosureServiceIntegrationTest.rejectsClosureUntilEveryApprovedActionSucceeded」。
    // 下游影响：「CaseClosureService.close(String,String,AuthenticatedActor,String,String)」向下依次触达 「transactions.execute」、「pending.invokeEvaluation」、「evaluationAgent.analyze」、「pending.snapshot」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseClosureService.close(String,String,AuthenticatedActor,String,String)」定义原子提交边界；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ClosureView close(
            String caseId,
            String idempotencyKey,
            AuthenticatedActor actor,
            String traceId,
            String requestId) {
        assertCanClose(actor);
        requireText(idempotencyKey, "idempotencyKey");
        PendingClosure pending =
                transactions.execute(
                        ignored ->
                                prepareClosure(
                                        caseId,
                                        idempotencyKey,
                                        actor));
        if (pending.invokeEvaluation()) {
            try {
                EvaluationAgentResult result =
                        evaluationAgent.analyze(
                                pending.snapshot(), traceId, requestId);
                transactions.executeWithoutResult(
                        ignored ->
                                completeEvaluation(
                                        pending, result, actor));
            } catch (RuntimeException exception) {
                transactions.executeWithoutResult(
                        ignored ->
                                failEvaluation(
                                        pending, exception, actor));
                throw exception;
            }
        }
        return transactions.execute(
                ignored -> closureView(caseId));
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.evaluation(String,AuthenticatedActor)」。
    // 具体功能：「CaseClosureService.evaluation(String,AuthenticatedActor)」：构建评估：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「evaluationRepository.findFirstByCaseIdOrderByEvaluationVersionDesc」、「disputeCase.getCaseStatus」、「assertCanReadEvaluation」；处理的关键状态/协议值包括 「case_status」、「case_id」，最终返回「EvaluationReportView」。
    // 上游调用：「CaseClosureService.evaluation(String,AuthenticatedActor)」的上游调用点包括 「ClosureController.evaluation」、「CaseClosureServiceIntegrationTest.evaluationCanOnlyBeReadByAdministratorOrSystem」、「ClosureControllerTest.administratorCanCloseAndQueryEvaluationAndMetrics」。
    // 下游影响：「CaseClosureService.evaluation(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「evaluationRepository.findFirstByCaseIdOrderByEvaluationVersionDesc」、「disputeCase.getCaseStatus」、「assertCanReadEvaluation」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseClosureService.evaluation(String,AuthenticatedActor)」定义原子提交边界；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public EvaluationReportView evaluation(
            String caseId, AuthenticatedActor actor) {
        assertCanReadEvaluation(actor);
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        if (disputeCase.getCaseStatus() != CaseStatus.CLOSED) {
            throw closureDenied(
                    "evaluation is only available for a closed case",
                    Map.of("case_status", disputeCase.getCaseStatus().name()));
        }
        return evaluationRepository
                .findFirstByCaseIdOrderByEvaluationVersionDesc(caseId)
                .map(this::reportView)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        ErrorCode.CASE_NOT_FOUND,
                                        "evaluation trace not found",
                                        Map.of("case_id", caseId)));
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.metrics(AuthenticatedActor)」。
    // 具体功能：「CaseClosureService.metrics(AuthenticatedActor)」：构建评估指标：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「evaluationRepository.findAllByOrderByCreatedAtDesc」、「trace.getEvaluationStatus」、「assertCanReadEvaluation」、「average」；处理的关键状态/协议值包括 「COMPLETED」、「draft_approval_rate」、「reviewer_modification_rate」，最终返回「EvaluationMetricsView」。
    // 上游调用：「CaseClosureService.metrics(AuthenticatedActor)」的上游调用点包括 「ClosureController.metrics」、「CaseClosureServiceIntegrationTest.closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation」、「ClosureControllerTest.administratorCanCloseAndQueryEvaluationAndMetrics」。
    // 下游影响：「CaseClosureService.metrics(AuthenticatedActor)」向下依次触达 「evaluationRepository.findAllByOrderByCreatedAtDesc」、「trace.getEvaluationStatus」、「assertCanReadEvaluation」、「average」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseClosureService.metrics(AuthenticatedActor)」定义原子提交边界；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public EvaluationMetricsView metrics(AuthenticatedActor actor) {
        assertCanReadEvaluation(actor);
        List<EvaluationTraceEntity> all =
                evaluationRepository.findAllByOrderByCreatedAtDesc();
        List<EvaluationTraceEntity> completed =
                all.stream()
                        .filter(
                                trace ->
                                        "COMPLETED"
                                                .equals(
                                                        trace
                                                                .getEvaluationStatus()))
                        .toList();
        return new EvaluationMetricsView(
                all.size(),
                completed.size(),
                average(completed, "draft_approval_rate"),
                average(completed, "reviewer_modification_rate"));
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.prepareClosure(String,String,AuthenticatedActor)」。
    // 具体功能：「CaseClosureService.prepareClosure(String,String,AuthenticatedActor)」：准备结案：先把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「evaluationRepository.findFirstByCaseIdOrderByEvaluationVersionDesc」、「actionRepository.findAllByCaseIdOrderByCreatedAtAsc」、「evaluationRepository.save」；处理的关键状态/协议值包括 「case_id」、「COMPLETED」、「PENDING」、「case_status」，最终返回「PendingClosure」。
    // 上游调用：「CaseClosureService.prepareClosure(String,String,AuthenticatedActor)」的上游调用点包括 「CaseClosureService.close」。
    // 下游影响：「CaseClosureService.prepareClosure(String,String,AuthenticatedActor)」向下依次触达 「caseRepository.findByIdForUpdate」、「evaluationRepository.findFirstByCaseIdOrderByEvaluationVersionDesc」、「actionRepository.findAllByCaseIdOrderByCreatedAtAsc」、「evaluationRepository.save」；计算结果以「PendingClosure」交给调用方。
    // 系统意义：「CaseClosureService.prepareClosure(String,String,AuthenticatedActor)」负责主链路中的“结案”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private PendingClosure prepareClosure(
            String caseId,
            String idempotencyKey,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        if (disputeCase.getCaseStatus() == CaseStatus.CLOSED) {
            EvaluationTraceEntity existing =
                    evaluationRepository
                            .findFirstByCaseIdOrderByEvaluationVersionDesc(
                                    caseId)
                            .orElseThrow(
                                    () ->
                                            closureDenied(
                                                    "closed case has no evaluation trace",
                                                    Map.of(
                                                            "case_id",
                                                            caseId)));
            if ("COMPLETED".equals(existing.getEvaluationStatus())
                    || "PENDING".equals(existing.getEvaluationStatus())) {
                return new PendingClosure(
                        caseId,
                        existing.getId(),
                        read(existing.getInputSnapshotJson()),
                        false);
            }
            JsonNode snapshot =
                    buildSnapshot(
                            disputeCase,
                            latestApproval(caseId),
                            actionRepository
                                    .findAllByCaseIdOrderByCreatedAtAsc(
                                            caseId));
            existing.retry(snapshot.toString(), actor.actorId());
            evaluationRepository.save(existing);
            return new PendingClosure(
                    caseId, existing.getId(), snapshot, true);
        }
        if (disputeCase.getCaseStatus() != CaseStatus.EXECUTING) {
            throw closureDenied(
                    "case is not ready for closure",
                    Map.of("case_status", disputeCase.getCaseStatus().name()));
        }
        ApprovalRecordEntity approval = latestApproval(caseId);
        List<ActionRecordEntity> actions =
                actionRepository.findAllByCaseIdOrderByCreatedAtAsc(caseId);
        validateCompletedExecution(approval, actions);
        disputeCase.close(actor.actorId());
        caseRepository.save(disputeCase);
        JsonNode snapshot = buildSnapshot(disputeCase, approval, actions);
        EvaluationTraceEntity trace =
                evaluationRepository.save(
                        EvaluationTraceEntity.pending(
                                "EVAL_" + compactUuid(),
                                caseId,
                                1,
                                snapshot.toString(),
                                actor.actorId()));
        auditRecorder.record(
                actor,
                "CASE_CLOSED",
                "FULFILLMENT_CASE",
                caseId,
                caseId,
                Map.of("case_status", CaseStatus.EXECUTING.name()),
                Map.of(
                        "case_status", CaseStatus.CLOSED.name(),
                        "closed_at", disputeCase.getClosedAt().toString(),
                        "idempotency_key", idempotencyKey));
        auditRecorder.record(
                actor,
                "EVALUATION_STARTED",
                "EVALUATION_TRACE",
                trace.getId(),
                caseId,
                Map.of(),
                Map.of(
                        "evaluation_status", "PENDING",
                        "evaluation_version", 1));
        lifecycleNotifications.executionCompleted(disputeCase);
        return new PendingClosure(caseId, trace.getId(), snapshot, true);
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.latestApproval(String)」。
    // 具体功能：「CaseClosureService.latestApproval(String)」：构建最新版本审批：先把 Optional 空值转换为明确业务异常；实际协作者为 「findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc」、「closureDenied」；处理的关键状态/协议值包括 「case_id」，最终返回「ApprovalRecordEntity」。
    // 上游调用：「CaseClosureService.latestApproval(String)」的上游调用点包括 「CaseClosureService.prepareClosure」。
    // 下游影响：「CaseClosureService.latestApproval(String)」向下依次触达 「findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc」、「closureDenied」；计算结果以「ApprovalRecordEntity」交给调用方。
    // 系统意义：「CaseClosureService.latestApproval(String)」负责主链路中的“最新版本审批”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private ApprovalRecordEntity latestApproval(String caseId) {
        return approvalRepository
                .findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(
                        caseId, EXECUTABLE_DECISIONS)
                .orElseThrow(
                        () ->
                                closureDenied(
                                        "approved execution record is required",
                                        Map.of("case_id", caseId)));
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.validateCompletedExecution(ApprovalRecordEntity,List)」。
    // 具体功能：「CaseClosureService.validateCompletedExecution(ApprovalRecordEntity,List)」：校验完成执行；实际协作者为 「approval.getId」、「action.getExecutionStatus」、「action.getApprovalRecordId」、「approval.getPlanId」；处理的关键状态/协议值包括 「approval_record_id」、「expected_actions」、「actual_actions」，最终返回「void」。
    // 上游调用：「CaseClosureService.validateCompletedExecution(ApprovalRecordEntity,List)」的上游调用点包括 「CaseClosureService.prepareClosure」。
    // 下游影响：「CaseClosureService.validateCompletedExecution(ApprovalRecordEntity,List)」向下依次触达 「approval.getId」、「action.getExecutionStatus」、「action.getApprovalRecordId」、「approval.getPlanId」。
    // 系统意义：「CaseClosureService.validateCompletedExecution(ApprovalRecordEntity,List)」在“完成执行”进入下游前阻断非法状态；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private void validateCompletedExecution(
            ApprovalRecordEntity approval,
            List<ActionRecordEntity> actions) {
        if (actions.isEmpty()) {
            throw closureDenied(
                    "at least one succeeded action is required",
                    Map.of("approval_record_id", approval.getId()));
        }
        if (actions.stream()
                .anyMatch(
                        action ->
                                action.getExecutionStatus()
                                        != ExecutionStatus.SUCCEEDED)) {
            throw closureDenied(
                    "all approved actions must have succeeded before closure",
                    Map.of("approval_record_id", approval.getId()));
        }
        if (actions.stream()
                .anyMatch(
                        action ->
                                !approval
                                                .getId()
                                                .equals(
                                                        action
                                                                .getApprovalRecordId())
                                        || !approval
                                                .getPlanId()
                                                .equals(
                                                        action.getPlanId()))) {
            throw closureDenied(
                    "action records do not belong to the approved plan",
                    Map.of("approval_record_id", approval.getId()));
        }
        Map<String, Integer> expected =
                expectedActionTypes(read(approval.getApprovedPlanJson()));
        Map<String, Integer> actual = new LinkedHashMap<>();
        actions.forEach(
                action ->
                        actual.merge(action.getActionType(), 1, Integer::sum));
        if (!expected.equals(actual)) {
            throw closureDenied(
                    "every approved action must have one succeeded record",
                    Map.of(
                            "expected_actions", expected,
                            "actual_actions", actual));
        }
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.expectedActionTypes(JsonNode)」。
    // 具体功能：「CaseClosureService.expectedActionTypes(JsonNode)」：构建expected动作Types；实际协作者为 「actionNodes.isArray」、「notificationNodes.isArray」、「expected.merge」、「node.asText」；处理的关键状态/协议值包括 「actions」、「notifications」、「action_type」，最终返回「Map<String, Integer>」。
    // 上游调用：「CaseClosureService.expectedActionTypes(JsonNode)」的上游调用点包括 「CaseClosureService.validateCompletedExecution」。
    // 下游影响：「CaseClosureService.expectedActionTypes(JsonNode)」向下依次触达 「actionNodes.isArray」、「notificationNodes.isArray」、「expected.merge」、「node.asText」；计算结果以「Map<String, Integer>」交给调用方。
    // 系统意义：「CaseClosureService.expectedActionTypes(JsonNode)」负责主链路中的“expected动作Types”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private Map<String, Integer> expectedActionTypes(JsonNode approvedPlan) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        JsonNode actionNodes = approvedPlan.path("actions");
        JsonNode notificationNodes = approvedPlan.path("notifications");
        if (!actionNodes.isArray() || !notificationNodes.isArray()) {
            throw closureDenied(
                    "approved plan snapshot is invalid", Map.of());
        }
        actionNodes.forEach(
                node ->
                        expected.merge(
                                requiredJsonText(node, "action_type"),
                                1,
                                Integer::sum));
        notificationNodes.forEach(
                node -> expected.merge(node.asText(), 1, Integer::sum));
        return expected;
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.buildSnapshot(FulfillmentCaseEntity,ApprovalRecordEntity,List)」。
    // 具体功能：「CaseClosureService.buildSnapshot(FulfillmentCaseEntity,ApprovalRecordEntity,List)」：组装快照；实际协作者为 「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「draftPolicy.isArray」、「objectMapper.createObjectNode」；处理的关键状态/协议值包括 「case_id」、「case_status」、「route_type」、「UNROUTED」，最终返回「JsonNode」。
    // 上游调用：「CaseClosureService.buildSnapshot(FulfillmentCaseEntity,ApprovalRecordEntity,List)」的上游调用点包括 「CaseClosureService.prepareClosure」。
    // 下游影响：「CaseClosureService.buildSnapshot(FulfillmentCaseEntity,ApprovalRecordEntity,List)」向下依次触达 「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「draftPolicy.isArray」、「objectMapper.createObjectNode」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「CaseClosureService.buildSnapshot(FulfillmentCaseEntity,ApprovalRecordEntity,List)」负责主链路中的“快照”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private JsonNode buildSnapshot(
            FulfillmentCaseEntity disputeCase,
            ApprovalRecordEntity approval,
            List<ActionRecordEntity> actions) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("case_id", disputeCase.getId());
        snapshot.put("case_status", CaseStatus.CLOSED.name());
        snapshot.put(
                "route_type",
                disputeCase.getRouteType() == null
                        ? "UNROUTED"
                        : disputeCase.getRouteType().name());
        snapshot.put("risk_level", disputeCase.getRiskLevel().name());
        snapshot.put(
                "approval_decision", approval.getDecisionType().name());
        snapshot.set(
                "approved_plan", read(approval.getApprovedPlanJson()));
        snapshot.set(
                "adjudication_draft",
                draftRepository
                        .findFirstByCaseIdOrderByDraftVersionDesc(
                                disputeCase.getId())
                        .map(this::draftSnapshot)
                        .orElseGet(objectMapper::createObjectNode));
        ArrayNode actionNodes = snapshot.putArray("action_records");
        actions.forEach(
                action -> {
                    ObjectNode node = actionNodes.addObject();
                    node.put("action_record_id", action.getId());
                    node.put("action_type", action.getActionType());
                    node.put(
                            "execution_status",
                            action.getExecutionStatus().name());
                    node.put("attempt_count", action.getAttemptCount());
                    node.put("review_packet_id", action.getReviewPacketId());
                    node.put(
                            "action_snapshot_hash",
                            action.getActionSnapshotHash());
                    node.set(
                            "evidence_refs",
                            read(action.getEvidenceRefsJson()));
                    node.set("rule_refs", read(action.getRuleRefsJson()));
                    node.set(
                            "agent_run_refs",
                            read(action.getAgentRunRefsJson()));
                    node.put(
                            "external_result_ref",
                            action.getExternalResultRef());
                    node.set("result", read(action.getResultJson()));
                });
        var evidence =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                disputeCase.getId());
        ObjectNode evidenceSummary =
                snapshot.putObject("evidence_summary");
        evidenceSummary.put("evidence_count", evidence.size());
        evidenceSummary.put(
                "parsed_evidence_count",
                evidence.stream()
                        .filter(
                                item ->
                                        item.getParseStatus()
                                                == com.example.dispute
                                                        .domain.model
                                                        .ParseStatus.SUCCEEDED)
                        .count());
        ObjectNode policySummary = snapshot.putObject("policy_summary");
        JsonNode draftPolicy =
                snapshot.path("adjudication_draft")
                        .path("policy_application");
        policySummary.put(
                "applied_rule_count",
                draftPolicy.isArray() ? draftPolicy.size() : 0);
        return snapshot;
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.draftSnapshot(AdjudicationDraftEntity)」。
    // 具体功能：「CaseClosureService.draftSnapshot(AdjudicationDraftEntity)」：构建草案快照；实际协作者为 「objectMapper.createObjectNode」、「draft.getId」、「draft.getDraftVersion」、「draft.getDraftStatus」；处理的关键状态/协议值包括 「draft_id」、「draft_version」、「draft_status」、「recommended_decision」，最终返回「ObjectNode」。
    // 上游调用：「CaseClosureService.draftSnapshot(AdjudicationDraftEntity)」只由「CaseClosureService」内部流程使用，负责封装“草案快照”这一步校验、映射或状态转换。
    // 下游影响：「CaseClosureService.draftSnapshot(AdjudicationDraftEntity)」向下依次触达 「objectMapper.createObjectNode」、「draft.getId」、「draft.getDraftVersion」、「draft.getDraftStatus」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「CaseClosureService.draftSnapshot(AdjudicationDraftEntity)」负责主链路中的“草案快照”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private ObjectNode draftSnapshot(AdjudicationDraftEntity draft) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("draft_id", draft.getId());
        node.put("draft_version", draft.getDraftVersion());
        node.put("draft_status", draft.getDraftStatus());
        node.put(
                "recommended_decision", draft.getRecommendedDecision());
        node.put("confidence", draft.getConfidence());
        node.set("fact_findings", read(draft.getFactFindingsJson()));
        node.set(
                "evidence_assessment",
                read(draft.getEvidenceAssessmentJson()));
        node.set(
                "policy_application",
                read(draft.getPolicyApplicationJson()));
        return node;
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.completeEvaluation(PendingClosure,EvaluationAgentResult,AuthenticatedActor)」。
    // 具体功能：「CaseClosureService.completeEvaluation(PendingClosure,EvaluationAgentResult,AuthenticatedActor)」：完成评估：先把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「evaluationRepository.findByIdForUpdate」、「evaluationRepository.save」、「result.report」、「pending.caseId」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「case_id」、「COMPLETED」、「evaluation_status」、「metric_scores」，最终返回「void」。
    // 上游调用：「CaseClosureService.completeEvaluation(PendingClosure,EvaluationAgentResult,AuthenticatedActor)」的上游调用点包括 「CaseClosureService.close」。
    // 下游影响：「CaseClosureService.completeEvaluation(PendingClosure,EvaluationAgentResult,AuthenticatedActor)」向下依次触达 「evaluationRepository.findByIdForUpdate」、「evaluationRepository.save」、「result.report」、「pending.caseId」。
    // 系统意义：「CaseClosureService.completeEvaluation(PendingClosure,EvaluationAgentResult,AuthenticatedActor)」负责主链路中的“评估”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void completeEvaluation(
            PendingClosure pending,
            EvaluationAgentResult result,
            AuthenticatedActor actor) {
        JsonNode report = result.report();
        if (report == null
                || !pending.caseId()
                        .equals(report.path("case_id").asText())
                || !"COMPLETED"
                        .equals(
                                report.path("evaluation_status").asText())
                || !report.path("metric_scores").isObject()
                || !report.path("findings").isArray()
                || report.path("automatic_changes_applied").asBoolean(true)
                || report.path("online_case_mutated").asBoolean(true)) {
            throw new IllegalStateException(
                    "evaluation agent returned an invalid or unsafe report");
        }
        EvaluationTraceEntity trace =
                evaluationRepository
                        .findByIdForUpdate(pending.evaluationTraceId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "evaluation trace disappeared"));
        trace.complete(
                result.evaluatorModel(),
                result.promptVersion(),
                report.path("metric_scores").toString(),
                report.path("findings").toString(),
                report.toString(),
                result.latencyMs(),
                result.tokenUsage(),
                actor.actorId());
        evaluationRepository.save(trace);
        auditRecorder.record(
                actor,
                "EVALUATION_COMPLETED",
                "EVALUATION_TRACE",
                trace.getId(),
                pending.caseId(),
                Map.of("evaluation_status", "PENDING"),
                Map.of(
                        "evaluation_status", "COMPLETED",
                        "evaluator_model", result.evaluatorModel()));
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.failEvaluation(PendingClosure,RuntimeException,AuthenticatedActor)」。
    // 具体功能：「CaseClosureService.failEvaluation(PendingClosure,RuntimeException,AuthenticatedActor)」：标记失败评估：先把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「evaluationRepository.findByIdForUpdate」、「evaluationRepository.save」、「pending.evaluationTraceId」、「trace.fail」；处理的关键状态/协议值包括 「status」、「FAILED」、「error_type」、「EVALUATION_FAILED」，最终返回「void」。
    // 上游调用：「CaseClosureService.failEvaluation(PendingClosure,RuntimeException,AuthenticatedActor)」的上游调用点包括 「CaseClosureService.close」。
    // 下游影响：「CaseClosureService.failEvaluation(PendingClosure,RuntimeException,AuthenticatedActor)」向下依次触达 「evaluationRepository.findByIdForUpdate」、「evaluationRepository.save」、「pending.evaluationTraceId」、「trace.fail」。
    // 系统意义：「CaseClosureService.failEvaluation(PendingClosure,RuntimeException,AuthenticatedActor)」负责主链路中的“评估”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void failEvaluation(
            PendingClosure pending,
            RuntimeException exception,
            AuthenticatedActor actor) {
        EvaluationTraceEntity trace =
                evaluationRepository
                        .findByIdForUpdate(pending.evaluationTraceId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "evaluation trace disappeared"));
        trace.fail(
                write(
                        Map.of(
                                "status",
                                "FAILED",
                                "error_type",
                                exception.getClass().getSimpleName())),
                actor.actorId());
        evaluationRepository.save(trace);
        auditRecorder.record(
                actor,
                "EVALUATION_FAILED",
                "EVALUATION_TRACE",
                trace.getId(),
                pending.caseId(),
                Map.of("evaluation_status", "PENDING"),
                Map.of("evaluation_status", "FAILED"));
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.closureView(String)」。
    // 具体功能：「CaseClosureService.closureView(String)」：构建结案视图：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「evaluationRepository.findFirstByCaseIdOrderByEvaluationVersionDesc」、「disputeCase.getCaseStatus」、「disputeCase.getClosedAt」，最终返回「ClosureView」。
    // 上游调用：「CaseClosureService.closureView(String)」的上游调用点包括 「CaseClosureService.close」。
    // 下游影响：「CaseClosureService.closureView(String)」向下依次触达 「caseRepository.findById」、「evaluationRepository.findFirstByCaseIdOrderByEvaluationVersionDesc」、「disputeCase.getCaseStatus」、「disputeCase.getClosedAt」；计算结果以「ClosureView」交给调用方。
    // 系统意义：「CaseClosureService.closureView(String)」负责主链路中的“结案视图”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private ClosureView closureView(String caseId) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        EvaluationTraceEntity trace =
                evaluationRepository
                        .findFirstByCaseIdOrderByEvaluationVersionDesc(
                                caseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "evaluation trace not found"));
        return new ClosureView(
                caseId,
                disputeCase.getCaseStatus(),
                disputeCase.getClosedAt(),
                trace.getId(),
                trace.getEvaluationStatus());
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.reportView(EvaluationTraceEntity)」。
    // 具体功能：「CaseClosureService.reportView(EvaluationTraceEntity)」：构建report视图；实际协作者为 「trace.getId」、「trace.getCaseId」、「trace.getEvaluationVersion」、「trace.getEvaluationStatus」，最终返回「EvaluationReportView」。
    // 上游调用：「CaseClosureService.reportView(EvaluationTraceEntity)」只由「CaseClosureService」内部流程使用，负责封装“report视图”这一步校验、映射或状态转换。
    // 下游影响：「CaseClosureService.reportView(EvaluationTraceEntity)」向下依次触达 「trace.getId」、「trace.getCaseId」、「trace.getEvaluationVersion」、「trace.getEvaluationStatus」；计算结果以「EvaluationReportView」交给调用方。
    // 系统意义：「CaseClosureService.reportView(EvaluationTraceEntity)」负责主链路中的“report视图”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private EvaluationReportView reportView(EvaluationTraceEntity trace) {
        return new EvaluationReportView(
                trace.getId(),
                trace.getCaseId(),
                trace.getEvaluationVersion(),
                trace.getEvaluationStatus(),
                trace.getEvaluatorModel(),
                trace.getPromptVersion(),
                read(trace.getMetricScoresJson()),
                read(trace.getFindingsJson()),
                read(trace.getReportJson()),
                trace.getLatencyMs(),
                trace.getTokenUsage(),
                trace.getCompletedAt(),
                trace.getCreatedAt());
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.average(List,String)」。
    // 具体功能：「CaseClosureService.average(List,String)」：从每条已完成评估的 metric_scores JSON 读取指定指标，忽略缺失值并计算平均分；没有有效样本时返回 0，最终返回「double」。
    // 上游调用：「CaseClosureService.average(List,String)」的上游调用点包括 「CaseClosureService.metrics」。
    // 下游影响：「CaseClosureService.average(List,String)」向下依次触达 「trace.getMetricScoresJson」、「read」、「average」、「traces.stream().mapToDouble」；计算结果以「double」交给调用方。
    // 系统意义：「CaseClosureService.average(List,String)」负责主链路中的“average”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private double average(
            List<EvaluationTraceEntity> traces, String metric) {
        return traces.stream()
                .mapToDouble(
                        trace ->
                                read(trace.getMetricScoresJson())
                                        .path(metric)
                                        .asDouble(0))
                .average()
                .orElse(0);
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.read(String)」。
    // 具体功能：「CaseClosureService.read(String)」：读取JSON节点：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「CaseClosureService.read(String)」的上游调用点包括 「CaseClosureService.prepareClosure」、「CaseClosureService.validateCompletedExecution」、「CaseClosureService.buildSnapshot」、「CaseClosureService.draftSnapshot」。
    // 下游影响：「CaseClosureService.read(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「CaseClosureService.read(String)」统一“JSON节点”的跨层表示，避免不同入口产生不兼容字段；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "invalid persisted evaluation JSON", exception);
        }
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.write(Object)」。
    // 具体功能：「CaseClosureService.write(Object)」：写入字符串：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「CaseClosureService.write(Object)」的上游调用点包括 「CaseClosureService.failEvaluation」。
    // 下游影响：「CaseClosureService.write(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「CaseClosureService.write(Object)」负责主链路中的“字符串”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "cannot serialize evaluation data", exception);
        }
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.requiredJsonText(JsonNode,String)」。
    // 具体功能：「CaseClosureService.requiredJsonText(JsonNode,String)」：校验JSON文本；实际协作者为 「closureDenied」、「node.path(field).asText」；处理的关键状态/协议值包括 「field」，最终返回「String」。
    // 上游调用：「CaseClosureService.requiredJsonText(JsonNode,String)」的上游调用点包括 「CaseClosureService.expectedActionTypes」。
    // 下游影响：「CaseClosureService.requiredJsonText(JsonNode,String)」向下依次触达 「closureDenied」、「node.path(field).asText」；计算结果以「String」交给调用方。
    // 系统意义：「CaseClosureService.requiredJsonText(JsonNode,String)」在“JSON文本”进入下游前阻断非法状态；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private static String requiredJsonText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw closureDenied(
                    "approved action field is required",
                    Map.of("field", field));
        }
        return value;
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.requireText(String,String)」。
    // 具体功能：「CaseClosureService.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「CaseClosureService.requireText(String,String)」的上游调用点包括 「CaseClosureService.close」。
    // 下游影响：「CaseClosureService.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseClosureService.requireText(String,String)」在“文本”进入下游前阻断非法状态；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.assertCanClose(AuthenticatedActor)」。
    // 具体功能：「CaseClosureService.assertCanClose(AuthenticatedActor)」：断言CanClose；实际协作者为 「actor.role」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「CaseClosureService.assertCanClose(AuthenticatedActor)」的上游调用点包括 「CaseClosureService.close」。
    // 下游影响：「CaseClosureService.assertCanClose(AuthenticatedActor)」向下依次触达 「actor.role」。
    // 系统意义：「CaseClosureService.assertCanClose(AuthenticatedActor)」在“CanClose”进入下游前阻断非法状态；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private static void assertCanClose(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.ADMIN
                && actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException(
                    "only the workflow or an administrator can close a case");
        }
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.assertCanReadEvaluation(AuthenticatedActor)」。
    // 具体功能：「CaseClosureService.assertCanReadEvaluation(AuthenticatedActor)」：断言CanRead评估；实际协作者为 「actor.role」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「CaseClosureService.assertCanReadEvaluation(AuthenticatedActor)」的上游调用点包括 「CaseClosureService.evaluation」、「CaseClosureService.metrics」。
    // 下游影响：「CaseClosureService.assertCanReadEvaluation(AuthenticatedActor)」向下依次触达 「actor.role」。
    // 系统意义：「CaseClosureService.assertCanReadEvaluation(AuthenticatedActor)」在“CanRead评估”进入下游前阻断非法状态；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private static void assertCanReadEvaluation(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.ADMIN
                && actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException(
                    "only an administrator can read evaluation reports");
        }
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.closureDenied(String,Map)」。
    // 具体功能：「CaseClosureService.closureDenied(String,Map)」：构建结案拒绝结果，最终返回「CaseClosureException」。
    // 上游调用：「CaseClosureService.closureDenied(String,Map)」的上游调用点包括 「CaseClosureService.evaluation」、「CaseClosureService.prepareClosure」、「CaseClosureService.latestApproval」、「CaseClosureService.validateCompletedExecution」。
    // 下游影响：「CaseClosureService.closureDenied(String,Map)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「CaseClosureException」交给调用方。
    // 系统意义：「CaseClosureService.closureDenied(String,Map)」负责主链路中的“结案拒绝结果”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private static CaseClosureException closureDenied(
            String message, Map<String, Object> details) {
        return new CaseClosureException(message, details);
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.caseNotFound(String)」。
    // 具体功能：「CaseClosureService.caseNotFound(String)」：构建案件不Found；处理的关键状态/协议值包括 「case_id」，最终返回「NotFoundException」。
    // 上游调用：「CaseClosureService.caseNotFound(String)」的上游调用点包括 「CaseClosureService.evaluation」、「CaseClosureService.prepareClosure」、「CaseClosureService.closureView」。
    // 下游影响：「CaseClosureService.caseNotFound(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「NotFoundException」交给调用方。
    // 系统意义：「CaseClosureService.caseNotFound(String)」负责主链路中的“案件不Found”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private static NotFoundException caseNotFound(String caseId) {
        return new NotFoundException(
                ErrorCode.CASE_NOT_FOUND,
                "case not found",
                Map.of("case_id", caseId));
    }

    // 所属模块：【结案与离线评估 / 应用编排层】「CaseClosureService.compactUuid()」。
    // 具体功能：「CaseClosureService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「CaseClosureService.compactUuid()」的上游调用点包括 「CaseClosureService.prepareClosure」。
    // 下游影响：「CaseClosureService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「CaseClosureService.compactUuid()」负责主链路中的“UUID”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【结案与离线评估 / 应用编排层】类型「PendingClosure」。
    // 类型职责：定义待处理结案跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record PendingClosure(
            String caseId,
            String evaluationTraceId,
            JsonNode snapshot,
            boolean invokeEvaluation) {}
}

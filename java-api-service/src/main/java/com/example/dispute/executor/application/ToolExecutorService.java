/*
 * 所属模块：确定性工具执行。
 * 文件职责：编排审核后白名单动作执行规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「executeApprovedActions」、「actions」；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;
import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.tool.application.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// 所属模块：【确定性工具执行 / 应用编排层】类型「ToolExecutorService」。
// 类型职责：编排审核后白名单动作执行规则、权限校验与事实读写；本类型显式提供 「ToolExecutorService」、「executeApprovedActions」、「executeAction」、「actions」、「loadApprovedExecution」、「validateFrozenApproval」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.executeApprovedPlan」、「CaseOutcomeService.get」、「ExecutionController.actions」、「ExecutionController.execute」 使用。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class ToolExecutorService {

    private static final List<ApprovalDecisionType> EXECUTABLE_DECISIONS =
            List.of(
                    ApprovalDecisionType.APPROVE,
                    ApprovalDecisionType.MODIFY_AND_APPROVE);

    private final FulfillmentCaseRepository caseRepository;
    private final RemedyPlanRepository planRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final ReviewPacketRepository packetRepository;
    private final ActionRecordRepository actionRepository;
    private final ActionExecutionLock executionLock;
    private final ToolRegistry toolRegistry;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.ToolExecutorService(FulfillmentCaseRepository,RemedyPlanRepository,ApprovalRecordRepository,ReviewPacketRepository,ActionRecordRepository,ActionExecutionLock,ToolRegistry,AuditRecorder,ObjectMapper,TransactionTemplate)」。
    // 具体功能：「ToolExecutorService.ToolExecutorService(FulfillmentCaseRepository,RemedyPlanRepository,ApprovalRecordRepository,ReviewPacketRepository,ActionRecordRepository,ActionExecutionLock,ToolRegistry,AuditRecorder,ObjectMapper,TransactionTemplate)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「planRepository」(RemedyPlanRepository)、「approvalRepository」(ApprovalRecordRepository)、「packetRepository」(ReviewPacketRepository)、「actionRepository」(ActionRecordRepository)、「executionLock」(ActionExecutionLock)、「toolRegistry」(ToolRegistry)、「auditRecorder」(AuditRecorder)、「objectMapper」(ObjectMapper)、「transactions」(TransactionTemplate) 并保存为「ToolExecutorService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ToolExecutorService.ToolExecutorService(FulfillmentCaseRepository,RemedyPlanRepository,ApprovalRecordRepository,ReviewPacketRepository,ActionRecordRepository,ActionExecutionLock,ToolRegistry,AuditRecorder,ObjectMapper,TransactionTemplate)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「ToolExecutorService.ToolExecutorService(FulfillmentCaseRepository,RemedyPlanRepository,ApprovalRecordRepository,ReviewPacketRepository,ActionRecordRepository,ActionExecutionLock,ToolRegistry,AuditRecorder,ObjectMapper,TransactionTemplate)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ToolExecutorService.ToolExecutorService(FulfillmentCaseRepository,RemedyPlanRepository,ApprovalRecordRepository,ReviewPacketRepository,ActionRecordRepository,ActionExecutionLock,ToolRegistry,AuditRecorder,ObjectMapper,TransactionTemplate)」负责主链路中的“工具执行器服务”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ToolExecutorService(
            FulfillmentCaseRepository caseRepository,
            RemedyPlanRepository planRepository,
            ApprovalRecordRepository approvalRepository,
            ReviewPacketRepository packetRepository,
            ActionRecordRepository actionRepository,
            ActionExecutionLock executionLock,
            ToolRegistry toolRegistry,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            TransactionTemplate transactions) {
        this.caseRepository = caseRepository;
        this.planRepository = planRepository;
        this.approvalRepository = approvalRepository;
        this.packetRepository = packetRepository;
        this.actionRepository = actionRepository;
        this.executionLock = executionLock;
        this.toolRegistry = toolRegistry;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.executeApprovedActions(String,String,AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.executeApprovedActions(String,String,AuthenticatedActor)」：在短事务中加载并冻结人工批准的执行快照，按已有成功记录跳过已完成动作；每个剩余动作先获取 Redis 幂等锁再独立执行，最终汇总动作记录而不重跑成功副作用，最终返回「ExecutionBatchView」。
    // 上游调用：「ToolExecutorService.executeApprovedActions(String,String,AuthenticatedActor)」的上游调用点包括 「ExecutionController.execute」、「CaseFulfillmentDisputeActivitiesImpl.executeApprovedPlan」、「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords」、「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction」。
    // 下游影响：「ToolExecutorService.executeApprovedActions(String,String,AuthenticatedActor)」向下依次触达 「actionRepository.findAllByCaseIdOrderByCreatedAtAsc」、「transactions.execute」、「snapshot.actions」、「executionLock.acquire」；计算结果以「ExecutionBatchView」交给调用方。
    // 系统意义：「ToolExecutorService.executeApprovedActions(String,String,AuthenticatedActor)」负责主链路中的“已审批动作列表”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public ExecutionBatchView executeApprovedActions(
            String caseId, String commandIdempotencyKey, AuthenticatedActor actor) {
        assertCanExecute(actor);
        if (commandIdempotencyKey == null || commandIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "command idempotency key must not be blank");
        }
        ExecutionSnapshot snapshot =
                transactions.execute(
                        ignored ->
                                loadApprovedExecution(
                                        caseId, commandIdempotencyKey, actor));
        // snapshot 在短事务中完成审批校验后即不可变。每个动作再用独立 Redis 锁保护，
        // 允许多实例并发请求安全汇合到同一 idempotencyKey。
        for (ExecutableAction action : snapshot.actions()) {
            String ownerToken = executionLock.acquire(action.idempotencyKey());
            try {
                executeAction(snapshot, action, actor);
            } finally {
                executionLock.release(action.idempotencyKey(), ownerToken);
            }
        }
        List<ActionRecordView> records =
                actionRepository
                        .findAllByCaseIdOrderByCreatedAtAsc(caseId)
                        .stream()
                        .filter(
                                record ->
                                        record.getPlanId()
                                                .equals(snapshot.planId()))
                        .map(this::view)
                        .toList();
        return new ExecutionBatchView(
                caseId,
                snapshot.planId(),
                snapshot.approvalRecordId(),
                records.stream()
                        .allMatch(
                                record ->
                                        record.executionStatus()
                                                == ExecutionStatus.SUCCEEDED),
                records);
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.executeAction(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.executeAction(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」：执行动作：先在显式事务模板中原子写入业务事实，再在事务模板中读取并更新同一版本的案件状态；实际协作者为 「toolRegistry.execute」、「transactions.execute」、「prepared.invokeTool」、「transactions.executeWithoutResult」，最终返回「void」。
    // 上游调用：「ToolExecutorService.executeAction(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」的上游调用点包括 「ToolExecutorService.executeApprovedActions」。
    // 下游影响：「ToolExecutorService.executeAction(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」向下依次触达 「toolRegistry.execute」、「transactions.execute」、「prepared.invokeTool」、「transactions.executeWithoutResult」。
    // 系统意义：「ToolExecutorService.executeAction(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」负责主链路中的“动作”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private void executeAction(
            ExecutionSnapshot snapshot,
            ExecutableAction action,
            AuthenticatedActor actor) {
        // 三段式边界：
        // 1. 事务内创建或领取 RUNNING ActionRecord；
        // 2. 事务外调用退款、补发等真实工具；
        // 3. 新事务把外部结果收敛为 SUCCEEDED/FAILED。
        // 这样不会长时间占用数据库锁，超时后也能凭 ActionRecord 查询而不是盲目重做。
        PreparedAction prepared =
                transactions.execute(
                        ignored -> prepare(snapshot, action, actor));
        if (!prepared.invokeTool()) {
            return;
        }
        try {
            ToolExecutionResult result = toolRegistry.execute(action);
            transactions.executeWithoutResult(
                    ignored ->
                            completeSuccess(snapshot, action, result, actor));
        } catch (ToolExecutionException exception) {
            transactions.executeWithoutResult(
                    ignored ->
                            completeFailure(
                                    snapshot, action, exception, actor));
            throw exception;
        }
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.actions(String,AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.actions(String,AuthenticatedActor)」：构建动作列表：先由 Spring 事务代理统一提交数据库变化，再按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「actionRepository.findAllByCaseIdOrderByCreatedAtAsc」、「caseNotFound」、「assertCanRead」，最终返回「List<ActionRecordView>」。
    // 上游调用：「ToolExecutorService.actions(String,AuthenticatedActor)」的上游调用点包括 「ExecutionController.actions」、「CaseOutcomeService.get」、「ExecutionControllerTest.administratorCanExecuteApprovedPlanAndListActionRecords」、「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft」。
    // 下游影响：「ToolExecutorService.actions(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「actionRepository.findAllByCaseIdOrderByCreatedAtAsc」、「caseNotFound」、「assertCanRead」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ToolExecutorService.actions(String,AuthenticatedActor)」定义原子提交边界；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public List<ActionRecordView> actions(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        assertCanRead(disputeCase, actor);
        return actionRepository
                .findAllByCaseIdOrderByCreatedAtAsc(caseId)
                .stream()
                .map(this::view)
                .toList();
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.loadApprovedExecution(String,String,AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.loadApprovedExecution(String,String,AuthenticatedActor)」：锁定案件、RemedyPlan 和最新 APPROVE/MODIFY_AND_APPROVE 记录，验证 ReviewPacket 版本与 action hash，只从 approvedPlanJson 解析白名单动作并把案件推进到 EXECUTING，最终返回「ExecutionSnapshot」。
    // 上游调用：「ToolExecutorService.loadApprovedExecution(String,String,AuthenticatedActor)」的上游调用点包括 「ToolExecutorService.executeApprovedActions」。
    // 下游影响：「ToolExecutorService.loadApprovedExecution(String,String,AuthenticatedActor)」向下依次触达 「findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc」、「caseRepository.findByIdForUpdate」、「planRepository.findById」、「caseRepository.save」；计算结果以「ExecutionSnapshot」交给调用方。
    // 系统意义：「ToolExecutorService.loadApprovedExecution(String,String,AuthenticatedActor)」负责主链路中的“已审批执行”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private ExecutionSnapshot loadApprovedExecution(
            String caseId,
            String commandIdempotencyKey,
            AuthenticatedActor actor) {
        ApprovalRecordEntity approval =
                approvalRepository
                        .findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(
                                caseId, EXECUTABLE_DECISIONS)
                        .orElseThrow(
                                () ->
                                        denied(
                                                "approved remedy plan is required",
                                                Map.of("case_id", caseId)));
        ReviewPacketEntity packet =
                validateFrozenApproval(caseId, approval);
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        if (disputeCase.getCaseStatus()
                        != com.example.dispute.domain.model.CaseStatus
                                .APPROVED_FOR_EXECUTION
                && disputeCase.getCaseStatus()
                        != com.example.dispute.domain.model.CaseStatus.EXECUTING) {
            throw denied(
                    "case is not approved for execution",
                    Map.of("case_status", disputeCase.getCaseStatus().name()));
        }
        var previousStatus = disputeCase.getCaseStatus();
        RemedyPlanEntity plan =
                planRepository
                        .findById(approval.getPlanId())
                        .orElseThrow(
                                () ->
                                        denied(
                                                "approved remedy plan does not exist",
                                                Map.of(
                                                        "plan_id",
                                                        approval.getPlanId())));
        if (!caseId.equals(plan.getCaseId())
                || !caseId.equals(approval.getCaseId())) {
            throw denied(
                    "approval, plan, and case do not match",
                    Map.of("case_id", caseId));
        }
        JsonNode approvedPlan = read(approval.getApprovedPlanJson());
        String calculatedActionHash =
                ActionSnapshotHasher.hash(objectMapper, approvedPlan);
        if (!calculatedActionHash.equals(approval.getActionSnapshotHash())) {
            throw denied(
                    "approved action snapshot hash does not match human review record",
                    Map.of("approval_record_id", approval.getId()));
        }
        if (approval.getDecisionType() == ApprovalDecisionType.APPROVE
                && !packet.getActionHash().equals(calculatedActionHash)) {
            throw denied(
                    "approved action snapshot does not match frozen review packet",
                    Map.of("review_packet_id", packet.getId()));
        }
        if (!plan.getId().equals(approvedPlan.path("id").asText())) {
            throw denied(
                    "approval does not reference the current remedy plan",
                    Map.of("plan_id", plan.getId()));
        }
        List<ExecutableAction> executableActions =
                approvedActions(caseId, plan, approvedPlan);
        if (executableActions.isEmpty()) {
            throw denied(
                    "approved remedy plan contains no executable actions",
                    Map.of("plan_id", plan.getId()));
        }
        disputeCase.beginExecution(actor.actorId());
        caseRepository.save(disputeCase);
        auditRecorder.record(
                actor,
                previousStatus
                                == com.example.dispute.domain.model.CaseStatus
                                        .EXECUTING
                        ? "TOOL_EXECUTION_BATCH_RESUMED"
                        : "TOOL_EXECUTION_BATCH_STARTED",
                "REMEDY_PLAN",
                plan.getId(),
                caseId,
                Map.of(
                        "case_status",
                        previousStatus.name()),
                Map.of(
                        "case_status",
                        com.example.dispute.domain.model.CaseStatus.EXECUTING
                                .name(),
                        "command_idempotency_key",
                        commandIdempotencyKey,
                        "action_count",
                        executableActions.size()));
        return new ExecutionSnapshot(
                caseId,
                plan.getId(),
                approval.getId(),
                approval.getReviewerId(),
                packet.getId(),
                approval.getActionSnapshotHash(),
                packet.getEvidenceMatrixJson(),
                packet.getDraftJson(),
                packet.getAgentRunRefsJson(),
                executableActions);
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.validateFrozenApproval(String,ApprovalRecordEntity)」。
    // 具体功能：「ToolExecutorService.validateFrozenApproval(String,ApprovalRecordEntity)」：逐项核对 approval 绑定的 ReviewPacket、packetVersion、caseId、planId、审核员角色与 actionSnapshotHash，防止审批后改动计划或拿其他案件审批复用，最终返回「ReviewPacketEntity」。
    // 上游调用：「ToolExecutorService.validateFrozenApproval(String,ApprovalRecordEntity)」的上游调用点包括 「ToolExecutorService.loadApprovedExecution」。
    // 下游影响：「ToolExecutorService.validateFrozenApproval(String,ApprovalRecordEntity)」向下依次触达 「packetRepository.findById」、「packetRepository.findFirstByCaseIdAndPlanIdOrderByPacketVersionDesc」、「approval.getReviewPacketId」、「approval.getActionSnapshotHash」；计算结果以「ReviewPacketEntity」交给调用方。
    // 系统意义：「ToolExecutorService.validateFrozenApproval(String,ApprovalRecordEntity)」在“冻结审批”进入下游前阻断非法状态；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private ReviewPacketEntity validateFrozenApproval(
            String caseId,
            ApprovalRecordEntity approval) {
        if (approval.getReviewPacketId() == null
                || approval.getActionSnapshotHash() == null
                || approval.getApprovalExpiresAt() == null) {
            throw denied(
                    "frozen human review provenance is required",
                    Map.of("approval_record_id", approval.getId()));
        }
        ReviewPacketEntity packet =
                packetRepository
                        .findById(approval.getReviewPacketId())
                        .orElseThrow(
                                () ->
                                        denied(
                                                "frozen review packet does not exist",
                                                Map.of(
                                                        "review_packet_id",
                                                        approval.getReviewPacketId())));
        ReviewPacketEntity latest =
                packetRepository
                        .findFirstByCaseIdAndPlanIdOrderByPacketVersionDesc(
                                caseId, approval.getPlanId())
                        .orElseThrow(
                                () ->
                                        denied(
                                                "current review packet does not exist",
                                                Map.of("case_id", caseId)));
        if (!packet.isFrozen()) {
            throw denied(
                    "review packet is not frozen",
                    Map.of("review_packet_id", packet.getId()));
        }
        if (!packet.getId().equals(latest.getId())
                || packet.getPacketVersion()
                        != approval.getReviewPacketVersion()) {
            throw denied(
                    "human approval references a stale review packet",
                    Map.of("review_packet_id", packet.getId()));
        }
        if (!caseId.equals(packet.getCaseId())
                || !approval.getPlanId().equals(packet.getPlanId())) {
            throw denied(
                    "review packet, approval, plan, and case do not match",
                    Map.of("review_packet_id", packet.getId()));
        }
        if (!"PLATFORM_REVIEWER".equals(approval.getReviewerRole())) {
            throw denied(
                    "approval was not issued by the required reviewer role",
                    Map.of("reviewer_role", approval.getReviewerRole()));
        }
        if (OffsetDateTime.now(ZoneOffset.UTC)
                .isAfter(approval.getApprovalExpiresAt())) {
            throw denied(
                    "human approval has expired",
                    Map.of("approval_record_id", approval.getId()));
        }
        return packet;
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.approvedActions(String,RemedyPlanEntity,JsonNode)」。
    // 具体功能：「ToolExecutorService.approvedActions(String,RemedyPlanEntity,JsonNode)」：解析人工批准快照中的 actions，并与原 RemedyPlan 的 action_type、idempotency_key 和结构逐项比对；审核员可以修改允许字段，但不能注入未规划工具，最终返回「List<ExecutableAction>」。
    // 上游调用：「ToolExecutorService.approvedActions(String,RemedyPlanEntity,JsonNode)」的上游调用点包括 「ToolExecutorService.loadApprovedExecution」。
    // 下游影响：「ToolExecutorService.approvedActions(String,RemedyPlanEntity,JsonNode)」向下依次触达 「approvedActionNodes.isArray」、「plan.getId」、「plan.getActionsJson」、「originalActions.isArray」；计算结果以「List<ExecutableAction>」交给调用方。
    // 系统意义：「ToolExecutorService.approvedActions(String,RemedyPlanEntity,JsonNode)」负责主链路中的“已审批动作列表”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<ExecutableAction> approvedActions(
            String caseId, RemedyPlanEntity plan, JsonNode approvedPlan) {
        JsonNode approvedActionNodes = approvedPlan.path("actions");
        if (!approvedActionNodes.isArray()) {
            throw denied(
                    "approved plan actions must be an array",
                    Map.of("plan_id", plan.getId()));
        }
        Map<String, JsonNode> originalByKey = new LinkedHashMap<>();
        JsonNode originalActions = read(plan.getActionsJson());
        if (!originalActions.isArray()) {
            throw denied(
                    "persisted remedy plan actions are invalid",
                    Map.of("plan_id", plan.getId()));
        }
        originalActions.forEach(
                node ->
                        originalByKey.put(
                                node.path("idempotency_key").asText(), node));
        List<ExecutableAction> result = new ArrayList<>();
        for (JsonNode node : approvedActionNodes) {
            String actionType = requiredText(node, "action_type");
            String idempotencyKey =
                    requiredText(node, "idempotency_key");
            JsonNode original = originalByKey.get(idempotencyKey);
            if (original == null
                    || !actionType.equals(
                            original.path("action_type").asText())
                    || !node.path("requires_approval").asBoolean()
                    || !original.path("requires_approval").asBoolean()) {
                throw denied(
                        "action is not contained in the approved remedy plan",
                        Map.of(
                                "action_type",
                                actionType,
                                "idempotency_key",
                                idempotencyKey));
            }
            result.add(
                    new ExecutableAction(
                            actionType,
                            idempotencyKey,
                            risk(node.path("risk_level").asText()),
                            parameters(node.path("parameters"))));
        }
        JsonNode approvedNotifications = approvedPlan.path("notifications");
        if (!approvedNotifications.isArray()) {
            throw denied(
                    "approved plan notifications must be an array",
                    Map.of("plan_id", plan.getId()));
        }
        Set<String> originalNotifications =
                new LinkedHashSet<>(
                        objectMapper.convertValue(
                                read(plan.getNotificationPlanJson()),
                                new TypeReference<List<String>>() {}));
        int index = 0;
        for (JsonNode notificationNode : approvedNotifications) {
            String notification = notificationNode.asText();
            if (!originalNotifications.contains(notification)) {
                throw denied(
                        "notification is not contained in the approved remedy plan",
                        Map.of("notification", notification));
            }
            result.add(
                    new ExecutableAction(
                            notification,
                            "REMEDY:"
                                    + caseId
                                    + ":"
                                    + plan.getPlanVersion()
                                    + ":NOTIFICATION:"
                                    + index
                                    + ":"
                                    + notification,
                            RiskLevel.LOW,
                            Map.of(
                                    "case_id", caseId,
                                    "plan_id", plan.getId())));
            index++;
        }
        return List.copyOf(result);
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.prepare(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.prepare(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」：按动作 idempotencyKey 加行锁：已成功返回跳过，失败记录可进入重试，首次执行创建 RUNNING ActionRecord 并写入 ReviewPacket/hash/evidence/rule 治理引用，最终返回「PreparedAction」。
    // 上游调用：「ToolExecutorService.prepare(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」的上游调用点包括 「ToolExecutorService.executeAction」。
    // 下游影响：「ToolExecutorService.prepare(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」向下依次触达 「actionRepository.findByIdempotencyKeyForUpdate」、「actionRepository.save」、「ActionRecordEntity.runningGoverned」、「action.idempotencyKey」；计算结果以「PreparedAction」交给调用方。
    // 系统意义：「ToolExecutorService.prepare(ExecutionSnapshot,ExecutableAction,AuthenticatedActor)」负责主链路中的“Prepared动作”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private PreparedAction prepare(
            ExecutionSnapshot snapshot,
            ExecutableAction action,
            AuthenticatedActor actor) {
        String requestJson = write(action);
        var existing =
                actionRepository.findByIdempotencyKeyForUpdate(
                        action.idempotencyKey());
        if (existing.isPresent()) {
            ActionRecordEntity record = existing.get();
            assertRecordMatches(record, snapshot, action);
            if (record.getExecutionStatus() == ExecutionStatus.SUCCEEDED) {
                return new PreparedAction(record.getId(), false);
            }
            record.retry(actor.actorId(), requestJson);
            actionRepository.save(record);
            auditStarted(record, actor, true);
            return new PreparedAction(record.getId(), true);
        }
        ActionRecordEntity record =
                actionRepository.save(
                        ActionRecordEntity.runningGoverned(
                                "ACTION_" + compactUuid(),
                                snapshot.caseId(),
                                snapshot.planId(),
                                snapshot.approvalRecordId(),
                                action.actionType(),
                                action.riskLevel(),
                                action.idempotencyKey(),
                                snapshot.approvedBy(),
                                actor.actorId(),
                                requestJson,
                                snapshot.reviewPacketId(),
                                snapshot.actionSnapshotHash(),
                                snapshot.evidenceRefsJson(),
                                snapshot.ruleRefsJson(),
                                snapshot.agentRunRefsJson()));
        auditStarted(record, actor, false);
        return new PreparedAction(record.getId(), true);
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.completeSuccess(ExecutionSnapshot,ExecutableAction,ToolExecutionResult,AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.completeSuccess(ExecutionSnapshot,ExecutableAction,ToolExecutionResult,AuthenticatedActor)」：工具返回后重新锁定 ActionRecord，校验仍绑定同一案件/计划/审批/动作，再保存结果引用和 SUCCEEDED 审计，最终返回「void」。
    // 上游调用：「ToolExecutorService.completeSuccess(ExecutionSnapshot,ExecutableAction,ToolExecutionResult,AuthenticatedActor)」的上游调用点包括 「ToolExecutorService.executeAction」。
    // 下游影响：「ToolExecutorService.completeSuccess(ExecutionSnapshot,ExecutableAction,ToolExecutionResult,AuthenticatedActor)」向下依次触达 「actionRepository.findByIdempotencyKeyForUpdate」、「actionRepository.save」、「action.idempotencyKey」、「record.succeed」。
    // 系统意义：「ToolExecutorService.completeSuccess(ExecutionSnapshot,ExecutableAction,ToolExecutionResult,AuthenticatedActor)」负责主链路中的“成功”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void completeSuccess(
            ExecutionSnapshot snapshot,
            ExecutableAction action,
            ToolExecutionResult result,
            AuthenticatedActor actor) {
        ActionRecordEntity record =
                actionRepository
                        .findByIdempotencyKeyForUpdate(
                                action.idempotencyKey())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "running action record disappeared"));
        assertRecordMatches(record, snapshot, action);
        record.succeed(write(result), result.referenceId());
        actionRepository.save(record);
        auditRecorder.record(
                actor,
                "TOOL_EXECUTION_SUCCEEDED",
                "ACTION_RECORD",
                record.getId(),
                snapshot.caseId(),
                Map.of("execution_status", ExecutionStatus.RUNNING.name()),
                Map.of(
                        "execution_status",
                        ExecutionStatus.SUCCEEDED.name(),
                        "action_type",
                        action.actionType(),
                        "attempt_count",
                        record.getAttemptCount()));
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.completeFailure(ExecutionSnapshot,ExecutableAction,ToolExecutionException,AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.completeFailure(ExecutionSnapshot,ExecutableAction,ToolExecutionException,AuthenticatedActor)」：工具异常后重新锁定 ActionRecord，保存受控错误码和详情并标记 FAILED；失败事实保留供人工接管和安全重试，最终返回「void」。
    // 上游调用：「ToolExecutorService.completeFailure(ExecutionSnapshot,ExecutableAction,ToolExecutionException,AuthenticatedActor)」的上游调用点包括 「ToolExecutorService.executeAction」。
    // 下游影响：「ToolExecutorService.completeFailure(ExecutionSnapshot,ExecutableAction,ToolExecutionException,AuthenticatedActor)」向下依次触达 「actionRepository.findByIdempotencyKeyForUpdate」、「actionRepository.save」、「action.idempotencyKey」、「record.fail」。
    // 系统意义：「ToolExecutorService.completeFailure(ExecutionSnapshot,ExecutableAction,ToolExecutionException,AuthenticatedActor)」负责主链路中的“失败”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void completeFailure(
            ExecutionSnapshot snapshot,
            ExecutableAction action,
            ToolExecutionException exception,
            AuthenticatedActor actor) {
        ActionRecordEntity record =
                actionRepository
                        .findByIdempotencyKeyForUpdate(
                                action.idempotencyKey())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "running action record disappeared"));
        assertRecordMatches(record, snapshot, action);
        record.fail(
                exception.errorCode().name(),
                exception.getMessage(),
                write(
                        Map.of(
                                "status", "FAILED",
                                "error_code",
                                exception.errorCode().name(),
                                "details",
                                exception.details())));
        actionRepository.save(record);
        auditRecorder.record(
                actor,
                "TOOL_EXECUTION_FAILED",
                "ACTION_RECORD",
                record.getId(),
                snapshot.caseId(),
                Map.of("execution_status", ExecutionStatus.RUNNING.name()),
                Map.of(
                        "execution_status",
                        ExecutionStatus.FAILED.name(),
                        "action_type",
                        action.actionType(),
                        "error_code",
                        exception.errorCode().name(),
                        "attempt_count",
                        record.getAttemptCount()));
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.auditStarted(ActionRecordEntity,AuthenticatedActor,boolean)」。
    // 具体功能：「ToolExecutorService.auditStarted(ActionRecordEntity,AuthenticatedActor,boolean)」：执行审计启动记录；实际协作者为 「auditRecorder.record」、「record.getId」、「record.getCaseId」、「record.getActionType」；处理的关键状态/协议值包括 「TOOL_EXECUTION_RETRIED」、「TOOL_EXECUTION_STARTED」、「ACTION_RECORD」、「execution_status」，最终返回「void」。
    // 上游调用：「ToolExecutorService.auditStarted(ActionRecordEntity,AuthenticatedActor,boolean)」的上游调用点包括 「ToolExecutorService.prepare」。
    // 下游影响：「ToolExecutorService.auditStarted(ActionRecordEntity,AuthenticatedActor,boolean)」向下依次触达 「auditRecorder.record」、「record.getId」、「record.getCaseId」、「record.getActionType」。
    // 系统意义：「ToolExecutorService.auditStarted(ActionRecordEntity,AuthenticatedActor,boolean)」负责主链路中的“审计启动记录”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private void auditStarted(
            ActionRecordEntity record,
            AuthenticatedActor actor,
            boolean retry) {
        auditRecorder.record(
                actor,
                retry ? "TOOL_EXECUTION_RETRIED" : "TOOL_EXECUTION_STARTED",
                "ACTION_RECORD",
                record.getId(),
                record.getCaseId(),
                Map.of(),
                Map.of(
                        "execution_status", ExecutionStatus.RUNNING.name(),
                        "action_type", record.getActionType(),
                        "attempt_count", record.getAttemptCount()));
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.assertRecordMatches(ActionRecordEntity,ExecutionSnapshot,ExecutableAction)」。
    // 具体功能：「ToolExecutorService.assertRecordMatches(ActionRecordEntity,ExecutionSnapshot,ExecutableAction)」：断言记录Matches；实际协作者为 「record.getCaseId」、「snapshot.caseId」、「record.getPlanId」、「snapshot.planId」；处理的关键状态/协议值包括 「idempotency_key」，最终返回「void」。
    // 上游调用：「ToolExecutorService.assertRecordMatches(ActionRecordEntity,ExecutionSnapshot,ExecutableAction)」的上游调用点包括 「ToolExecutorService.prepare」、「ToolExecutorService.completeSuccess」、「ToolExecutorService.completeFailure」。
    // 下游影响：「ToolExecutorService.assertRecordMatches(ActionRecordEntity,ExecutionSnapshot,ExecutableAction)」向下依次触达 「record.getCaseId」、「snapshot.caseId」、「record.getPlanId」、「snapshot.planId」。
    // 系统意义：「ToolExecutorService.assertRecordMatches(ActionRecordEntity,ExecutionSnapshot,ExecutableAction)」在“记录Matches”进入下游前阻断非法状态；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private void assertRecordMatches(
            ActionRecordEntity record,
            ExecutionSnapshot snapshot,
            ExecutableAction action) {
        if (!record.getCaseId().equals(snapshot.caseId())
                || !record.getPlanId().equals(snapshot.planId())
                || !record.getApprovalRecordId()
                        .equals(snapshot.approvalRecordId())
                || !record.getActionType().equals(action.actionType())) {
            throw denied(
                    "idempotency key belongs to a different approved action",
                    Map.of(
                            "idempotency_key",
                            action.idempotencyKey()));
        }
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.view(ActionRecordEntity)」。
    // 具体功能：「ToolExecutorService.view(ActionRecordEntity)」：构建视图；实际协作者为 「record.getId」、「record.getCaseId」、「record.getPlanId」、「record.getApprovalRecordId」，最终返回「ActionRecordView」。
    // 上游调用：「ToolExecutorService.view(ActionRecordEntity)」只由「ToolExecutorService」内部流程使用，负责封装“视图”这一步校验、映射或状态转换。
    // 下游影响：「ToolExecutorService.view(ActionRecordEntity)」向下依次触达 「record.getId」、「record.getCaseId」、「record.getPlanId」、「record.getApprovalRecordId」；计算结果以「ActionRecordView」交给调用方。
    // 系统意义：「ToolExecutorService.view(ActionRecordEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private ActionRecordView view(ActionRecordEntity record) {
        return new ActionRecordView(
                record.getId(),
                record.getCaseId(),
                record.getPlanId(),
                record.getApprovalRecordId(),
                record.getActionType(),
                record.getRiskLevel(),
                record.getIdempotencyKey(),
                record.getApprovedBy(),
                record.getExecutedBy(),
                record.getExecutionStatus(),
                record.getAttemptCount(),
                read(record.getRequestJson()),
                read(record.getResultJson()),
                record.getErrorCode(),
                record.getErrorMessage(),
                record.getReviewPacketId(),
                record.getActionSnapshotHash(),
                read(record.getEvidenceRefsJson()),
                read(record.getRuleRefsJson()),
                read(record.getAgentRunRefsJson()),
                record.getExternalResultRef(),
                record.getExecutionTime(),
                record.getCreatedAt());
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.parameters(JsonNode)」。
    // 具体功能：「ToolExecutorService.parameters(JsonNode)」：构建参数；实际协作者为 「node.isMissingNode」、「node.isNull」、「node.isObject」、「objectMapper.convertValue」，最终返回「Map<String, Object>」。
    // 上游调用：「ToolExecutorService.parameters(JsonNode)」的上游调用点包括 「ToolExecutorService.approvedActions」。
    // 下游影响：「ToolExecutorService.parameters(JsonNode)」向下依次触达 「node.isMissingNode」、「node.isNull」、「node.isObject」、「objectMapper.convertValue」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「ToolExecutorService.parameters(JsonNode)」负责主链路中的“参数”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private Map<String, Object> parameters(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw denied(
                    "approved action parameters must be an object",
                    Map.of());
        }
        return objectMapper.convertValue(
                node, new TypeReference<Map<String, Object>>() {});
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.risk(String)」。
    // 具体功能：「ToolExecutorService.risk(String)」：构建风险；实际协作者为 「denied」；处理的关键状态/协议值包括 「risk_level」，最终返回「RiskLevel」。
    // 上游调用：「ToolExecutorService.risk(String)」的上游调用点包括 「ToolExecutorService.approvedActions」。
    // 下游影响：「ToolExecutorService.risk(String)」向下依次触达 「denied」；计算结果以「RiskLevel」交给调用方。
    // 系统意义：「ToolExecutorService.risk(String)」负责主链路中的“风险”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private static RiskLevel risk(String value) {
        try {
            return RiskLevel.valueOf(value);
        } catch (RuntimeException exception) {
            throw denied(
                    "approved action risk level is invalid",
                    Map.of("risk_level", value));
        }
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.requiredText(JsonNode,String)」。
    // 具体功能：「ToolExecutorService.requiredText(JsonNode,String)」：校验文本；实际协作者为 「denied」、「node.path(field).asText」；处理的关键状态/协议值包括 「field」，最终返回「String」。
    // 上游调用：「ToolExecutorService.requiredText(JsonNode,String)」的上游调用点包括 「ToolExecutorService.approvedActions」。
    // 下游影响：「ToolExecutorService.requiredText(JsonNode,String)」向下依次触达 「denied」、「node.path(field).asText」；计算结果以「String」交给调用方。
    // 系统意义：「ToolExecutorService.requiredText(JsonNode,String)」在“文本”进入下游前阻断非法状态；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw denied(
                    "approved action field is required",
                    Map.of("field", field));
        }
        return value;
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.read(String)」。
    // 具体功能：「ToolExecutorService.read(String)」：读取JSON节点：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」、「denied」，最终返回「JsonNode」。
    // 上游调用：「ToolExecutorService.read(String)」的上游调用点包括 「ToolExecutorService.loadApprovedExecution」、「ToolExecutorService.approvedActions」、「ToolExecutorService.view」。
    // 下游影响：「ToolExecutorService.read(String)」向下依次触达 「objectMapper.readTree」、「denied」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「ToolExecutorService.read(String)」统一“JSON节点”的跨层表示，避免不同入口产生不兼容字段；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw denied(
                    "approved execution JSON is invalid",
                    Map.of());
        }
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.write(Object)」。
    // 具体功能：「ToolExecutorService.write(Object)」：写入字符串：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「ToolExecutorService.write(Object)」的上游调用点包括 「ToolExecutorService.prepare」、「ToolExecutorService.completeSuccess」、「ToolExecutorService.completeFailure」。
    // 下游影响：「ToolExecutorService.write(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「ToolExecutorService.write(Object)」负责主链路中的“字符串”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "cannot serialize tool execution payload", exception);
        }
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.assertCanExecute(AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.assertCanExecute(AuthenticatedActor)」：断言CanExecute；实际协作者为 「actor.role」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「ToolExecutorService.assertCanExecute(AuthenticatedActor)」的上游调用点包括 「ToolExecutorService.executeApprovedActions」。
    // 下游影响：「ToolExecutorService.assertCanExecute(AuthenticatedActor)」向下依次触达 「actor.role」。
    // 系统意义：「ToolExecutorService.assertCanExecute(AuthenticatedActor)」在“CanExecute”进入下游前阻断非法状态；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private static void assertCanExecute(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.ADMIN
                && actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException(
                    "only the workflow executor or an administrator can execute actions");
        }
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「ToolExecutorService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」：断言CanRead；实际协作者为 「actor.role」、「actor.actorId」、「disputeCase.getUserId」、「disputeCase.getMerchantId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「ToolExecutorService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「ToolExecutorService.actions」。
    // 下游影响：「ToolExecutorService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「disputeCase.getUserId」、「disputeCase.getMerchantId」。
    // 系统意义：「ToolExecutorService.assertCanRead(FulfillmentCaseEntity,AuthenticatedActor)」在“CanRead”进入下游前阻断非法状态；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private static void assertCanRead(
            FulfillmentCaseEntity disputeCase, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER ->
                            actor.actorId().equals(disputeCase.getUserId());
                    case MERCHANT ->
                            actor.actorId()
                                    .equals(disputeCase.getMerchantId());
                    case CUSTOMER_SERVICE,
                            PLATFORM_REVIEWER,
                            ADMIN,
                            SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException(
                    "actor cannot read action records for this case");
        }
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.caseNotFound(String)」。
    // 具体功能：「ToolExecutorService.caseNotFound(String)」：构建案件不Found；处理的关键状态/协议值包括 「case_id」，最终返回「NotFoundException」。
    // 上游调用：「ToolExecutorService.caseNotFound(String)」的上游调用点包括 「ToolExecutorService.actions」、「ToolExecutorService.loadApprovedExecution」。
    // 下游影响：「ToolExecutorService.caseNotFound(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「NotFoundException」交给调用方。
    // 系统意义：「ToolExecutorService.caseNotFound(String)」负责主链路中的“案件不Found”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private static NotFoundException caseNotFound(String caseId) {
        return new NotFoundException(
                ErrorCode.CASE_NOT_FOUND,
                "case not found",
                Map.of("case_id", caseId));
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.denied(String,Map)」。
    // 具体功能：「ToolExecutorService.denied(String,Map)」：构建拒绝结果，最终返回「ToolExecutionException」。
    // 上游调用：「ToolExecutorService.denied(String,Map)」的上游调用点包括 「ToolExecutorService.loadApprovedExecution」、「ToolExecutorService.validateFrozenApproval」、「ToolExecutorService.approvedActions」、「ToolExecutorService.assertRecordMatches」。
    // 下游影响：「ToolExecutorService.denied(String,Map)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ToolExecutionException」交给调用方。
    // 系统意义：「ToolExecutorService.denied(String,Map)」负责主链路中的“拒绝结果”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private static ToolExecutionException denied(
            String message, Map<String, Object> details) {
        return new ToolExecutionException(
                ErrorCode.TOOL_EXECUTION_DENIED, message, details);
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「ToolExecutorService.compactUuid()」。
    // 具体功能：「ToolExecutorService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「ToolExecutorService.compactUuid()」的上游调用点包括 「ToolExecutorService.prepare」。
    // 下游影响：「ToolExecutorService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「ToolExecutorService.compactUuid()」负责主链路中的“UUID”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【确定性工具执行 / 应用编排层】类型「ExecutionSnapshot」。
    // 类型职责：定义执行快照跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record ExecutionSnapshot(
            String caseId,
            String planId,
            String approvalRecordId,
            String approvedBy,
            String reviewPacketId,
            String actionSnapshotHash,
            String evidenceRefsJson,
            String ruleRefsJson,
            String agentRunRefsJson,
            List<ExecutableAction> actions) {}

    // 所属模块：【确定性工具执行 / 应用编排层】类型「PreparedAction」。
    // 类型职责：定义Prepared动作跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record PreparedAction(String actionRecordId, boolean invokeTool) {}
}

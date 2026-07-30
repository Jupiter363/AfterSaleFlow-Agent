/*
 * 所属模块：平台人工终审。
 * 文件职责：编排冻结审核包与审核员最终决定规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「createForWorkflow」、「list」、「packet」、「decide」；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.PlatformReviewerAuthorization;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.ReviewTaskStatus;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalPolicyDecisionEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalPolicyDecisionRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetRoomCommandIngress;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewHumanDecisionReceipt;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewFrozenExecutionContract;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.example.dispute.review.domain.ApprovalPolicyDecision;
import com.example.dispute.review.domain.ApprovalPolicyEngine;
import com.example.dispute.review.domain.ApprovalPolicyInput;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.review.domain.ReviewPacketVersions;
import com.example.dispute.review.domain.ReviewPacketContentHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// 所属模块：【平台人工终审 / 应用编排层】类型「ReviewApplicationService」。
// 类型职责：编排冻结审核包与审核员最终决定规则、权限校验与事实读写；本类型显式提供 「ReviewApplicationService」、「createForWorkflow」、「list」、「packet」、「decide」、「persistDecision」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.createReviewTask」、「CaseOutcomeService.confirmDraft」、「CaseOutcomeService.modifyDraft」、「HearingOutcomeOrchestrationService.orchestrate」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class ReviewApplicationService {

    static final String LEGACY_DECISION_AUTHORITY = "LEGACY_NONE";
    static final String TRUSTED_DECISION_AUTHORITY = "SERVER_TRUSTED_SYNTHETIC";
    static final String TARGET_DECISION_AUTHORITY = "TARGET_REVIEW";
    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("temporal-worker", ActorRole.SYSTEM);
    private final FulfillmentCaseRepository caseRepository;
    private final RemedyPlanRepository planRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingFlowArtifactRepository hearingArtifactRepository;
    private final HearingStateRepository hearingRepository;
    private final EvidenceDossierRepository dossierRepository;
    private final ReviewPacketRepository packetRepository;
    private final ReviewTaskRepository taskRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final ApprovalPolicyDecisionRepository policyDecisionRepository;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final AuditRecorder auditRecorder;
    private final PostReviewOrchestrationService postReviewOrchestration;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final ApprovalPolicyEngine policyEngine;
    private final int packetExpiryHours;
    private final int reviewDueBusinessDays;
    private final CaseRoomEpochRepository roomEpochRepository;
    private final CaseCommandService caseCommandService;
    private final ObjectProvider<TargetRoomCommandIngress> targetIngressProvider;
    private final ObjectProvider<TargetRoomEpochSelectionAuthority> targetAuthorityProvider;
    private final CaseEventService caseEventService;
    private final ReviewTargetDecisionHandoffWriter targetHandoffWriter;

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.ReviewApplicationService(FulfillmentCaseRepository,RemedyPlanRepository,AdjudicationDraftRepository,HearingStateRepository,EvidenceDossierRepository,ReviewPacketRepository,ReviewTaskRepository,ApprovalRecordRepository,DeliberationReportRepository,ApprovalPolicyDecisionRepository,CaseLifecycleNotificationService,AuditRecorder,PostReviewOrchestrationService,ObjectMapper,TransactionTemplate,BigDecimal,BigDecimal,int)」。
    // 具体功能：「ReviewApplicationService.ReviewApplicationService(FulfillmentCaseRepository,RemedyPlanRepository,AdjudicationDraftRepository,HearingStateRepository,EvidenceDossierRepository,ReviewPacketRepository,ReviewTaskRepository,ApprovalRecordRepository,DeliberationReportRepository,ApprovalPolicyDecisionRepository,CaseLifecycleNotificationService,AuditRecorder,PostReviewOrchestrationService,ObjectMapper,TransactionTemplate,BigDecimal,BigDecimal,int)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「planRepository」(RemedyPlanRepository)、「draftRepository」(AdjudicationDraftRepository)、「hearingRepository」(HearingStateRepository)、「dossierRepository」(EvidenceDossierRepository)、「packetRepository」(ReviewPacketRepository)、「taskRepository」(ReviewTaskRepository)、「approvalRepository」(ApprovalRecordRepository)、「deliberationRepository」(DeliberationReportRepository)、「policyDecisionRepository」(ApprovalPolicyDecisionRepository)、「lifecycleNotifications」(CaseLifecycleNotificationService)、「auditRecorder」(AuditRecorder)、「postReviewOrchestration」(PostReviewOrchestrationService)、「objectMapper」(ObjectMapper)、「transactions」(TransactionTemplate)、「refundThreshold」(BigDecimal)、「reshipThreshold」(BigDecimal)、「reviewTimeoutHours」(int) 并保存为「ReviewApplicationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「ReviewApplicationService.ReviewApplicationService(FulfillmentCaseRepository,RemedyPlanRepository,AdjudicationDraftRepository,HearingStateRepository,EvidenceDossierRepository,ReviewPacketRepository,ReviewTaskRepository,ApprovalRecordRepository,DeliberationReportRepository,ApprovalPolicyDecisionRepository,CaseLifecycleNotificationService,AuditRecorder,PostReviewOrchestrationService,ObjectMapper,TransactionTemplate,BigDecimal,BigDecimal,int)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「ReviewApplicationService.ReviewApplicationService(FulfillmentCaseRepository,RemedyPlanRepository,AdjudicationDraftRepository,HearingStateRepository,EvidenceDossierRepository,ReviewPacketRepository,ReviewTaskRepository,ApprovalRecordRepository,DeliberationReportRepository,ApprovalPolicyDecisionRepository,CaseLifecycleNotificationService,AuditRecorder,PostReviewOrchestrationService,ObjectMapper,TransactionTemplate,BigDecimal,BigDecimal,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewApplicationService.ReviewApplicationService(FulfillmentCaseRepository,RemedyPlanRepository,AdjudicationDraftRepository,HearingStateRepository,EvidenceDossierRepository,ReviewPacketRepository,ReviewTaskRepository,ApprovalRecordRepository,DeliberationReportRepository,ApprovalPolicyDecisionRepository,CaseLifecycleNotificationService,AuditRecorder,PostReviewOrchestrationService,ObjectMapper,TransactionTemplate,BigDecimal,BigDecimal,int)」负责主链路中的“审核应用服务”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    @Autowired
    public ReviewApplicationService(
            FulfillmentCaseRepository caseRepository,
            RemedyPlanRepository planRepository,
            AdjudicationDraftRepository draftRepository,
            HearingFlowArtifactRepository hearingArtifactRepository,
            HearingStateRepository hearingRepository,
            EvidenceDossierRepository dossierRepository,
            ReviewPacketRepository packetRepository,
            ReviewTaskRepository taskRepository,
            ApprovalRecordRepository approvalRepository,
            ApprovalPolicyDecisionRepository policyDecisionRepository,
            CaseLifecycleNotificationService lifecycleNotifications,
            AuditRecorder auditRecorder,
            PostReviewOrchestrationService postReviewOrchestration,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            @Value("${app.approval.refund-threshold:500.00}") BigDecimal refundThreshold,
            @Value("${app.approval.reship-threshold:300.00}") BigDecimal reshipThreshold,
            @Value("${app.approval.packet-expiry-hours:168}") int packetExpiryHours,
            @Value("${app.approval.review-due-business-days:1}") int reviewDueBusinessDays,
            CaseRoomEpochRepository roomEpochRepository,
            CaseCommandService caseCommandService,
            ObjectProvider<TargetRoomCommandIngress> targetIngressProvider,
            ObjectProvider<TargetRoomEpochSelectionAuthority> targetAuthorityProvider,
            CaseEventService caseEventService,
            ReviewTargetDecisionHandoffWriter targetHandoffWriter) {
        this.caseRepository=caseRepository; this.planRepository=planRepository;
        this.draftRepository=draftRepository;
        this.hearingArtifactRepository=hearingArtifactRepository;
        this.hearingRepository=hearingRepository;
        this.dossierRepository=dossierRepository;
        this.packetRepository=packetRepository; this.taskRepository=taskRepository;
        this.approvalRepository=approvalRepository;
        this.policyDecisionRepository=policyDecisionRepository;
        this.lifecycleNotifications=lifecycleNotifications;
        this.auditRecorder=auditRecorder;
        this.postReviewOrchestration=postReviewOrchestration; this.objectMapper=objectMapper;
        this.transactions=transactions;
        this.policyEngine=new ApprovalPolicyEngine(refundThreshold,reshipThreshold);
        this.packetExpiryHours=packetExpiryHours;
        this.reviewDueBusinessDays=Math.max(1,reviewDueBusinessDays);
        this.roomEpochRepository=roomEpochRepository;
        this.caseCommandService=caseCommandService;
        this.targetIngressProvider=targetIngressProvider;
        this.targetAuthorityProvider=targetAuthorityProvider;
        this.caseEventService=caseEventService;
        this.targetHandoffWriter=targetHandoffWriter;
    }

    /** Compatibility constructor retained for focused legacy unit tests. */
    public ReviewApplicationService(
            FulfillmentCaseRepository caseRepository,
            RemedyPlanRepository planRepository,
            AdjudicationDraftRepository draftRepository,
            HearingFlowArtifactRepository hearingArtifactRepository,
            HearingStateRepository hearingRepository,
            EvidenceDossierRepository dossierRepository,
            ReviewPacketRepository packetRepository,
            ReviewTaskRepository taskRepository,
            ApprovalRecordRepository approvalRepository,
            ApprovalPolicyDecisionRepository policyDecisionRepository,
            CaseLifecycleNotificationService lifecycleNotifications,
            AuditRecorder auditRecorder,
            PostReviewOrchestrationService postReviewOrchestration,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            BigDecimal refundThreshold,
            BigDecimal reshipThreshold,
            int packetExpiryHours,
            int reviewDueBusinessDays) {
        this(caseRepository, planRepository, draftRepository, hearingArtifactRepository, hearingRepository,
                dossierRepository, packetRepository, taskRepository, approvalRepository,
                policyDecisionRepository, lifecycleNotifications, auditRecorder, postReviewOrchestration,
                objectMapper, transactions, refundThreshold, reshipThreshold, packetExpiryHours,
                reviewDueBusinessDays, null, null, null, null, null, null);
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.createForWorkflow(String,String)」。
    // 具体功能：「ReviewApplicationService.createForWorkflow(String,String)」：锁定案件和 RemedyPlan，冻结包含案件/卷宗/草案/评议/动作的 ReviewPacket，计算 action hash、packet/version/expiry 并幂等创建分配给系统审核员的 ReviewTask，最终返回「String」。
    // 上游调用：「ReviewApplicationService.createForWorkflow(String,String)」的上游调用点包括 「HearingOutcomeOrchestrationService.orchestrate」、「CaseFulfillmentDisputeActivitiesImpl.createReviewTask」、「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff」、「ReviewApplicationServiceIntegrationTest.anotherPlatformReviewerRetainsReadOnlyReviewAccess」。
    // 下游影响：「ReviewApplicationService.createForWorkflow(String,String)」向下依次触达 「taskRepository.findFirstByCaseIdOrderByCreatedAtDesc」、「caseRepository.findByIdForUpdate」、「planRepository.findById」、「hearingRepository.findByCaseId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ReviewApplicationService.createForWorkflow(String,String)」定义原子提交边界；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public String createForWorkflow(String caseId,String planId){
        FulfillmentCaseEntity disputeCase=caseRepository.findByIdForUpdate(caseId).orElseThrow(()->notFound("case",caseId));
        var existing=taskRepository.findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(caseId, planId);
        if(existing.isPresent() && isOpen(existing.get().getTaskStatus())) return existing.get().getId();
        RemedyPlanEntity plan=planRepository.findById(planId).orElseThrow(()->notFound("remedy plan",planId));
        if(!caseId.equals(plan.getCaseId())) throw new BusinessException(ErrorCode.CASE_STATUS_INVALID,"plan does not belong to case",Map.of());
        List<String> actionTypes=actionTypes(plan.getActionsJson());
        boolean insufficient=hearingRepository.findByCaseId(caseId).map(state->state.isManualRequired()).orElse(false);
        ApprovalPolicyDecision policy=policyEngine.evaluate(new ApprovalPolicyInput(
                plan.getRiskLevel(),plan.getTotalAmount(),actionTypes,disputeCase.getDisputeType(),insufficient));
        ApprovalPolicyDecisionEntity policyDecision=policyDecisionRepository.save(
                ApprovalPolicyDecisionEntity.record(
                        "POLICY_" + id(),
                        caseId,
                        planId,
                        plan.getRiskLevel(),
                        policy,
                        write(policy.allowedActions()),
                        write(policy.forbiddenActions()),
                        SYSTEM.actorId()));
        int version=packetRepository.findFirstByCaseIdAndPlanIdOrderByPacketVersionDesc(caseId,planId)
                .map(packet->packet.getPacketVersion()+1).orElse(1);
        AdjudicationDraftEntity draft=draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(caseId).orElse(null);
        HearingFlowArtifactEntity v2Draft =
                hearingArtifactRepository
                        .findByCaseIdAndArtifactType(
                                caseId, HearingArtifactType.ADJUDICATION_DRAFT)
                        .orElse(null);
        if (v2Draft != null
                && (draft == null
                        || !v2Draft.getId().equals(draft.getId())
                        || !v2Draft.getAgentRunId().equals(draft.getCreatedByAgentRunId())
                        || !v2Draft.getId().equals(plan.getAdjudicationDraftId()))) {
            throw new IllegalStateException(
                    "review packet is not bound to the frozen V2 adjudication draft");
        }
        List<String> agentRunRefs;
        if (v2Draft == null) {
            agentRunRefs =
                    draft == null || draft.getCreatedByAgentRunId() == null
                            ? List.of()
                            : List.of(draft.getCreatedByAgentRunId());
        } else {
            HearingFlowArtifactEntity proposal =
                    requireV2Artifact(caseId, HearingArtifactType.JUDGE_PROPOSAL);
            HearingFlowArtifactEntity report =
                    requireV2Artifact(caseId, HearingArtifactType.JURY_REVIEW_REPORT);
            validateV2DecisionChain(caseId, proposal, report, v2Draft);
            agentRunRefs =
                    List.of(
                            proposal.getAgentRunId(),
                            report.getAgentRunId(),
                            v2Draft.getAgentRunId());
        }
        int dossierVersion =
                dossierRepository
                        .findByCaseId(caseId)
                        .map(item -> item.getDossierVersion())
                        .orElse(1);
        int issueVersion =
                hearingRepository
                        .findByCaseId(caseId)
                        .map(item -> Math.max(1, item.getRoundNo() + 1))
                        .orElse(1);
        int deliberationVersion = 0;
        OffsetDateTime frozenAt = OffsetDateTime.now(ZoneOffset.UTC);
        JsonNode frozenRemedy =
                read(
                        write(
                                Map.of(
                                        "id",
                                        plan.getId(),
                                        "version",
                                        plan.getPlanVersion(),
                                        "actions",
                                        read(plan.getActionsJson()),
                                        "preconditions",
                                        read(plan.getPreconditionsJson()),
                                        "notifications",
                                        read(plan.getNotificationPlanJson()))));
        String actionHash = actionHash(frozenRemedy);
        ReviewPacketEntity packet=packetRepository.save(ReviewPacketEntity.createFrozen(
                "PACKET_"+id(),caseId,planId,version,
                new ReviewPacketVersions(
                        Math.max(1, disputeCase.getVersion()),
                        dossierVersion,
                        issueVersion,
                        draft == null ? 1 : draft.getDraftVersion(),
                        v2Draft == null ? deliberationVersion : 1,
                        plan.getPlanVersion(),
                        "ruleset-current",
                        v2Draft == null ? "hearing-v1" : "hearing-flow.v2",
                        "dispute-default-v1",
                        v2Draft == null ? "presiding-judge-v1" : "hearing-judge-v2"),
                actionHash,
                frozenAt,
                frozenAt.plusHours(packetExpiryHours),
                write(agentRunRefs),
                write(Map.of("title",disputeCase.getTitle(),"description",disputeCase.getDescription(),
                        "route_type",disputeCase.getRouteType().name(),"risk_level",disputeCase.getRiskLevel().name())),
                disputeCase.getIntakeResultJson(),
                draft==null?"[]":draft.getFactFindingsJson(),
                draft==null?"[]":draft.getEvidenceAssessmentJson(),
                v2Draft != null
                        ? v2Draft.getPayloadJson()
                        : draft==null?"{}":write(Map.of(
                                "id",draft.getId(),
                                "recommended_decision",draft.getRecommendedDecision(),
                                "confidence",draft.getConfidence(),
                                "draft_text",draft.getDraftText(),
                                "fact_findings",read(draft.getFactFindingsJson()),
                                "evidence_assessment",read(draft.getEvidenceAssessmentJson()),
                                "policy_application",read(draft.getPolicyApplicationJson()),
                                "reviewer_attention",read(draft.getReviewerAttentionJson()))),
                frozenRemedy.toString(),
                write(policy.riskFlags()),SYSTEM.actorId()));
        ReviewTaskEntity task=taskRepository.save(ReviewTaskEntity.pendingAssigned(
                "REVIEW_"+id(),caseId,planId,packet.getId(),policy.priority(),policy.requiredRole(),
                PlatformReviewerAuthorization.SYSTEM_REVIEWER_ID,
                nextBusinessDay(frozenAt,reviewDueBusinessDays),SYSTEM.actorId(),
                policyDecision.getId()));
        disputeCase.waitForHumanReview(SYSTEM.actorId()); caseRepository.save(disputeCase);
        auditRecorder.record(SYSTEM,"REVIEW_TASK_CREATED","REVIEW_TASK",task.getId(),caseId,Map.of(),
                Map.of("priority",policy.priority(),"required_approvals",policy.requiredApprovals(),"risk_flags",policy.riskFlags()));
        lifecycleNotifications.reviewPending(disputeCase, task.getId());
        return task.getId();
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.list(ReviewTaskStatus,AuthenticatedActor)」。
    // 具体功能：「ReviewApplicationService.list(ReviewTaskStatus,AuthenticatedActor)」：列出列表：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「taskRepository.findAllByTaskStatusOrderByCreatedAtAsc」、「assertCanView」，最终返回「List<ReviewTaskView>」。
    // 上游调用：「ReviewApplicationService.list(ReviewTaskStatus,AuthenticatedActor)」的上游调用点包括 「ReviewController.list」、「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff」、「ReviewApplicationServiceIntegrationTest.anotherPlatformReviewerRetainsReadOnlyReviewAccess」、「ReviewControllerTest.reviewerCanListAndSubmitAuditedDecision」。
    // 下游影响：「ReviewApplicationService.list(ReviewTaskStatus,AuthenticatedActor)」向下依次触达 「taskRepository.findAllByTaskStatusOrderByCreatedAtAsc」、「assertCanView」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ReviewApplicationService.list(ReviewTaskStatus,AuthenticatedActor)」定义原子提交边界；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly=true)
    public List<ReviewTaskView> list(ReviewTaskStatus status,AuthenticatedActor actor){
        assertCanView(actor);
        return taskRepository.findAllByTaskStatusOrderByCreatedAtAsc(status).stream().map(this::view).toList();
    }
    @Transactional
    public ReviewTaskView start(String taskId, AuthenticatedActor actor) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        ReviewTaskEntity task =
                taskRepository
                        .findByIdForUpdate(taskId)
                        .orElseThrow(() -> notFound("review task", taskId));
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(task.getCaseId())
                        .orElseThrow(() -> notFound("case", task.getCaseId()));
        if (targetEpoch(task.getCaseId()) != null) {
            // Target Review has no browser-side orchestration. This is only the durable human task opening.
            task.startReview(actor.actorId());
            taskRepository.save(task);
            return view(task);
        }
        String previousTaskStatus = task.getTaskStatus().name();
        String previousRoom = Objects.toString(disputeCase.getCurrentRoom(), "");
        task.startReview(actor.actorId());
        disputeCase.enterHumanReview(actor.actorId());
        taskRepository.save(task);
        caseRepository.save(disputeCase);
        auditRecorder.record(
                actor,
                "REVIEW_STARTED",
                "REVIEW_TASK",
                task.getId(),
                task.getCaseId(),
                Map.of("task_status", previousTaskStatus, "current_room", previousRoom),
                Map.of("task_status", "IN_REVIEW", "current_room", "REVIEW"));
        return view(task);
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.packet(String,AuthenticatedActor)」。
    // 具体功能：「ReviewApplicationService.packet(String,AuthenticatedActor)」：校验客服或平台审核只读权限后读取任务绑定的不可变 ReviewPacket，并反序列化事实、证据、规则、草案与补救动作，最终返回「ReviewPacketView」。
    // 上游调用：「ReviewApplicationService.packet(String,AuthenticatedActor)」的上游调用点包括 「ReviewController.packet」、「ReviewCopilotStreamService.query」、「ReviewCopilotStreamService.active」、「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff」。
    // 下游影响：「ReviewApplicationService.packet(String,AuthenticatedActor)」向下依次触达 「taskRepository.findById」、「packetRepository.findById」、「task.getPacketId」、「p.getId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「ReviewApplicationService.packet(String,AuthenticatedActor)」定义原子提交边界；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly=true)
    public ReviewPacketView packet(String taskId,AuthenticatedActor actor){
        assertCanView(actor);
        ReviewTaskEntity task=taskRepository.findById(taskId).orElseThrow(()->notFound("review task",taskId));
        ReviewPacketEntity p=packetRepository.findById(task.getPacketId()).orElseThrow(()->notFound("review packet",task.getPacketId()));
        String contentHash = packetContentHash(p);
        return new ReviewPacketView(p.getId(),p.getCaseId(),p.getPlanId(),p.getPacketVersion(),
                p.getCaseVersion(),p.getDossierVersion(),p.getIssueVersion(),
                p.getAdjudicationDraftVersion(),p.getDeliberationReportVersion(),
                p.getRemedyPlanVersion(),p.getRulesetVersion(),p.getPromptVersion(),
                p.getSkillVersion(),p.getProfileVersion(),p.getActionHash(),contentHash,
                read(p.getAgentRunRefsJson()),
                p.getFrozenAt(),p.getExpiresAt(),
                read(p.getCaseSummaryJson()),read(p.getClaimsJson()),read(p.getIssuesJson()),
                read(p.getEvidenceMatrixJson()),read(p.getDraftJson()),read(p.getRemedyJson()),
                read(p.getRiskFlagsJson()),p.getPacketStatus(),task.getTaskStatus().name(),
                task.getAssignedReviewerId(),reviewDeadline(task,p));
    }

    @Transactional
    public ReviewPacketAuthorizationView packetAuthorization(
            String taskId,
            AuthenticatedActor actor,
            long roomEpoch,
            long processRevision,
            long fencingToken) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        ReviewTaskEntity task=taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(()->notFound("review task",taskId));
        if(!Objects.equals(task.getAssignedReviewerId(),actor.actorId()))
            throw new ForbiddenException("review packet capability belongs to the assigned reviewer");
        if(!isOpen(task.getTaskStatus()))
            throw new BusinessException(ErrorCode.CASE_STATUS_INVALID,"review task is not open",Map.of("status",task.getTaskStatus().name()));
        ReviewPacketEntity packet=packetRepository.findById(task.getPacketId())
                .orElseThrow(()->notFound("review packet",task.getPacketId()));
        if(!packet.isFrozen()||!"FROZEN".equals(packet.getPacketStatus()))
            throw new BusinessException(ErrorCode.CASE_STATUS_INVALID,"review packet is not frozen",Map.of("packet_id",packet.getId()));
        if(OffsetDateTime.now(ZoneOffset.UTC).isAfter(reviewDeadline(task,packet)))
            throw new BusinessException(ErrorCode.CASE_STATUS_INVALID,"review packet authorization has expired",Map.of("task_id",taskId));
        String policyVersion=pinnedPolicyDecision(task,targetEpoch(task.getCaseId())==null)
                .getPolicyVersion();
        Map<String,String> refs=Map.of(
                "case_summary",packet.getId()+":case-summary",
                "claims",packet.getId()+":claims",
                "issues",packet.getId()+":issues",
                "evidence_matrix",packet.getId()+":evidence-matrix",
                "adjudication_draft",packet.getId()+":draft",
                "remedy_plan",packet.getId()+":remedy",
                "risk_flags",packet.getId()+":risk-flags");
        return new ReviewPacketAuthorizationView(
                "review-packet-authorization.v1",task.getCaseId(),task.getId(),
                sha256("reviewer-authority:v1:"+actor.actorId()),packet.getId(),packet.getPacketVersion(),
                packetContentHash(packet),packet.getActionHash(),task.getTaskStatus().name(),policyVersion,
                task.getCreatedAt(),reviewDeadline(task,packet),roomEpoch,processRevision,fencingToken,refs);
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.decide(String,ReviewDecisionCommand,AuthenticatedActor)」。
    // 具体功能：「ReviewApplicationService.decide(String,ReviewDecisionCommand,AuthenticatedActor)」：先校验当前操作者具备最终决定权限，在事务内核对任务状态、packet 版本、action hash、过期时间和幂等键并落审批记录；提交后才触发执行/结案编排，最终返回「ReviewDecisionView」。
    // 上游调用：「ReviewApplicationService.decide(String,ReviewDecisionCommand,AuthenticatedActor)」的上游调用点包括 「CaseOutcomeService.confirmDraft」、「CaseOutcomeService.modifyDraft」、「ReviewController.decide」、「CaseOutcomeServiceTest.reviewerConfirmsLatestDraftByCaseReviewTask」。
    // 下游影响：「ReviewApplicationService.decide(String,ReviewDecisionCommand,AuthenticatedActor)」向下依次触达 「PlatformReviewerAuthorization.requireDecisionAccess」、「transactions.execute」、「postReviewOrchestration.orchestrate」、「result.approvalRecordId」；计算结果以「ReviewDecisionView」交给调用方。
    // 系统意义：「ReviewApplicationService.decide(String,ReviewDecisionCommand,AuthenticatedActor)」负责主链路中的“审核决定”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    public ReviewDecisionView decide(String taskId,ReviewDecisionCommand command,AuthenticatedActor actor){
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        ReviewTaskEntity task = taskRepository.findById(taskId).orElseThrow(()->notFound("review task",taskId));
        String durableAuthority=durableDecisionAuthority(task.getDecisionJson());
        if(TARGET_DECISION_AUTHORITY.equals(durableAuthority)) {
            return transactions.execute(ignored->persistDecision(
                    taskId,command,actor,null,TARGET_DECISION_AUTHORITY));
        }
        if(TRUSTED_DECISION_AUTHORITY.equals(durableAuthority)) {
            throw new IdempotencyConflictException(
                    "a trusted Outcome decision cannot be replayed through the legacy endpoint");
        }
        if (targetEpoch(task.getCaseId()) != null) {
            return transactions.execute(ignored -> decideTarget(taskId, command, actor));
        }
        // 审批事实先在事务内完成版本、哈希和幂等校验并提交。
        // 工具执行与结案属于事务后编排，不能在持有 ReviewTask 行锁时调用外部系统。
        ReviewDecisionView result=transactions.execute(ignored->persistDecision(
                taskId,command,actor,null,LEGACY_DECISION_AUTHORITY));
        if (result.executionAllowed()) {
            postReviewOrchestration.orchestrate(
                    result.approvalRecordId(), actor, command.idempotencyKey());
        }
        return result;
    }

    private ReviewDecisionView decideTarget(
            String taskId,
            ReviewDecisionCommand command,
            AuthenticatedActor actor) {
        ReviewTaskEntity initialTask = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> notFound("review task", taskId));
        TargetReviewRoute target = targetRoute(initialTask.getCaseId());
        ReviewDecisionView decision = persistDecision(
                taskId, command, actor, null, TARGET_DECISION_AUTHORITY);
        ReviewTaskEntity task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> notFound("review task", taskId));
        ReviewPacketEntity packet = packetRepository.findById(task.getPacketId())
                .orElseThrow(() -> notFound("review packet", task.getPacketId()));
        ApprovalRecordEntity record = approvalRepository.findById(decision.approvalRecordId())
                .orElseThrow(() -> notFound("approval record", decision.approvalRecordId()));

        String commandId = targetCommandId(taskId, command.idempotencyKey());
        Map<String, Object> frozenDecision = frozenTargetDecision(record, task, packet, target, commandId);
        JsonNode frozenNode = objectMapper.valueToTree(frozenDecision);
        String frozenHash = ContractJson.sha256Hex(frozenNode);
        var event = caseEventService.recordLifecycleEvent(
                task.getCaseId(), target.epoch().getRoomId(), "TARGET_REVIEW_DECISION_COMMITTED",
                frozenDecision, "target-review-decision:" + record.getId(), actor.actorId());
        PayloadRef payloadRef = new PayloadRef(
                "target-e2e-review-human-decision-event.v1",
                "urn:target-e2e:review-decision:" + event.getId(),
                frozenHash,
                ContractJson.canonicalize(frozenNode).length);

        TargetReviewHumanDecisionReceipt receipt = targetReceipt(
                record, task, packet, decision, command, target, frozenHash, event.getSequenceNo());
        required(targetHandoffWriter, "target Review handoff writer")
                .record(target.grant(), target.epoch(), commandId, receipt, record, task, packet);

        AcceptCaseCommand caseCommand = new AcceptCaseCommand(
                CommandType.REVIEW_DECISION, RoomType.REVIEW, target.epoch().getRoomEpoch(), payloadRef,
                target.epoch().getProcessRevision(), reviewDeadline(task, packet).toInstant());
        String traceId = targetTraceId(frozenHash);
        CaseCommandService commands = required(caseCommandService, "target Review case command service");
        commands.accept(task.getCaseId(), commandId, caseCommand, actor, traceId,
                "review:" + commandId, null);
        commands.flushAcceptanceForMaterialization();
        TargetRoomCommandIngress ingress = exactTargetIngress();
        ingress.materialize(task.getCaseId(), commandId, caseCommand, actor, traceId);
        return decision;
    }

    private TargetReviewRoute targetRoute(String caseId) {
        CaseRoomEpochEntity epoch = targetEpoch(caseId);
        if (epoch == null || targetAuthorityProvider == null) {
            throw new IllegalStateException("target Review activation authority is unavailable");
        }
        List<TargetRoomEpochSelectionAuthority> authorities = targetAuthorityProvider.stream().toList();
        if (authorities.size() != 1) {
            throw new IllegalStateException("target Review requires exactly one activation authority");
        }
        var request = new TargetRoomEpochSelectionAuthority.Request(
                TargetRoomEpochSelectionAuthority.PROFILE,
                TargetRoomEpochSelectionAuthority.EXECUTION_LANE,
                epoch.getTenantSurrogate(), caseId, RoomType.REVIEW,
                TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC);
        var grant = authorities.getFirst().authorize(request)
                .orElseThrow(() -> new IllegalStateException("target Review activation authority rejected command"));
        if (!epoch.getTenantSurrogate().equals(grant.request().tenantSurrogate())
                || !epoch.getCaseId().equals(grant.request().caseId())
                || !epoch.getTemporalBuildId().equals(grant.roomWorkflowBuildId())
                || !epoch.getGraphVersion().equals(grant.graphVersion())
                || !epoch.getCheckpointSchemaVersion().equals(grant.checkpointSchemaVersion())) {
            throw new IllegalStateException("target Review activation grant differs from the active epoch");
        }
        return new TargetReviewRoute(epoch, grant);
    }

    private CaseRoomEpochEntity targetEpoch(String caseId) {
        if (roomEpochRepository == null) return null;
        CaseRoomEpochEntity epoch = roomEpochRepository
                .findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                        caseId, RoomType.REVIEW, EpochLifecycleStatus.ACTIVE)
                .orElse(null);
        if (epoch == null
                || epoch.getWriterMode() != WriterMode.TEMPORAL
                || epoch.getProvisioningStatus() != EpochProvisioningStatus.READY
                || !TargetTypedRoomProtocol.GRAPH_KEY.equals(epoch.getGraphKey())) {
            return null;
        }
        return epoch;
    }

    private TargetRoomCommandIngress exactTargetIngress() {
        if (targetIngressProvider == null) {
            throw new IllegalStateException("target Review command ingress is unavailable");
        }
        List<TargetRoomCommandIngress> ingresses = targetIngressProvider.stream().toList();
        if (ingresses.size() != 1) {
            throw new IllegalStateException("target Review requires exactly one command ingress");
        }
        return ingresses.getFirst();
    }

    private Map<String, Object> frozenTargetDecision(
            ApprovalRecordEntity record,
            ReviewTaskEntity task,
            ReviewPacketEntity packet,
            TargetReviewRoute target,
            String commandId) {
        TargetReviewFrozenExecutionContract execution =
                TargetReviewFrozenExecutionContract.fromFrozenPacket(
                        packet, objectMapper, target.epoch().getRoomRevision());
        Map<String, Object> value = new TreeMap<>();
        value.put("schema_version", "target-e2e-review-human-decision-event.v1");
        value.put("approval_record_id", record.getId());
        value.put("approval_hash", record.getApprovalHash());
        value.put("approved_plan", read(record.getApprovedPlanJson()));
        value.put("original_plan", read(record.getOriginalPlanJson()));
        value.put("case_id", task.getCaseId());
        value.put("command_id", commandId);
        value.put("decision", record.getDecisionType().name());
        value.put("decision_reason", record.getDecisionReason());
        value.put("fencing_token", target.epoch().getFencingToken());
        value.put("packet_content_hash", packetContentHash(packet));
        value.put("packet_id", packet.getId());
        value.put("packet_version", packet.getPacketVersion());
        value.put("case_process_revision", target.epoch().getProcessRevision());
        value.put("kernel_revision", execution.kernelRevision());
        value.put("decision_source_revision", execution.decisionSourceRevision());
        value.put("decision_revision", execution.decisionRevision());
        value.put("policy_decision_id", task.getPolicyDecisionId());
        value.put("policy_version", record.getPolicyVersion());
        value.put("recorded_at", record.getCreatedAt().toInstant().toString());
        value.put("request_hash", read(task.getDecisionJson()).path("request_hash").asText());
        value.put("review_task_id", task.getId());
        value.put("reviewer_id", record.getReviewerId());
        value.put("room_epoch", target.epoch().getRoomEpoch());
        value.put("frozen_action_snapshot_ref", execution.actionSnapshotRef());
        value.put("frozen_action_snapshot_hash", execution.actionSnapshotHash());
        value.put("required_operation_set_ref", execution.requiredOperationSetRef());
        value.put("required_operation_set_hash", execution.requiredOperationSetHash());
        value.put("required_operation_count", execution.requiredOperationCount());
        value.put("approved_action_snapshot_hash", record.getActionSnapshotHash());
        return Map.copyOf(value);
    }

    private TargetReviewHumanDecisionReceipt targetReceipt(
            ApprovalRecordEntity record,
            ReviewTaskEntity task,
            ReviewPacketEntity packet,
            ReviewDecisionView decision,
            ReviewDecisionCommand command,
            TargetReviewRoute target,
            String decisionRecordHash,
            long committedEventSequence) {
        TargetReviewFrozenExecutionContract execution =
                TargetReviewFrozenExecutionContract.fromFrozenPacket(
                        packet, objectMapper, target.epoch().getRoomRevision());
        boolean approved = decision.executionAllowed();
        String operationKeyHash = approved
                ? sha256("target-review-operation:" + task.getCaseId() + ":" + record.getId()
                        + ":" + record.getActionSnapshotHash())
                : null;
        var outcome = new com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt(
                com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt.SCHEMA_VERSION,
                target.epoch().getRoomTemporalWorkflowId(), task.getCaseId(), record.getId(), decisionRecordHash,
                task.getId(), "reviewer-authority:" + sha256("reviewer-authority:v1:" + record.getReviewerId()),
                packet.getId(), packetContentHash(packet), execution.actionSnapshotRef(),
                execution.actionSnapshotHash(), approved ? "approval:" + record.getId() + ":action" : null,
                approved ? record.getActionSnapshotHash() : null, record.getId(), decisionRecordHash,
                "review-decision:" + record.getId() + ":reason", sha256(record.getDecisionReason()), operationKeyHash,
                execution.requiredOperationSetRef(), execution.requiredOperationSetHash(),
                execution.requiredOperationCount(),
                com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.ReviewDecision
                        .valueOf(record.getDecisionType().name()),
                approved, read(task.getDecisionJson()).path("request_hash").asText(),
                sha256(command.idempotencyKey()),
                record.getPolicyVersion(), target.epoch().getRoomEpoch(), execution.decisionSourceRevision(),
                execution.decisionRevision(),
                target.epoch().getFencingToken(), committedEventSequence, record.getCreatedAt().toInstant(), false);
        return new TargetReviewHumanDecisionReceipt(
                TargetReviewHumanDecisionReceipt.SCHEMA_VERSION,
                TargetReviewHumanDecisionReceipt.DECISION_AUTHORITY,
                record.getId(), decisionRecordHash, outcome);
    }

    private static String targetCommandId(String taskId, String idempotencyKey) {
        return "review-decision:" + sha256(taskId + "\n" + idempotencyKey);
    }

    private static String targetTraceId(String frozenHash) {
        return frozenHash.substring(0, 32);
    }

    private static <T> T required(T value, String name) {
        if (value == null) throw new IllegalStateException(name + " is unavailable");
        return value;
    }

    private record TargetReviewRoute(
            CaseRoomEpochEntity epoch, TargetRoomEpochSelectionAuthority.Grant grant) {}

    /**
     * Trusted non-HTTP entry for a server-minted Outcome context. This method emits a typed receipt
     * but does not start a Workflow, register a worker, allocate an epoch, or call a tool.
     */
    public ReviewDecisionView decideWithTrustedOutcomeContext(
            String taskId,
            ReviewDecisionCommand command,
            AuthenticatedActor actor,
            ReviewOutcomeReceiptContext outcomeContext) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        Objects.requireNonNull(outcomeContext,"outcomeContext");
        return transactions.execute(
                ignored->persistDecision(
                        taskId,command,actor,outcomeContext,TRUSTED_DECISION_AUTHORITY));
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.persistDecision(String,ReviewDecisionCommand,AuthenticatedActor)」。
    // 具体功能：「ReviewApplicationService.persistDecision(String,ReviewDecisionCommand,AuthenticatedActor)」：在任务行锁内处理决定幂等与冲突，执行 ApprovalPolicy，保存原方案/批准方案 diff、policy version 和 action hash，再按 APPROVE、补证、拒绝或人工升级推进案件与 ReviewTask，最终返回「ReviewDecisionView」。
    // 上游调用：「ReviewApplicationService.persistDecision(String,ReviewDecisionCommand,AuthenticatedActor)」的上游调用点包括 「ReviewApplicationService.decide」。
    // 下游影响：「ReviewApplicationService.persistDecision(String,ReviewDecisionCommand,AuthenticatedActor)」向下依次触达 「taskRepository.findByIdForUpdate」、「approvalRepository.findByApprovalHash」、「planRepository.findById」、「packetRepository.findById」；计算结果以「ReviewDecisionView」交给调用方。
    // 系统意义：「ReviewApplicationService.persistDecision(String,ReviewDecisionCommand,AuthenticatedActor)」负责主链路中的“决定”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private ReviewDecisionView persistDecision(
            String taskId,
            ReviewDecisionCommand command,
            AuthenticatedActor actor,
            ReviewOutcomeReceiptContext outcomeContext,
            String expectedAuthoritySource){
        ReviewTaskEntity task=taskRepository.findByIdForUpdate(taskId).orElseThrow(()->notFound("review task",taskId));
        ReviewPacketEntity packet =
                packetRepository
                        .findById(task.getPacketId())
                        .orElseThrow(() -> notFound("review packet", task.getPacketId()));
        if (!packet.isFrozen() || !"FROZEN".equals(packet.getPacketStatus())) {
            throw new BusinessException(ErrorCode.CASE_STATUS_INVALID,"review packet is not frozen",Map.of("packet_id",packet.getId()));
        }
        boolean allowLegacyLazyPin=LEGACY_DECISION_AUTHORITY.equals(expectedAuthoritySource);
        var policyDecision=pinnedPolicyDecision(task,allowLegacyLazyPin);
        String hash=sha256(taskId+":"+command.idempotencyKey());
        var existing=approvalRepository.findByApprovalHash(hash);
        JsonNode storedDecision=existing.isPresent()?read(task.getDecisionJson()):objectMapper.createObjectNode();
        if(existing.isPresent()
                && !expectedAuthoritySource.equals(
                        durableDecisionAuthority(storedDecision))) {
            throw new IdempotencyConflictException(
                    "durable review decision authority source drifted");
        }
        String packetContentHash=packetContentHash(packet);
        String effectivePolicyDecisionId=storedDecision.path("policy_decision_id").asText();
        String effectivePolicyVersion=storedDecision.path("policy_version").asText(policyDecision.getPolicyVersion());
        if((existing.isPresent()
                    && !policyDecision.getId().equals(effectivePolicyDecisionId))
                || !policyDecision.getPolicyVersion().equals(effectivePolicyVersion)
                || (existing.isPresent()
                    && !policyDecision.getPolicyVersion().equals(existing.get().getPolicyVersion())))
            throw new IdempotencyConflictException(
                    "durable review decision does not bind the exact task-pinned approval policy");
        String submittedTaskStatus=storedDecision.path("submitted_task_status").asText(task.getTaskStatus().name());
        ReviewPacketAuthorizationView trustedAuthorization=
                outcomeContext==null?null:outcomeContext.authorization();
        long outcomeEpoch=storedDecision.has("outcome_epoch")?storedDecision.path("outcome_epoch").asLong()
                :trustedAuthorization==null?0:trustedAuthorization.roomEpoch();
        long fencingToken=storedDecision.has("fencing_token")?storedDecision.path("fencing_token").asLong()
                :trustedAuthorization==null?0:trustedAuthorization.fencingToken();
        long processRevision=storedDecision.has("process_revision")?storedDecision.path("process_revision").asLong()
                :trustedAuthorization==null?0:trustedAuthorization.processRevision();
        String requestHash=decisionRequestHash(task,packet,packetContentHash,effectivePolicyVersion,
                submittedTaskStatus,actor,command,outcomeEpoch,fencingToken,processRevision,
                outcomeContext);
        validateTrustedOutcomeContext(outcomeContext,task,packet,packetContentHash,
                effectivePolicyVersion,submittedTaskStatus,actor,command,requestHash,
                outcomeEpoch,fencingToken,processRevision);
        if(existing.isPresent()) {
            assertSameIdempotentRequest(
                    existing.get(),task,command,actor,requestHash,
                    !LEGACY_DECISION_AUTHORITY.equals(expectedAuthoritySource));
            return decisionView(existing.get(),task,packetContentHash,packet.getActionHash(),requestHash,
                    outcomeEpoch,fencingToken,processRevision,outcomeContext!=null);
        }
        if(!isOpen(task.getTaskStatus())) throw new BusinessException(ErrorCode.CASE_STATUS_INVALID,"review task is not open",Map.of("status",task.getTaskStatus().name()));
        if(!Objects.equals(task.getAssignedReviewerId(),actor.actorId())) throw new ForbiddenException("only the assigned reviewer can submit this decision");
        OffsetDateTime deadline=reviewDeadline(task,packet);
        OffsetDateTime committedAt=OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        if(committedAt.isAfter(deadline)) throw new BusinessException(
                ErrorCode.CASE_STATUS_INVALID,"review decision deadline has expired",Map.of("deadline",deadline.toString()));

        JsonNode original=read(packet.getRemedyJson());
        JsonNode approved=command.approvedPlan();
        // APPROVE 必须精确采用冻结原方案；MODIFY_AND_APPROVE 可改批准快照，
        // 但仍要保留 plan id 和结构化 actions，不能提交自由文本工具命令。
        if(command.decision()==ApprovalDecisionType.MODIFY_AND_APPROVE && (approved==null||approved.isNull()||approved.isEmpty()))
            throw new IllegalArgumentException("approved_plan is required for modification");
        if(command.decision()==ApprovalDecisionType.APPROVE) approved=original;
        if(approved==null) approved=objectMapper.createObjectNode();
        if (command.decision() == ApprovalDecisionType.MODIFY_AND_APPROVE
                && (approved.path("id").asText().isBlank()
                        || !approved.path("actions").isArray())) {
            throw new IllegalArgumentException(
                    "modified approved_plan must retain plan id and actions");
        }
        if (command.decision() == ApprovalDecisionType.MODIFY_AND_APPROVE
                && (!Objects.equals(original.path("id").asText(), approved.path("id").asText())
                        || original.path("version").asInt(-1)
                                != approved.path("version").asInt(-2))) {
            throw new IllegalArgumentException(
                    "modified approved_plan cannot change frozen plan identity or version");
        }
        String actionSnapshotHash =
                isExecutable(command.decision()) ? actionHash(approved) : packet.getActionHash();
        if (command.decision() == ApprovalDecisionType.MODIFY_AND_APPROVE
                && (original.equals(approved)
                        || packet.getActionHash().equals(actionSnapshotHash))) {
            throw new IllegalArgumentException(
                    "MODIFY_AND_APPROVE requires a real action diff and a new action hash");
        }
        Map<String,Object> decisionFact=new TreeMap<>();
        decisionFact.put("approved_action_hash",actionSnapshotHash);
        decisionFact.put("approved_plan",approved);
        decisionFact.put("authority_source",expectedAuthoritySource);
        decisionFact.put("confirmed",true);
        decisionFact.put("decision",command.decision().name());
        decisionFact.put("fencing_token",fencingToken);
        decisionFact.put("original_plan",original);
        decisionFact.put("outcome_epoch",outcomeEpoch);
        decisionFact.put("packet_content_hash",packetContentHash);
        decisionFact.put("policy_decision_id",policyDecision.getId());
        decisionFact.put("policy_version",policyDecision.getPolicyVersion());
        decisionFact.put("process_revision",processRevision);
        decisionFact.put("reason",command.reason());
        decisionFact.put("request_hash",requestHash);
        decisionFact.put("submitted_task_status",submittedTaskStatus);
        String decisionJson=write(decisionFact);
        task.decide(command.decision(),actor.actorId(),decisionJson);taskRepository.save(task);
        FulfillmentCaseEntity disputeCase=caseRepository.findByIdForUpdate(task.getCaseId()).orElseThrow(()->notFound("case",task.getCaseId()));
        disputeCase.applyReviewOutcome(command.decision(),actor.actorId());caseRepository.save(disputeCase);
        // 对“原样批准”再次核对 ReviewPacket 冻结哈希，防止审核等待期间计划被后台任务替换。
        if (command.decision() == ApprovalDecisionType.APPROVE
                && !packet.getActionHash().equals(actionSnapshotHash)) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "frozen review packet action hash does not match approved plan",
                    Map.of("packet_id", packet.getId()));
        }
        ApprovalRecordEntity record=approvalRepository.save(ApprovalRecordEntity.recordFrozen(
                "APPROVAL_"+id(),task.getCaseId(),taskId,task.getPlanId(),actor.actorId(),actor.role().name(),
                command.decision(),original.toString(),approved.toString(),command.reason(),hash,
                packet.getId(),packet.getPacketVersion(),policyDecision.getPolicyVersion(),
                actionSnapshotHash,packet.getExpiresAt(),committedAt));
        auditRecorder.record(actor,"REVIEW_DECIDED","REVIEW_TASK",taskId,task.getCaseId(),
                Map.of("task_status",submittedTaskStatus,"plan",original),Map.of("task_status",task.getTaskStatus().name(),"approved_plan",approved));
        switch (command.decision()) {
            case APPROVE, MODIFY_AND_APPROVE ->
                    lifecycleNotifications.finalDecision(
                            disputeCase, command.decision().name());
            case REQUEST_MORE_EVIDENCE ->
                    lifecycleNotifications.supplementRequested(
                            disputeCase, "review-" + taskId);
            case REJECT, ESCALATE_MANUAL ->
                    lifecycleNotifications.manualHandoff(
                            disputeCase, command.decision().name());
        }
        return decisionView(record,task,packetContentHash,packet.getActionHash(),requestHash,
                outcomeEpoch,fencingToken,processRevision,outcomeContext!=null);
    }

    private ApprovalPolicyDecisionEntity pinnedPolicyDecision(
            ReviewTaskEntity task,
            boolean allowLegacyLazyPin) {
        String policyDecisionId=task.getPolicyDecisionId();
        if(policyDecisionId==null||policyDecisionId.isBlank()) {
            if(!allowLegacyLazyPin) {
                throw new BusinessException(
                        ErrorCode.CASE_STATUS_INVALID,
                        "target review task has no pinned approval policy decision",
                        Map.of("task_id",task.getId(),"plan_id",task.getPlanId()));
            }
            ApprovalPolicyDecisionEntity historical=policyDecisionRepository
                    .findFirstByCaseIdAndPlanIdAndCreatedAtLessThanEqualOrderByCreatedAtDescIdDesc(
                            task.getCaseId(),task.getPlanId(),task.getCreatedAt())
                    .orElseThrow(()->new BusinessException(
                            ErrorCode.CASE_STATUS_INVALID,
                            "legacy review task has no deterministic approval policy decision",
                            Map.of("task_id",task.getId(),"plan_id",task.getPlanId())));
            task.pinPolicyDecision(historical.getId());
            taskRepository.save(task);
            return historical;
        }
        return policyDecisionRepository
                .findByIdAndCaseIdAndPlanId(
                        policyDecisionId,task.getCaseId(),task.getPlanId())
                .orElseThrow(()->new BusinessException(
                        ErrorCode.CASE_STATUS_INVALID,
                        "pinned approval policy decision does not bind the review task",
                        Map.of("task_id",task.getId(),"plan_id",task.getPlanId())));
    }

    static String durableDecisionAuthority(String decisionJson) {
        if(decisionJson==null||decisionJson.isBlank()||"{}".equals(decisionJson.trim()))
            return LEGACY_DECISION_AUTHORITY;
        try {
            JsonNode decision=new ObjectMapper().readTree(decisionJson);
            return durableDecisionAuthority(decision);
        } catch (JsonProcessingException failure) {
            throw new IdempotencyConflictException("durable review decision authority is invalid");
        }
    }

    private static String durableDecisionAuthority(JsonNode decision) {
        String source=decision.path("authority_source").asText(LEGACY_DECISION_AUTHORITY);
        if(!LEGACY_DECISION_AUTHORITY.equals(source)
                && !TRUSTED_DECISION_AUTHORITY.equals(source)
                && !TARGET_DECISION_AUTHORITY.equals(source)) {
            throw new IdempotencyConflictException("durable review decision authority is invalid");
        }
        return source;
    }

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.decisionView(ApprovalRecordEntity,ReviewTaskEntity)」。
    // 具体功能：「ReviewApplicationService.decisionView(ApprovalRecordEntity,ReviewTaskEntity)」：构建决定视图；实际协作者为 「record.getDecisionType」、「record.getId」、「task.getId」、「task.getCaseId」；处理的关键状态/协议值包括 「APPROVED_FOR_EXECUTION」、「WAITING_EVIDENCE」、「MANUAL_HANDOFF」，最终返回「ReviewDecisionView」。
    // 上游调用：「ReviewApplicationService.decisionView(ApprovalRecordEntity,ReviewTaskEntity)」的上游调用点包括 「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewApplicationService.decisionView(ApprovalRecordEntity,ReviewTaskEntity)」向下依次触达 「record.getDecisionType」、「record.getId」、「task.getId」、「task.getCaseId」；计算结果以「ReviewDecisionView」交给调用方。
    // 系统意义：「ReviewApplicationService.decisionView(ApprovalRecordEntity,ReviewTaskEntity)」负责主链路中的“决定视图”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private ReviewDecisionView decisionView(
            ApprovalRecordEntity record,
            ReviewTaskEntity task,
            String packetContentHash,
            String frozenActionHash,
            String requestHash,
            long outcomeEpoch,
            long fencingToken,
            long processRevision,
            boolean exposeOutcomeReceipt){
        boolean allowed=record.getDecisionType()==ApprovalDecisionType.APPROVE||record.getDecisionType()==ApprovalDecisionType.MODIFY_AND_APPROVE;
        String status=allowed?"APPROVED_FOR_EXECUTION":record.getDecisionType()==ApprovalDecisionType.REQUEST_MORE_EVIDENCE?"WAITING_EVIDENCE":"MANUAL_HANDOFF";
        ReviewDecisionReceiptView receipt=exposeOutcomeReceipt
                ?ReviewDecisionReceiptView.mint(
                        "review-decision-receipt.v1",record.getId(),"HUMAN_DECISION",task.getId(),task.getCaseId(),
                        record.getReviewPacketId(),record.getReviewPacketVersion(),packetContentHash,
                        record.getDecisionType().name(),record.getReviewerId(),record.getPolicyVersion(),requestHash,
                        frozenActionHash,allowed?record.getActionSnapshotHash():null,
                        outcomeEpoch,fencingToken,processRevision,allowed,false,record.getCreatedAt())
                :null;
        return new ReviewDecisionView(record.getId(),task.getId(),task.getCaseId(),record.getDecisionType().name(),task.getTaskStatus().name(),status,allowed,receipt);
    }
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.assertSameIdempotentRequest(ApprovalRecordEntity,ReviewDecisionCommand,AuthenticatedActor)」。
    // 具体功能：「ReviewApplicationService.assertSameIdempotentRequest(ApprovalRecordEntity,ReviewDecisionCommand,AuthenticatedActor)」：复核相同审批幂等键的决定类型、理由、审核员和批准动作快照完全一致；任何差异都抛幂等冲突，最终返回「void」。
    // 上游调用：「ReviewApplicationService.assertSameIdempotentRequest(ApprovalRecordEntity,ReviewDecisionCommand,AuthenticatedActor)」的上游调用点包括 「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewApplicationService.assertSameIdempotentRequest(ApprovalRecordEntity,ReviewDecisionCommand,AuthenticatedActor)」向下依次触达 「record.getDecisionType」、「command.decision」、「record.getDecisionReason」、「command.reason」。
    // 系统意义：「ReviewApplicationService.assertSameIdempotentRequest(ApprovalRecordEntity,ReviewDecisionCommand,AuthenticatedActor)」在“相同Idempotent请求”进入下游前阻断非法状态；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private void assertSameIdempotentRequest(
            ApprovalRecordEntity record,
            ReviewTaskEntity task,
            ReviewDecisionCommand command,
            AuthenticatedActor actor,
            String requestHash,
            boolean requireDurableRequestHash) {
        boolean sameRequest =
                record.getDecisionType() == command.decision()
                        && Objects.equals(record.getDecisionReason(), command.reason())
                        && Objects.equals(record.getReviewerId(), actor.actorId())
                        && Objects.equals(record.getReviewTaskId(), task.getId())
                        && Objects.equals(record.getReviewPacketId(), task.getPacketId());
        if (sameRequest && command.decision() == ApprovalDecisionType.MODIFY_AND_APPROVE) {
            sameRequest =
                    command.approvedPlan() != null
                            && read(record.getApprovedPlanJson()).equals(command.approvedPlan());
        }
        JsonNode durableDecision=read(task.getDecisionJson());
        if(sameRequest&&(requireDurableRequestHash||durableDecision.hasNonNull("request_hash"))) {
            sameRequest=durableDecision.hasNonNull("request_hash")
                    && requestHash.equals(durableDecision.path("request_hash").asText());
        }
        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used with a different review decision");
        }
    }

    private String decisionRequestHash(
            ReviewTaskEntity task,
            ReviewPacketEntity packet,
            String packetContentHash,
            String policyVersion,
            String submittedTaskStatus,
            AuthenticatedActor actor,
            ReviewDecisionCommand command,
            long outcomeEpoch,
            long fencingToken,
            long processRevision,
            ReviewOutcomeReceiptContext outcomeContext) {
        Map<String,Object> request=new TreeMap<>();
        request.put("actor_id",actor.actorId());
        request.put("actor_role",actor.role().name());
        request.put("approved_plan",command.approvedPlan());
        request.put("case_id",task.getCaseId());
        request.put("confirmed",command.confirmed());
        request.put("deadline",reviewDeadline(task,packet).toInstant().toString());
        request.put("decision",command.decision().name());
        request.put("fencing_token",fencingToken);
        request.put("idempotency_key",command.idempotencyKey());
        request.put("outcome_epoch",outcomeEpoch);
        request.put("packet_action_hash",packet.getActionHash());
        request.put("packet_content_hash",packetContentHash);
        request.put("packet_id",packet.getId());
        request.put("packet_version",packet.getPacketVersion());
        request.put("policy_version",policyVersion);
        request.put("process_revision",processRevision);
        request.put("reason",command.reason());
        if(outcomeContext!=null)
            request.put("review_opened_at",task.getCreatedAt().toInstant().toString());
        request.put("task_id",task.getId());
        request.put("task_status",submittedTaskStatus);
        if(outcomeContext!=null)
            request.put("outcome_context",outcomeContext.canonicalRequestBinding());
        return ReviewPacketContentHasher.hash(objectMapper,request);
    }

    private void validateTrustedOutcomeContext(
            ReviewOutcomeReceiptContext context,
            ReviewTaskEntity task,
            ReviewPacketEntity packet,
            String packetContentHash,
            String policyVersion,
            String submittedTaskStatus,
            AuthenticatedActor actor,
            ReviewDecisionCommand command,
            String requestHash,
            long outcomeEpoch,
            long fencingToken,
            long processRevision) {
        if(context==null) return;
        ReviewPacketAuthorizationView expected=context.authorization();
        boolean approval=isExecutable(command.decision());
        boolean matches=context.syntheticOnly()
                && Objects.equals(expected.caseId(),task.getCaseId())
                && Objects.equals(expected.reviewTaskId(),task.getId())
                && Objects.equals(expected.reviewerAuthorityHash(),
                        sha256("reviewer-authority:v1:"+actor.actorId()))
                && Objects.equals(context.reviewerAuthorityRef(),
                        "reviewer-authority:"+expected.reviewerAuthorityHash())
                && Objects.equals(expected.packetId(),packet.getId())
                && expected.packetVersion()==packet.getPacketVersion()
                && Objects.equals(expected.packetContentHash(),packetContentHash)
                && Objects.equals(expected.actionHash(),packet.getActionHash())
                && Objects.equals(expected.policyVersion(),policyVersion)
                && Objects.equals(expected.taskStatus(),submittedTaskStatus)
                && expected.reviewOpenedAt().isEqual(task.getCreatedAt())
                && expected.deadline().isEqual(reviewDeadline(task,packet))
                && Objects.equals(context.requestHash(),requestHash)
                && Objects.equals(context.idempotencyKeyHash(),sha256(command.idempotencyKey()))
                && approval==(context.approvedActionSnapshotRef()!=null)
                && approval==(context.operationKeyHash()!=null)
                && expected.roomEpoch()==outcomeEpoch
                && expected.fencingToken()==fencingToken
                && expected.processRevision()==processRevision;
        if(!matches) throw new IdempotencyConflictException(
                "trusted Outcome review packet, actor, policy, task, deadline, or fence is stale");
    }

    private static OffsetDateTime reviewDeadline(ReviewTaskEntity task,ReviewPacketEntity packet) {
        if(task.getDueAt()==null) return packet.getExpiresAt();
        return task.getDueAt().isBefore(packet.getExpiresAt())?task.getDueAt():packet.getExpiresAt();
    }

    private static boolean isExecutable(ApprovalDecisionType decision) {
        return decision==ApprovalDecisionType.APPROVE
                || decision==ApprovalDecisionType.MODIFY_AND_APPROVE;
    }

    private String packetContentHash(ReviewPacketEntity packet) {
        Map<String,Object> content=new TreeMap<>();
        content.put("action_hash",packet.getActionHash());
        content.put("adjudication_draft_version",packet.getAdjudicationDraftVersion());
        content.put("agent_run_refs",read(packet.getAgentRunRefsJson()));
        content.put("case_id",packet.getCaseId());
        content.put("case_summary",read(packet.getCaseSummaryJson()));
        content.put("case_version",packet.getCaseVersion());
        content.put("claims",read(packet.getClaimsJson()));
        content.put("deliberation_report_version",packet.getDeliberationReportVersion());
        content.put("dossier_version",packet.getDossierVersion());
        content.put("draft",read(packet.getDraftJson()));
        content.put("evidence_matrix",read(packet.getEvidenceMatrixJson()));
        content.put("expires_at",packet.getExpiresAt().toInstant().toString());
        content.put("frozen_at",packet.getFrozenAt().toInstant().toString());
        content.put("issue_version",packet.getIssueVersion());
        content.put("issues",read(packet.getIssuesJson()));
        content.put("packet_id",packet.getId());
        content.put("packet_status",packet.getPacketStatus());
        content.put("packet_version",packet.getPacketVersion());
        content.put("plan_id",packet.getPlanId());
        content.put("profile_version",packet.getProfileVersion());
        content.put("prompt_version",packet.getPromptVersion());
        content.put("remedy",read(packet.getRemedyJson()));
        content.put("remedy_plan_version",packet.getRemedyPlanVersion());
        content.put("risk_flags",read(packet.getRiskFlagsJson()));
        content.put("ruleset_version",packet.getRulesetVersion());
        content.put("skill_version",packet.getSkillVersion());
        return ReviewPacketContentHasher.hash(objectMapper,content);
    }
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.view(ReviewTaskEntity)」。
    // 具体功能：「ReviewApplicationService.view(ReviewTaskEntity)」：构建视图；实际协作者为 「task.getId」、「task.getCaseId」、「task.getPlanId」、「task.getPacketId」，最终返回「ReviewTaskView」。
    // 上游调用：「ReviewApplicationService.view(ReviewTaskEntity)」只由「ReviewApplicationService」内部流程使用，负责封装“视图”这一步校验、映射或状态转换。
    // 下游影响：「ReviewApplicationService.view(ReviewTaskEntity)」向下依次触达 「task.getId」、「task.getCaseId」、「task.getPlanId」、「task.getPacketId」；计算结果以「ReviewTaskView」交给调用方。
    // 系统意义：「ReviewApplicationService.view(ReviewTaskEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private ReviewTaskView view(ReviewTaskEntity task){return new ReviewTaskView(task.getId(),task.getCaseId(),task.getPlanId(),task.getPacketId(),task.getTaskStatus().name(),task.getPriority(),task.getRequiredRole(),task.getAssignedReviewerId(),task.getDueAt(),task.getCreatedAt());}
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.actionTypes(String)」。
    // 具体功能：「ReviewApplicationService.actionTypes(String)」：构建动作Types；实际协作者为 「read」、「node.path("action_type").asText」；处理的关键状态/协议值包括 「action_type」，最终返回「List<String>」。
    // 上游调用：「ReviewApplicationService.actionTypes(String)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」。
    // 下游影响：「ReviewApplicationService.actionTypes(String)」向下依次触达 「read」、「node.path("action_type").asText」；计算结果以「List<String>」交给调用方。
    // 系统意义：「ReviewApplicationService.actionTypes(String)」负责主链路中的“动作Types”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<String> actionTypes(String json){List<String> values=new ArrayList<>();read(json).forEach(node->values.add(node.path("action_type").asText()));return values;}

    private HearingFlowArtifactEntity requireV2Artifact(
            String caseId, HearingArtifactType artifactType) {
        return hearingArtifactRepository
                .findByCaseIdAndArtifactType(caseId, artifactType)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "incomplete hearing_flow.v2 decision chain"));
    }

    private static void validateV2DecisionChain(
            String caseId,
            HearingFlowArtifactEntity proposal,
            HearingFlowArtifactEntity report,
            HearingFlowArtifactEntity draft) {
        boolean sameDossier =
                caseId.equals(proposal.getCaseId())
                        && caseId.equals(report.getCaseId())
                        && caseId.equals(draft.getCaseId())
                        && proposal.getFlowInstanceId().equals(report.getFlowInstanceId())
                        && proposal.getFlowInstanceId().equals(draft.getFlowInstanceId())
                        && proposal.getTrialDossierId().equals(report.getTrialDossierId())
                        && proposal.getTrialDossierId().equals(draft.getTrialDossierId())
                        && proposal.getTrialDossierHash().equals(report.getTrialDossierHash())
                        && proposal.getTrialDossierHash().equals(draft.getTrialDossierHash());
        boolean parentChain =
                proposal.getId().equals(report.getProposalId())
                        && proposal.getContentHash().equals(report.getProposalContentHash())
                        && proposal.getId().equals(draft.getProposalId())
                        && proposal.getContentHash().equals(draft.getProposalContentHash())
                        && report.getId().equals(draft.getReportId())
                        && report.getContentHash().equals(draft.getReportContentHash());
        if (!sameDossier || !parentChain) {
            throw new IllegalStateException(
                    "review packet decision artifact chain is invalid");
        }
    }
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.read(String)」。
    // 具体功能：「ReviewApplicationService.read(String)」：读取JSON节点：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「ReviewApplicationService.read(String)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」、「ReviewApplicationService.packet」、「ReviewApplicationService.persistDecision」、「ReviewApplicationService.assertSameIdempotentRequest」。
    // 下游影响：「ReviewApplicationService.read(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「ReviewApplicationService.read(String)」统一“JSON节点”的跨层表示，避免不同入口产生不兼容字段；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private JsonNode read(String json){try{return objectMapper.readTree(json);}catch(JsonProcessingException e){throw new IllegalStateException("invalid review JSON",e);}}
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.write(Object)」。
    // 具体功能：「ReviewApplicationService.write(Object)」：写入字符串：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「ReviewApplicationService.write(Object)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」、「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewApplicationService.write(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「ReviewApplicationService.write(Object)」负责主链路中的“字符串”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private String write(Object value){try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("cannot serialize review JSON",e);}}
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.isOpen(ReviewTaskStatus)」。
    // 具体功能：「ReviewApplicationService.isOpen(ReviewTaskStatus)」：判断是否Open，最终返回「boolean」。
    // 上游调用：「ReviewApplicationService.isOpen(ReviewTaskStatus)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」、「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewApplicationService.isOpen(ReviewTaskStatus)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「ReviewApplicationService.isOpen(ReviewTaskStatus)」负责主链路中的“Open”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private static boolean isOpen(ReviewTaskStatus status){return status==ReviewTaskStatus.PENDING||status==ReviewTaskStatus.ASSIGNED||status==ReviewTaskStatus.IN_REVIEW;}

    private static OffsetDateTime nextBusinessDay(
            OffsetDateTime createdAt, int businessDays) {
        OffsetDateTime dueAt =
                createdAt
                        .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                        .toOffsetDateTime();
        int remaining = businessDays;
        while (remaining > 0) {
            dueAt = dueAt.plusDays(1);
            DayOfWeek day = dueAt.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return dueAt.withOffsetSameInstant(ZoneOffset.UTC);
    }
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.assertCanView(AuthenticatedActor)」。
    // 具体功能：「ReviewApplicationService.assertCanView(AuthenticatedActor)」：断言Can视图；实际协作者为 「actor.role」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「ReviewApplicationService.assertCanView(AuthenticatedActor)」的上游调用点包括 「ReviewApplicationService.list」、「ReviewApplicationService.packet」。
    // 下游影响：「ReviewApplicationService.assertCanView(AuthenticatedActor)」向下依次触达 「actor.role」。
    // 系统意义：「ReviewApplicationService.assertCanView(AuthenticatedActor)」在“Can视图”进入下游前阻断非法状态；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private static void assertCanView(AuthenticatedActor actor){if(actor.role()!=ActorRole.PLATFORM_REVIEWER&&actor.role()!=ActorRole.ADMIN&&actor.role()!=ActorRole.CUSTOMER_SERVICE)throw new ForbiddenException("review role is required");}
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.notFound(String,String)」。
    // 具体功能：「ReviewApplicationService.notFound(String,String)」：构建不Found；处理的关键状态/协议值包括 「id」，最终返回「NotFoundException」。
    // 上游调用：「ReviewApplicationService.notFound(String,String)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」、「ReviewApplicationService.packet」、「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewApplicationService.notFound(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「NotFoundException」交给调用方。
    // 系统意义：「ReviewApplicationService.notFound(String,String)」负责主链路中的“不Found”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private static NotFoundException notFound(String type,String id){return new NotFoundException(ErrorCode.CASE_NOT_FOUND,type+" not found",Map.of("id",id));}
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.id()」。
    // 具体功能：「ReviewApplicationService.id()」：构建标识；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「ReviewApplicationService.id()」的上游调用点包括 「ReviewApplicationService.createForWorkflow」、「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewApplicationService.id()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「ReviewApplicationService.id()」负责主链路中的“标识”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.sha256(String)」。
    // 具体功能：「ReviewApplicationService.sha256(String)」：计算 SHA-256：先计算稳定哈希以绑定审批快照；实际协作者为 「MessageDigest.getInstance」、「HexFormat.of().formatHex」、「MessageDigest.getInstance("SHA-256").digest」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「SHA-256」，最终返回「String」。
    // 上游调用：「ReviewApplicationService.sha256(String)」的上游调用点包括 「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewApplicationService.sha256(String)」向下依次触达 「MessageDigest.getInstance」、「HexFormat.of().formatHex」、「MessageDigest.getInstance("SHA-256").digest」；计算结果以「String」交给调用方。
    // 系统意义：「ReviewApplicationService.sha256(String)」负责主链路中的“256”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    // 所属模块：【平台人工终审 / 应用编排层】「ReviewApplicationService.actionHash(JsonNode)」。
    // 具体功能：「ReviewApplicationService.actionHash(JsonNode)」：构建动作哈希；实际协作者为 「ActionSnapshotHasher.hash」，最终返回「String」。
    // 上游调用：「ReviewApplicationService.actionHash(JsonNode)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」、「ReviewApplicationService.persistDecision」。
    // 下游影响：「ReviewApplicationService.actionHash(JsonNode)」向下依次触达 「ActionSnapshotHasher.hash」；计算结果以「String」交给调用方。
    // 系统意义：「ReviewApplicationService.actionHash(JsonNode)」负责主链路中的“动作哈希”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    private String actionHash(JsonNode plan) {
        return ActionSnapshotHasher.hash(objectMapper, plan);
    }
}

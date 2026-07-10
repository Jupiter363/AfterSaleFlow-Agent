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
import com.example.dispute.infrastructure.persistence.repository.DeliberationReportRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.review.domain.ApprovalPolicyDecision;
import com.example.dispute.review.domain.ApprovalPolicyEngine;
import com.example.dispute.review.domain.ApprovalPolicyInput;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.review.domain.ReviewPacketVersions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewApplicationService {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("temporal-worker", ActorRole.SYSTEM);
    private final FulfillmentCaseRepository caseRepository;
    private final RemedyPlanRepository planRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingStateRepository hearingRepository;
    private final EvidenceDossierRepository dossierRepository;
    private final ReviewPacketRepository packetRepository;
    private final ReviewTaskRepository taskRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final DeliberationReportRepository deliberationRepository;
    private final ApprovalPolicyDecisionRepository policyDecisionRepository;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final AuditRecorder auditRecorder;
    private final PostReviewOrchestrationService postReviewOrchestration;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final ApprovalPolicyEngine policyEngine;
    private final int reviewTimeoutHours;

    public ReviewApplicationService(
            FulfillmentCaseRepository caseRepository,
            RemedyPlanRepository planRepository,
            AdjudicationDraftRepository draftRepository,
            HearingStateRepository hearingRepository,
            EvidenceDossierRepository dossierRepository,
            ReviewPacketRepository packetRepository,
            ReviewTaskRepository taskRepository,
            ApprovalRecordRepository approvalRepository,
            DeliberationReportRepository deliberationRepository,
            ApprovalPolicyDecisionRepository policyDecisionRepository,
            CaseLifecycleNotificationService lifecycleNotifications,
            AuditRecorder auditRecorder,
            PostReviewOrchestrationService postReviewOrchestration,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            @Value("${app.approval.refund-threshold:500.00}") BigDecimal refundThreshold,
            @Value("${app.approval.reship-threshold:300.00}") BigDecimal reshipThreshold,
            @Value("${app.approval.review-timeout-hours:168}") int reviewTimeoutHours) {
        this.caseRepository=caseRepository; this.planRepository=planRepository;
        this.draftRepository=draftRepository; this.hearingRepository=hearingRepository;
        this.dossierRepository=dossierRepository;
        this.packetRepository=packetRepository; this.taskRepository=taskRepository;
        this.approvalRepository=approvalRepository;
        this.deliberationRepository=deliberationRepository;
        this.policyDecisionRepository=policyDecisionRepository;
        this.lifecycleNotifications=lifecycleNotifications;
        this.auditRecorder=auditRecorder;
        this.postReviewOrchestration=postReviewOrchestration; this.objectMapper=objectMapper;
        this.transactions=transactions;
        this.policyEngine=new ApprovalPolicyEngine(refundThreshold,reshipThreshold);
        this.reviewTimeoutHours=reviewTimeoutHours;
    }

    @Transactional
    public String createForWorkflow(String caseId,String planId){
        var existing=taskRepository.findFirstByCaseIdOrderByCreatedAtDesc(caseId);
        if(existing.isPresent() && isOpen(existing.get().getTaskStatus())) return existing.get().getId();
        FulfillmentCaseEntity disputeCase=caseRepository.findByIdForUpdate(caseId).orElseThrow(()->notFound("case",caseId));
        RemedyPlanEntity plan=planRepository.findById(planId).orElseThrow(()->notFound("remedy plan",planId));
        if(!caseId.equals(plan.getCaseId())) throw new BusinessException(ErrorCode.CASE_STATUS_INVALID,"plan does not belong to case",Map.of());
        List<String> actionTypes=actionTypes(plan.getActionsJson());
        boolean insufficient=hearingRepository.findByCaseId(caseId).map(state->state.isManualRequired()).orElse(false);
        ApprovalPolicyDecision policy=policyEngine.evaluate(new ApprovalPolicyInput(
                plan.getRiskLevel(),plan.getTotalAmount(),actionTypes,disputeCase.getDisputeType(),insufficient));
        policyDecisionRepository.save(
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
        int deliberationVersion =
                deliberationRepository
                        .findFirstByCaseIdOrderByReportVersionDesc(caseId)
                        .map(item -> item.getReportVersion())
                        .orElse(0);
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
                        deliberationVersion,
                        plan.getPlanVersion(),
                        "ruleset-current",
                        "hearing-v1",
                        "dispute-default-v1",
                        "presiding-judge-v1"),
                actionHash,
                frozenAt,
                frozenAt.plusHours(reviewTimeoutHours),
                write(
                        draft == null || draft.getCreatedByAgentRunId() == null
                                ? List.of()
                                : List.of(draft.getCreatedByAgentRunId())),
                write(Map.of("title",disputeCase.getTitle(),"description",disputeCase.getDescription(),
                        "route_type",disputeCase.getRouteType().name(),"risk_level",disputeCase.getRiskLevel().name())),
                disputeCase.getIntakeResultJson(),
                draft==null?"[]":draft.getFactFindingsJson(),
                draft==null?"[]":draft.getEvidenceAssessmentJson(),
                draft==null?"{}":write(Map.of(
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
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(reviewTimeoutHours),SYSTEM.actorId()));
        disputeCase.waitForHumanReview(SYSTEM.actorId()); caseRepository.save(disputeCase);
        auditRecorder.record(SYSTEM,"REVIEW_TASK_CREATED","REVIEW_TASK",task.getId(),caseId,Map.of(),
                Map.of("priority",policy.priority(),"required_approvals",policy.requiredApprovals(),"risk_flags",policy.riskFlags()));
        lifecycleNotifications.reviewPending(disputeCase, task.getId());
        return task.getId();
    }

    @Transactional(readOnly=true)
    public List<ReviewTaskView> list(ReviewTaskStatus status,AuthenticatedActor actor){
        assertCanView(actor);
        return taskRepository.findAllByTaskStatusOrderByCreatedAtAsc(status).stream().map(this::view).toList();
    }

    @Transactional(readOnly=true)
    public ReviewPacketView packet(String taskId,AuthenticatedActor actor){
        assertCanView(actor);
        ReviewTaskEntity task=taskRepository.findById(taskId).orElseThrow(()->notFound("review task",taskId));
        ReviewPacketEntity p=packetRepository.findById(task.getPacketId()).orElseThrow(()->notFound("review packet",task.getPacketId()));
        return new ReviewPacketView(p.getId(),p.getCaseId(),p.getPlanId(),p.getPacketVersion(),
                p.getCaseVersion(),p.getDossierVersion(),p.getIssueVersion(),
                p.getAdjudicationDraftVersion(),p.getDeliberationReportVersion(),
                p.getRemedyPlanVersion(),p.getRulesetVersion(),p.getPromptVersion(),
                p.getSkillVersion(),p.getProfileVersion(),p.getActionHash(),
                read(p.getAgentRunRefsJson()),
                p.getFrozenAt(),p.getExpiresAt(),
                read(p.getCaseSummaryJson()),read(p.getClaimsJson()),read(p.getIssuesJson()),
                read(p.getEvidenceMatrixJson()),read(p.getDraftJson()),read(p.getRemedyJson()),
                read(p.getRiskFlagsJson()),p.getPacketStatus());
    }

    public ReviewDecisionView decide(String taskId,ReviewDecisionCommand command,AuthenticatedActor actor){
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        ReviewDecisionView result=transactions.execute(ignored->persistDecision(taskId,command,actor));
        postReviewOrchestration.orchestrate(
                result.approvalRecordId(), actor, command.idempotencyKey());
        return result;
    }

    private ReviewDecisionView persistDecision(String taskId,ReviewDecisionCommand command,AuthenticatedActor actor){
        ReviewTaskEntity task=taskRepository.findByIdForUpdate(taskId).orElseThrow(()->notFound("review task",taskId));
        String hash=sha256(taskId+":"+command.idempotencyKey());
        var existing=approvalRepository.findByApprovalHash(hash);
        if(existing.isPresent()) {
            assertSameIdempotentRequest(existing.get(), command, actor);
            return decisionView(existing.get(),task);
        }
        if(!isOpen(task.getTaskStatus())) throw new BusinessException(ErrorCode.CASE_STATUS_INVALID,"review task is not open",Map.of("status",task.getTaskStatus().name()));
        RemedyPlanEntity plan=planRepository.findById(task.getPlanId()).orElseThrow(()->notFound("plan",task.getPlanId()));
        ReviewPacketEntity packet =
                packetRepository
                        .findById(task.getPacketId())
                        .orElseThrow(() -> notFound("review packet", task.getPacketId()));
        var policyDecision =
                policyDecisionRepository
                        .findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(
                                task.getCaseId(), task.getPlanId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.CASE_STATUS_INVALID,
                                                "approval policy decision is required",
                                                Map.of("plan_id", task.getPlanId())));
        JsonNode original=read(write(Map.of("id",plan.getId(),"version",plan.getPlanVersion(),"actions",read(plan.getActionsJson()),
                "preconditions",read(plan.getPreconditionsJson()),"notifications",read(plan.getNotificationPlanJson()))));
        JsonNode approved=command.approvedPlan();
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
        String decisionJson=write(Map.of("decision",command.decision().name(),"reason",command.reason(),
                "original_plan",original,"approved_plan",approved));
        task.decide(command.decision(),actor.actorId(),decisionJson);taskRepository.save(task);
        FulfillmentCaseEntity disputeCase=caseRepository.findByIdForUpdate(task.getCaseId()).orElseThrow(()->notFound("case",task.getCaseId()));
        disputeCase.applyReviewOutcome(command.decision(),actor.actorId());caseRepository.save(disputeCase);
        String actionSnapshotHash = actionHash(approved);
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
                actionSnapshotHash,packet.getExpiresAt()));
        auditRecorder.record(actor,"REVIEW_DECIDED","REVIEW_TASK",taskId,task.getCaseId(),
                Map.of("task_status","PENDING","plan",original),Map.of("task_status",task.getTaskStatus().name(),"approved_plan",approved));
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
        return decisionView(record,task);
    }

    private ReviewDecisionView decisionView(ApprovalRecordEntity record,ReviewTaskEntity task){
        boolean allowed=record.getDecisionType()==ApprovalDecisionType.APPROVE||record.getDecisionType()==ApprovalDecisionType.MODIFY_AND_APPROVE;
        String status=allowed?"APPROVED_FOR_EXECUTION":record.getDecisionType()==ApprovalDecisionType.REQUEST_MORE_EVIDENCE?"WAITING_EVIDENCE":"MANUAL_HANDOFF";
        return new ReviewDecisionView(record.getId(),task.getId(),task.getCaseId(),record.getDecisionType().name(),task.getTaskStatus().name(),status,allowed);
    }
    private void assertSameIdempotentRequest(
            ApprovalRecordEntity record,
            ReviewDecisionCommand command,
            AuthenticatedActor actor) {
        boolean sameRequest =
                record.getDecisionType() == command.decision()
                        && Objects.equals(record.getDecisionReason(), command.reason())
                        && Objects.equals(record.getReviewerId(), actor.actorId());
        if (sameRequest && command.decision() == ApprovalDecisionType.MODIFY_AND_APPROVE) {
            sameRequest =
                    command.approvedPlan() != null
                            && read(record.getApprovedPlanJson()).equals(command.approvedPlan());
        }
        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used with a different review decision");
        }
    }
    private ReviewTaskView view(ReviewTaskEntity task){return new ReviewTaskView(task.getId(),task.getCaseId(),task.getPlanId(),task.getPacketId(),task.getTaskStatus().name(),task.getPriority(),task.getRequiredRole(),task.getAssignedReviewerId(),task.getDueAt(),task.getCreatedAt());}
    private List<String> actionTypes(String json){List<String> values=new ArrayList<>();read(json).forEach(node->values.add(node.path("action_type").asText()));return values;}
    private JsonNode read(String json){try{return objectMapper.readTree(json);}catch(JsonProcessingException e){throw new IllegalStateException("invalid review JSON",e);}}
    private String write(Object value){try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("cannot serialize review JSON",e);}}
    private static boolean isOpen(ReviewTaskStatus status){return status==ReviewTaskStatus.PENDING||status==ReviewTaskStatus.ASSIGNED||status==ReviewTaskStatus.IN_REVIEW;}
    private static void assertCanView(AuthenticatedActor actor){if(actor.role()!=ActorRole.PLATFORM_REVIEWER&&actor.role()!=ActorRole.ADMIN&&actor.role()!=ActorRole.CUSTOMER_SERVICE)throw new ForbiddenException("review role is required");}
    private static NotFoundException notFound(String type,String id){return new NotFoundException(ErrorCode.CASE_NOT_FOUND,type+" not found",Map.of("id",id));}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String actionHash(JsonNode plan) {
        return ActionSnapshotHasher.hash(objectMapper, plan);
    }
}

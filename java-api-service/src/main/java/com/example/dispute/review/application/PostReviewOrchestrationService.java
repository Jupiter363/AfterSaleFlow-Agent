package com.example.dispute.review.application;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.room.application.CaseEventService;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PostReviewOrchestrationService {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("post-review-orchestrator", ActorRole.SYSTEM);
    private static final String EXECUTION_ASSISTANT_HANDOFF =
            "EXECUTION_ASSISTANT_HANDOFF";

    private final ApprovalRecordRepository approvalRepository;
    private final AuditRecorder auditRecorder;
    private final CaseEventService caseEventService;

    public PostReviewOrchestrationService(
            ApprovalRecordRepository approvalRepository,
            AuditRecorder auditRecorder,
            CaseEventService caseEventService) {
        this.approvalRepository = approvalRepository;
        this.auditRecorder = auditRecorder;
        this.caseEventService = caseEventService;
    }

    public PostReviewOrchestrationResult orchestrate(
            String approvalRecordId,
            AuthenticatedActor reviewer,
            String idempotencyKey) {
        ApprovalRecordEntity approval =
                approvalRepository
                        .findById(approvalRecordId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "approval record not found",
                                                Map.of("approval_record_id", approvalRecordId)));
        ApprovalDecisionType decision = approval.getDecisionType();
        if (!isExecutable(decision)) {
            String status =
                    decision == ApprovalDecisionType.REQUEST_MORE_EVIDENCE
                            ? "WAITING_EVIDENCE"
                            : "MANUAL_HANDOFF";
            audit("POST_REVIEW_NO_EXECUTION", approval, reviewer, status, null);
            return new PostReviewOrchestrationResult(
                    approvalRecordId,
                    approval.getCaseId(),
                    status,
                    false,
                    false,
                    "review decision does not allow execution");
        }

        audit(
                "POST_REVIEW_EXECUTION_ASSISTANT_HANDOFF",
                approval,
                reviewer,
                EXECUTION_ASSISTANT_HANDOFF,
                null);
        caseEventService.recordLifecycleEvent(
                approval.getCaseId(),
                null,
                EXECUTION_ASSISTANT_HANDOFF,
                Map.of(
                        "approval_record_id",
                        approvalRecordId,
                        "decision",
                        decision.name(),
                        "status",
                        EXECUTION_ASSISTANT_HANDOFF,
                        "reviewer_id",
                        reviewer.actorId(),
                        "message",
                        "final decision confirmed and handed off to execution assistant"),
                "post-review-execution-assistant:" + approvalRecordId,
                reviewer.actorId());
        return new PostReviewOrchestrationResult(
                approvalRecordId,
                approval.getCaseId(),
                EXECUTION_ASSISTANT_HANDOFF,
                false,
                false,
                "final decision confirmed and handed off to execution assistant");
    }

    private void audit(
            String action,
            ApprovalRecordEntity approval,
            AuthenticatedActor reviewer,
            String status,
            String errorType) {
        auditRecorder.record(
                SYSTEM,
                action,
                "APPROVAL_RECORD",
                approval.getId(),
                approval.getCaseId(),
                Map.of("decision", approval.getDecisionType().name()),
                errorType == null
                        ? Map.of(
                                "status",
                                status,
                                "reviewer_id",
                                reviewer.actorId())
                        : Map.of(
                                "status",
                                status,
                                "reviewer_id",
                                reviewer.actorId(),
                                "error_type",
                                errorType));
    }

    private static boolean isExecutable(ApprovalDecisionType decision) {
        return decision == ApprovalDecisionType.APPROVE
                || decision == ApprovalDecisionType.MODIFY_AND_APPROVE;
    }
}

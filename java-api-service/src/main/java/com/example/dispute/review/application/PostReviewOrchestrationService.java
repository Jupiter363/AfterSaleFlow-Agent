package com.example.dispute.review.application;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PostReviewOrchestrationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PostReviewOrchestrationService.class);
    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("post-review-orchestrator", ActorRole.SYSTEM);

    private final ApprovalRecordRepository approvalRepository;
    private final ToolExecutorService executorService;
    private final CaseClosureService closureService;
    private final AuditRecorder auditRecorder;

    public PostReviewOrchestrationService(
            ApprovalRecordRepository approvalRepository,
            ToolExecutorService executorService,
            CaseClosureService closureService,
            AuditRecorder auditRecorder) {
        this.approvalRepository = approvalRepository;
        this.executorService = executorService;
        this.closureService = closureService;
        this.auditRecorder = auditRecorder;
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

        boolean executionAttempted = false;
        boolean closureAttempted = false;
        try {
            executionAttempted = true;
            executorService.executeApprovedActions(
                    approval.getCaseId(),
                    "POST_REVIEW_EXECUTE:" + approvalRecordId + ":" + idempotencyKey,
                    SYSTEM);
            closureAttempted = true;
            closureService.close(
                    approval.getCaseId(),
                    "POST_REVIEW_CLOSE:" + approvalRecordId + ":" + idempotencyKey,
                    SYSTEM,
                    "TRACE_POST_REVIEW_" + approval.getCaseId(),
                    "REQ_POST_REVIEW_" + approvalRecordId);
            audit("POST_REVIEW_ORCHESTRATION_COMPLETED", approval, reviewer, "CLOSED", null);
            return new PostReviewOrchestrationResult(
                    approvalRecordId,
                    approval.getCaseId(),
                    "CLOSED",
                    true,
                    true,
                    "approved actions executed and case closed");
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Post-review orchestration failed: approval_record_id={}, case_id={}",
                    approvalRecordId,
                    approval.getCaseId(),
                    exception);
            audit(
                    "POST_REVIEW_ORCHESTRATION_FAILED",
                    approval,
                    reviewer,
                    "FAILED",
                    exception.getClass().getSimpleName());
            return new PostReviewOrchestrationResult(
                    approvalRecordId,
                    approval.getCaseId(),
                    "FAILED",
                    executionAttempted,
                    closureAttempted,
                    exception.getMessage());
        }
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

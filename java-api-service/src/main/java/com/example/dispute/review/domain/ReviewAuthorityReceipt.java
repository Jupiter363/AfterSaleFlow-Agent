package com.example.dispute.review.domain;

import com.example.dispute.domain.model.ApprovalDecisionType;
import java.time.OffsetDateTime;

/** Immutable authority receipt. Persistence adapters store the receipt before publishing it. */
public record ReviewAuthorityReceipt(
        String receiptId,
        ReviewDecisionFactType factType,
        String taskId,
        String caseId,
        FrozenReviewPacketIdentity packet,
        ApprovalDecisionType decision,
        String reviewerId,
        String reason,
        String policyVersion,
        String idempotencyKey,
        String requestHash,
        long outcomeEpoch,
        long fencingToken,
        long processRevision,
        long eventSequence,
        String approvedActionHash,
        boolean operationEligible,
        OffsetDateTime recordedAt) {

    public ReviewAuthorityReceipt {
        requireText(receiptId, "receiptId");
        if (factType == null || decision == null || packet == null || recordedAt == null) {
            throw new IllegalArgumentException("factType, decision, packet, and recordedAt are required");
        }
        requireText(taskId, "taskId");
        requireText(caseId, "caseId");
        requireText(reason, "reason");
        requireText(policyVersion, "policyVersion");
        requireText(idempotencyKey, "idempotencyKey");
        requireHash(requestHash, "requestHash");
        requireText(approvedActionHash, "approvedActionHash");
        if (outcomeEpoch < 0 || fencingToken < 0 || processRevision < 0 || eventSequence < 1) {
            throw new IllegalArgumentException("epoch, fence, revision, and event sequence are invalid");
        }
        boolean approval =
                decision == ApprovalDecisionType.APPROVE
                        || decision == ApprovalDecisionType.MODIFY_AND_APPROVE;
        if (factType == ReviewDecisionFactType.HUMAN_DECISION) {
            requireText(reviewerId, "reviewerId");
        } else {
            if (decision != ApprovalDecisionType.ESCALATE_MANUAL) {
                throw new IllegalArgumentException("SLA escalation must have ESCALATE_MANUAL semantics");
            }
            if (reviewerId != null) {
                throw new IllegalArgumentException("SLA escalation cannot fabricate a reviewer");
            }
        }
        if (operationEligible != approval || factType == ReviewDecisionFactType.SYSTEM_SLA_ESCALATION && operationEligible) {
            throw new IllegalArgumentException("operation eligibility does not match the authority fact");
        }
    }

    public static ReviewAuthorityReceipt slaEscalation(
            String receiptId,
            String taskId,
            String caseId,
            FrozenReviewPacketIdentity packet,
            String reason,
            String policyVersion,
            String idempotencyKey,
            String requestHash,
            long outcomeEpoch,
            long fencingToken,
            long processRevision,
            long eventSequence,
            OffsetDateTime recordedAt) {
        return new ReviewAuthorityReceipt(
                receiptId,
                ReviewDecisionFactType.SYSTEM_SLA_ESCALATION,
                taskId,
                caseId,
                packet,
                ApprovalDecisionType.ESCALATE_MANUAL,
                null,
                reason,
                policyVersion,
                idempotencyKey,
                requestHash,
                outcomeEpoch,
                fencingToken,
                processRevision,
                eventSequence,
                packet.actionHash(),
                false,
                recordedAt);
    }

    private static void requireHash(String value, String name) {
        requireText(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}

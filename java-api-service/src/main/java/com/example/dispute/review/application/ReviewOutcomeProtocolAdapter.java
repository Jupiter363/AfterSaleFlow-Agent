package com.example.dispute.review.application;

import com.example.dispute.review.domain.ReviewAuthorityReceipt;
import com.example.dispute.review.domain.ReviewDecisionFactType;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeSlaEscalationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Pure wire adapter. It does not start a Workflow, allocate an epoch, or invoke a tool. */
public final class ReviewOutcomeProtocolAdapter {

    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA_256=Pattern.compile("[0-9a-f]{64}");

    private ReviewOutcomeProtocolAdapter() {}

    public static OutcomeReviewDecisionReceipt humanDecision(
            ReviewDecisionReceiptView receipt,
            ReviewOutcomeReceiptContext context) {
        if(receipt==null||context==null) throw new IllegalArgumentException("receipt and context are required");
        if(!"review-decision-receipt.v1".equals(receipt.schemaVersion()))
            throw new IllegalArgumentException("unsupported review decision receipt schema");
        if(!"HUMAN_DECISION".equals(receipt.factType()))
            throw new IllegalArgumentException("only a human decision can map to an Outcome decision receipt");
        if(!context.syntheticOnly())
            throw new IllegalArgumentException("Phase 7 trusted receipts must remain synthetic-only");
        requireIdentifier(receipt.receiptId(),"receiptId");
        requireIdentifier(receipt.taskId(),"taskId");
        requireIdentifier(receipt.caseId(),"caseId");
        requireIdentifier(receipt.packetId(),"packetId");
        if(receipt.packetVersion()<1)
            throw new IllegalArgumentException("packetVersion must be positive");
        requireHash(receipt.packetContentHash(),"packetContentHash");
        requireIdentifier(receipt.reviewerId(),"reviewerId");
        requireIdentifier(receipt.policyVersion(),"policyVersion");
        requireHash(receipt.requestHash(),"requestHash");
        requireHash(receipt.frozenActionHash(),"frozenActionHash");
        if(receipt.recordedAt()==null)
            throw new IllegalArgumentException("receipt recordedAt is required");
        OutcomeWireTypes.ReviewDecision decision=reviewDecision(receipt.decision());
        boolean approval=decision==OutcomeWireTypes.ReviewDecision.APPROVE
                || decision==OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE;
        if(receipt.operationEligible()!=approval)
            throw new IllegalArgumentException("operation eligibility does not match the decision");
        if(receipt.operationRequestEmitted())
            throw new IllegalArgumentException("review receipt cannot pre-emit an operation request");
        if(approval!=(receipt.approvedActionHash()!=null))
            throw new IllegalArgumentException("approved action hash does not match approval semantics");
        if(approval) requireHash(receipt.approvedActionHash(),"approvedActionHash");
        if(decision==OutcomeWireTypes.ReviewDecision.APPROVE
                && !receipt.frozenActionHash().equals(receipt.approvedActionHash()))
            throw new IllegalArgumentException("APPROVE must retain the frozen action hash");
        if(decision==OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE
                && receipt.frozenActionHash().equals(receipt.approvedActionHash()))
            throw new IllegalArgumentException("MODIFY_AND_APPROVE must carry a changed action hash");
        ReviewPacketAuthorizationView authorization=context.authorization();
        boolean authorityMatches=receipt.caseId().equals(authorization.caseId())
                && receipt.taskId().equals(authorization.reviewTaskId())
                && sha256("reviewer-authority:v1:"+receipt.reviewerId())
                        .equals(authorization.reviewerAuthorityHash())
                && receipt.packetId().equals(authorization.packetId())
                && receipt.packetVersion()==authorization.packetVersion()
                && receipt.packetContentHash().equals(authorization.packetContentHash())
                && receipt.frozenActionHash().equals(authorization.actionHash())
                && receipt.policyVersion().equals(authorization.policyVersion())
                && receipt.outcomeEpoch()==authorization.roomEpoch()
                && receipt.fencingToken()==authorization.fencingToken()
                && receipt.processRevision()==authorization.processRevision()
                && receipt.requestHash().equals(context.requestHash())
                && context.reviewerAuthorityRef().equals(
                        "reviewer-authority:"+authorization.reviewerAuthorityHash());
        if(!authorityMatches)
            throw new IllegalArgumentException("review receipt does not match server-side authorization");
        if(approval!=(context.approvedActionSnapshotRef()!=null)
                || approval!=(context.operationKeyHash()!=null))
            throw new IllegalArgumentException(
                    "trusted Outcome context does not match the decision execution semantics");
        if(!approval&&context.requiredOperationCount()!=0)
            throw new IllegalArgumentException("nonexecution decisions require an empty operation set");
        if(!receipt.hasValidAuthoritySeal())
            throw new IllegalArgumentException("review receipt was not minted by the decision transaction");
        return new OutcomeReviewDecisionReceipt(
                OutcomeReviewDecisionReceipt.SCHEMA_VERSION,
                context.workflowId(),
                receipt.caseId(),
                receipt.receiptId(),
                context.receiptHash(),
                receipt.taskId(),
                context.reviewerAuthorityRef(),
                receipt.packetId(),
                receipt.packetContentHash(),
                context.actionSnapshotRef(),
                receipt.frozenActionHash(),
                approval?context.approvedActionSnapshotRef():null,
                approval?receipt.approvedActionHash():null,
                receipt.receiptId(),
                context.decisionRecordHash(),
                context.reasonRef(),
                context.reasonHash(),
                approval?context.operationKeyHash():null,
                context.requiredOperationSetRef(),
                context.requiredOperationSetHash(),
                context.requiredOperationCount(),
                decision,
                approval,
                receipt.requestHash(),
                context.idempotencyKeyHash(),
                receipt.policyVersion(),
                receipt.outcomeEpoch(),
                context.sourceRevision(),
                receipt.processRevision(),
                receipt.fencingToken(),
                context.committedEventSequence(),
                requiredInstant(receipt.recordedAt()),
                context.syntheticOnly());
    }

    public static OutcomeSlaEscalationReceipt slaEscalation(
            ReviewAuthorityReceipt receipt,
            String workflowId,
            String receiptHash,
            Instant deadlineAt,
            long sourceRevision,
            boolean syntheticOnly) {
        if(receipt==null||receipt.factType()!=ReviewDecisionFactType.SYSTEM_SLA_ESCALATION)
            throw new IllegalArgumentException("a SYSTEM_SLA_ESCALATION receipt is required");
        if(!syntheticOnly)
            throw new IllegalArgumentException("Phase 7 SLA receipts must remain synthetic-only");
        return new OutcomeSlaEscalationReceipt(
                OutcomeSlaEscalationReceipt.SCHEMA_VERSION,
                workflowId,
                receipt.caseId(),
                receipt.receiptId(),
                receiptHash,
                receipt.taskId(),
                receipt.packet().packetId(),
                receipt.packet().contentHash(),
                OutcomeWireTypes.SlaFactType.SYSTEM_SLA_ESCALATION,
                OutcomeWireTypes.ActorType.SYSTEM,
                deadlineAt,
                receipt.recordedAt().toInstant(),
                receipt.outcomeEpoch(),
                sourceRevision,
                receipt.processRevision(),
                receipt.fencingToken(),
                receipt.eventSequence(),
                false,
                false,
                syntheticOnly);
    }

    private static Instant requiredInstant(java.time.OffsetDateTime value) {
        if(value==null) throw new IllegalArgumentException("receipt recordedAt is required");
        return value.toInstant();
    }

    private static OutcomeWireTypes.ReviewDecision reviewDecision(String value) {
        try {
            return OutcomeWireTypes.ReviewDecision.valueOf(value);
        } catch(RuntimeException exception) {
            throw new IllegalArgumentException("review receipt decision is invalid",exception);
        }
    }

    private static void requireHash(String value,String name) {
        if(value==null||!SHA_256.matcher(value).matches())
            throw new IllegalArgumentException(name+" must be a lowercase SHA-256");
    }

    private static void requireIdentifier(String value,String name) {
        if(value==null||!IDENTIFIER.matcher(value).matches())
            throw new IllegalArgumentException(name+" must be a bounded identifier");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

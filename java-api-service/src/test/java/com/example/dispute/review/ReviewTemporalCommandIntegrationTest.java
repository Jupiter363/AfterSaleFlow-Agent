package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.review.application.ReviewDecisionReceiptView;
import com.example.dispute.review.application.ReviewOutcomeProtocolAdapter;
import com.example.dispute.review.application.ReviewOutcomeReceiptContext;
import com.example.dispute.review.application.ReviewPacketAuthorizationView;
import com.example.dispute.review.domain.FrozenReviewPacketIdentity;
import com.example.dispute.review.domain.ReviewAuthorityReceipt;
import com.example.dispute.review.domain.ReviewDecisionConflictException;
import com.example.dispute.review.domain.ReviewDecisionFactType;
import com.example.dispute.review.domain.ReviewDecisionFence;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTemporalCommandIntegrationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 24, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final String PACKET_HASH = "a".repeat(64);
    private static final String FROZEN_ACTION_HASH = "b".repeat(64);
    private static final String APPROVED_ACTION_HASH = "c".repeat(64);
    private static final String REQUEST_HASH = "d".repeat(64);
    private static final FrozenReviewPacketIdentity PACKET =
            new FrozenReviewPacketIdentity(
                    "PACKET_1",
                    "CASE_1",
                    "PLAN_1",
                    2,
                    PACKET_HASH,
                    FROZEN_ACTION_HASH,
                    NOW.minusHours(1),
                    NOW.plusHours(1));

    @Test
    void actorBoundApprovalMapsToTypedReceiptWithOperationHandoff() {
        ReviewDecisionReceiptView receipt = decisionView(ApprovalDecisionType.APPROVE, true);

        var wire = ReviewOutcomeProtocolAdapter.humanDecision(receipt, context(true));

        assertThat(wire.decision()).isEqualTo(OutcomeWireTypes.ReviewDecision.APPROVE);
        assertThat(wire.reviewerAuthorityRef())
                .isEqualTo("reviewer-authority:" + reviewerAuthorityHash());
        assertThat(wire.frozenReviewPacketHash()).isEqualTo(PACKET_HASH);
        assertThat(wire.executionAuthorized()).isTrue();
        assertThat(wire.operationKeyHash()).isEqualTo("2".repeat(64));
        assertThat(wire.requiredOperationCount()).isEqualTo(1);
    }

    @Test
    void nonExecutionHumanDecisionMapsWithoutOperationHandoff() {
        ReviewDecisionReceiptView receipt =
                decisionView(ApprovalDecisionType.REQUEST_MORE_EVIDENCE, false);

        var wire = ReviewOutcomeProtocolAdapter.humanDecision(receipt, context(false));

        assertThat(wire.decision())
                .isEqualTo(OutcomeWireTypes.ReviewDecision.REQUEST_MORE_EVIDENCE);
        assertThat(wire.executionAuthorized()).isFalse();
        assertThat(wire.operationKeyHash()).isNull();
        assertThat(wire.approvedActionSnapshotRef()).isNull();
        assertThat(wire.requiredOperationCount()).isZero();
    }

    @Test
    void systemSlaMapsToDistinctTypedFactWithoutApprovalOrOperation() {
        ReviewAuthorityReceipt receipt =
                ReviewAuthorityReceipt.slaEscalation(
                        "SLA_1",
                        "TASK_1",
                        "CASE_1",
                        PACKET,
                        "review deadline elapsed",
                        "policy-v1",
                        "sla-key",
                        "e".repeat(64),
                        4,
                        7,
                        3,
                        4,
                        NOW);

        var wire =
                ReviewOutcomeProtocolAdapter.slaEscalation(
                        receipt,
                        "outcome:CASE_1:4",
                        "f".repeat(64),
                        NOW.toInstant(),
                        2,
                        true);

        assertThat(wire.factType()).isEqualTo(OutcomeWireTypes.SlaFactType.SYSTEM_SLA_ESCALATION);
        assertThat(wire.actor()).isEqualTo(OutcomeWireTypes.ActorType.SYSTEM);
        assertThat(wire.executionAuthorized()).isFalse();
        assertThat(wire.approvalRecordCreated()).isFalse();
    }

    @Test
    void actorHashEpochFenceAndRevisionSubstitutionConflictWithTheWinner() {
        ReviewAuthorityReceipt winner = humanAuthority("reviewer-local", REQUEST_HASH, 4, 7, 3);
        List<ReviewAuthorityReceipt> substitutions =
                List.of(
                        humanAuthority("reviewer-other", REQUEST_HASH, 4, 7, 3),
                        humanAuthority("reviewer-local", "9".repeat(64), 4, 7, 3),
                        humanAuthority("reviewer-local", REQUEST_HASH, 5, 7, 3),
                        humanAuthority("reviewer-local", REQUEST_HASH, 4, 8, 3),
                        humanAuthority("reviewer-local", REQUEST_HASH, 4, 7, 4));

        substitutions.forEach(
                candidate ->
                        assertThatThrownBy(
                                        () ->
                                                new ReviewDecisionFence(winner)
                                                        .record(candidate))
                                .isInstanceOf(ReviewDecisionConflictException.class));
    }

    @Test
    void trustedOutcomeContextRejectsEqualGapAndZeroSequenceOrders() {
        assertThatThrownBy(() -> context(true,3,4,3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly sourceRevision + 1");
        assertThatThrownBy(() -> context(true,1,4,3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly sourceRevision + 1");
        assertThatThrownBy(() -> context(true,2,0,3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("committedEventSequence must be positive");
    }

    @Test
    void delayedDeliveryPreservesTheDurableReviewOpenAndCommitTimes() {
        OffsetDateTime deadline=NOW.plusHours(1);
        OffsetDateTime deliveredAt=deadline.plusHours(4);

        var wire=ReviewOutcomeProtocolAdapter.humanDecision(
                decisionView(ApprovalDecisionType.APPROVE,true),context(true));

        assertThat(deliveredAt).isAfter(deadline);
        assertThat(wire.committedAt()).isEqualTo(NOW.toInstant());
    }

    private static ReviewDecisionReceiptView decisionView(
            ApprovalDecisionType decision, boolean approval) {
        return ReviewDecisionReceiptTestFixture.mint(
                "review-decision-receipt.v1",
                "RECEIPT_1",
                "HUMAN_DECISION",
                "TASK_1",
                "CASE_1",
                "PACKET_1",
                2,
                PACKET_HASH,
                decision.name(),
                "reviewer-local",
                "policy-v1",
                REQUEST_HASH,
                FROZEN_ACTION_HASH,
                approval ? FROZEN_ACTION_HASH : null,
                4,
                7,
                3,
                approval,
                false,
                NOW);
    }

    private static ReviewOutcomeReceiptContext context(boolean approval) {
        return context(approval,2,4,3);
    }

    private static ReviewOutcomeReceiptContext context(
            boolean approval,
            long sourceRevision,
            long committedEventSequence,
            long processRevision) {
        return new ReviewOutcomeReceiptContext(
                "outcome:CASE_1:4",
                "0".repeat(64),
                REQUEST_HASH,
                "reviewer-authority:" + reviewerAuthorityHash(),
                "action-snapshot:original",
                approval ? "action-snapshot:approved" : null,
                "3".repeat(64),
                "reason:RECEIPT_1",
                "4".repeat(64),
                approval ? "2".repeat(64) : null,
                "operations:RECEIPT_1",
                "5".repeat(64),
                approval ? 1 : 0,
                "6".repeat(64),
                sourceRevision,
                committedEventSequence,
                true,
                new ReviewPacketAuthorizationView(
                        "review-packet-authorization.v1",
                        "CASE_1",
                        "TASK_1",
                        reviewerAuthorityHash(),
                        "PACKET_1",
                        2,
                        PACKET_HASH,
                        FROZEN_ACTION_HASH,
                        "PENDING",
                        "policy-v1",
                        NOW.minusHours(1),
                        NOW.plusHours(1),
                        4,
                        processRevision,
                        7,
                        java.util.Map.of("packet","PACKET_1")));
    }

    private static String reviewerAuthorityHash() {
        return ReviewDecisionReceiptTestFixture.reviewerAuthorityHash("reviewer-local");
    }

    private static ReviewAuthorityReceipt humanAuthority(
            String actor, String requestHash, long epoch, long fence, long revision) {
        return new ReviewAuthorityReceipt(
                "HUMAN_1",
                ReviewDecisionFactType.HUMAN_DECISION,
                "TASK_1",
                "CASE_1",
                PACKET,
                ApprovalDecisionType.APPROVE,
                actor,
                "approved after review",
                "policy-v1",
                "human-key",
                requestHash,
                epoch,
                fence,
                revision,
                4,
                APPROVED_ACTION_HASH,
                true,
                NOW);
    }
}

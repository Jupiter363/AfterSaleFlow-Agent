package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.review.domain.FrozenReviewPacketIdentity;
import com.example.dispute.review.domain.ReviewAuthorityReceipt;
import com.example.dispute.review.domain.ReviewDecisionConflictException;
import com.example.dispute.review.domain.ReviewDecisionFactType;
import com.example.dispute.review.domain.ReviewDecisionFence;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ReviewDecisionConcurrencyTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 24, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final FrozenReviewPacketIdentity PACKET =
            new FrozenReviewPacketIdentity(
                    "PACKET_1",
                    "CASE_1",
                    "PLAN_1",
                    3,
                    "a".repeat(64),
                    "ACTION_HASH_1",
                    NOW.minusHours(1),
                    NOW.plusHours(1));

    @Test
    void identicalReplayReturnsTheExactOriginalReceipt() {
        ReviewDecisionFence fence = new ReviewDecisionFence();
        ReviewAuthorityReceipt receipt = human("RECEIPT_1", "key-1", "b".repeat(64));

        assertThat(fence.record(receipt)).isSameAs(receipt);
        assertThat(fence.record(human("RECEIPT_RETRY", "key-1", "b".repeat(64))))
                .isSameAs(receipt);
    }

    @Test
    void concurrentDifferentRequestsProduceOneWinnerAndOneConflict() throws Exception {
        ReviewDecisionFence fence = new ReviewDecisionFence();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> first = contender(fence, ready, start, human("R1", "k1", "b".repeat(64)));
            Callable<String> second = contender(fence, ready, start, human("R2", "k2", "c".repeat(64)));
            var futures = List.of(executor.submit(first), executor.submit(second));
            ready.await();
            start.countDown();

            List<String> results =
                    futures.stream()
                            .map(
                                    future -> {
                                        try {
                                            return future.get();
                                        } catch (Exception exception) {
                                            throw new AssertionError(exception);
                                        }
                                    })
                            .toList();
            assertThat(results).containsExactlyInAnyOrder("RECORDED", "CONFLICT");
            assertThat(fence.winner()).isPresent();
        }
    }

    @Test
    void systemSlaEscalationIsNotAHumanApprovalOrOperation() {
        ReviewAuthorityReceipt receipt =
                ReviewAuthorityReceipt.slaEscalation(
                        "SLA_1",
                        "TASK_1",
                        "CASE_1",
                        PACKET,
                        "review deadline elapsed",
                        "policy-v1",
                        "sla-key",
                        "d".repeat(64),
                        4,
                        9,
                        12,
                        13,
                        NOW);

        assertThat(receipt.factType()).isEqualTo(ReviewDecisionFactType.SYSTEM_SLA_ESCALATION);
        assertThat(receipt.reviewerId()).isNull();
        assertThat(receipt.decision()).isEqualTo(ApprovalDecisionType.ESCALATE_MANUAL);
        assertThat(receipt.operationEligible()).isFalse();
    }

    @Test
    void actorPacketEpochFenceAndRevisionSubstitutionAlwaysConflicts() {
        ReviewAuthorityReceipt winner=human("R1","key-1","b".repeat(64));
        FrozenReviewPacketIdentity changedPacket=new FrozenReviewPacketIdentity(
                "PACKET_2","CASE_1","PLAN_1",4,"e".repeat(64),"ACTION_HASH_2",
                NOW.minusHours(1),NOW.plusHours(1));
        List<ReviewAuthorityReceipt> substitutions=List.of(
                copy(winner,"other-reviewer",PACKET,4,9,12),
                copy(winner,"reviewer-local",changedPacket,4,9,12),
                copy(winner,"reviewer-local",PACKET,5,9,12),
                copy(winner,"reviewer-local",PACKET,4,10,12),
                copy(winner,"reviewer-local",PACKET,4,9,13));

        substitutions.forEach(candidate -> assertThatThrownByConflict(winner,candidate));
    }

    private static Callable<String> contender(
            ReviewDecisionFence fence,
            CountDownLatch ready,
            CountDownLatch start,
            ReviewAuthorityReceipt receipt) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                fence.record(receipt);
                return "RECORDED";
            } catch (ReviewDecisionConflictException conflict) {
                return "CONFLICT";
            }
        };
    }

    private static ReviewAuthorityReceipt human(
            String receiptId, String idempotencyKey, String requestHash) {
        return new ReviewAuthorityReceipt(
                receiptId,
                ReviewDecisionFactType.HUMAN_DECISION,
                "TASK_1",
                "CASE_1",
                PACKET,
                ApprovalDecisionType.APPROVE,
                "reviewer-local",
                "approved after review",
                "policy-v1",
                idempotencyKey,
                requestHash,
                4,
                9,
                12,
                13,
                "APPROVED_ACTION_HASH",
                true,
                NOW);
    }

    private static ReviewAuthorityReceipt copy(
            ReviewAuthorityReceipt source,
            String reviewerId,
            FrozenReviewPacketIdentity packet,
            long epoch,
            long fence,
            long revision) {
        return new ReviewAuthorityReceipt(
                "RETRY",source.factType(),source.taskId(),source.caseId(),packet,source.decision(),
                reviewerId,source.reason(),source.policyVersion(),source.idempotencyKey(),
                source.requestHash(),epoch,fence,revision,source.eventSequence(),
                source.approvedActionHash(),source.operationEligible(),source.recordedAt());
    }

    private static void assertThatThrownByConflict(
            ReviewAuthorityReceipt winner, ReviewAuthorityReceipt candidate) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ReviewDecisionFence(winner).record(candidate))
                .isInstanceOf(ReviewDecisionConflictException.class);
    }
}

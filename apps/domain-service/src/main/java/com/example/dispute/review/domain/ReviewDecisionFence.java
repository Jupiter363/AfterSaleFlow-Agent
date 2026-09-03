package com.example.dispute.review.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Transaction-scoped first-writer fence. Repositories rehydrate it while holding the durable task
 * lock; it is deliberately not a process-global idempotency store.
 */
public final class ReviewDecisionFence {

    private ReviewAuthorityReceipt winner;

    public ReviewDecisionFence() {}

    public ReviewDecisionFence(ReviewAuthorityReceipt winner) {
        this.winner = Objects.requireNonNull(winner, "winner");
    }

    public synchronized ReviewAuthorityReceipt record(ReviewAuthorityReceipt candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (winner == null) {
            winner = candidate;
            return winner;
        }
        if (sameCanonicalRequest(winner, candidate)) {
            return winner;
        }
        throw new ReviewDecisionConflictException(
                "the review decision/SLA fence already has an authoritative fact");
    }

    public synchronized Optional<ReviewAuthorityReceipt> winner() {
        return Optional.ofNullable(winner);
    }

    private static boolean sameCanonicalRequest(
            ReviewAuthorityReceipt existing, ReviewAuthorityReceipt candidate) {
        return existing.factType() == candidate.factType()
                && Objects.equals(existing.taskId(), candidate.taskId())
                && Objects.equals(existing.caseId(), candidate.caseId())
                && Objects.equals(existing.packet(), candidate.packet())
                && existing.decision() == candidate.decision()
                && Objects.equals(existing.reviewerId(), candidate.reviewerId())
                && Objects.equals(existing.reason(), candidate.reason())
                && Objects.equals(existing.policyVersion(), candidate.policyVersion())
                && Objects.equals(existing.idempotencyKey(), candidate.idempotencyKey())
                && Objects.equals(existing.requestHash(), candidate.requestHash())
                && existing.outcomeEpoch() == candidate.outcomeEpoch()
                && existing.fencingToken() == candidate.fencingToken()
                && existing.processRevision() == candidate.processRevision()
                && Objects.equals(existing.approvedActionHash(), candidate.approvedActionHash());
    }
}

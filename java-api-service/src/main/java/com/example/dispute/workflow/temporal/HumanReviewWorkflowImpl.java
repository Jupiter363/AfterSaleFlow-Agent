package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Durable human gate for every remedy.
 *
 * <p>The workflow validates the exact frozen packet version and action hash
 * before accepting a reviewer decision. Invalid or stale signals are audited
 * and cannot advance execution.
 */
public class HumanReviewWorkflowImpl implements HumanReviewWorkflow {

    private static final Set<String> DECISIONS =
            Set.of(
                    "APPROVE",
                    "MODIFY_AND_APPROVE",
                    "RETURN_FOR_REVISION",
                    "REQUEST_MORE_EVIDENCE",
                    "REJECT",
                    "ESCALATE",
                    "ESCALATE_MANUAL");
    private static final Map<String, String> STATUSES =
            Map.of(
                    "APPROVE", "APPROVED",
                    "MODIFY_AND_APPROVE", "MODIFIED_AND_APPROVED",
                    "RETURN_FOR_REVISION", "RETURNED_FOR_REVISION",
                    "REQUEST_MORE_EVIDENCE", "MORE_EVIDENCE_REQUESTED",
                    "REJECT", "REJECTED",
                    "ESCALATE", "ESCALATED",
                    "ESCALATE_MANUAL", "ESCALATED");

    private final HumanReviewActivities activities =
            Workflow.newActivityStub(
                    HumanReviewActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(1))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());
    private final Deque<HumanReviewSignal> decisions = new ArrayDeque<>();

    @Override
    public HumanReviewResult run(HumanReviewCommand command) {
        long waitDeadline =
                Workflow.currentTimeMillis() + command.waitTimeout().toMillis();
        long deadline = Math.min(waitDeadline, command.expiresAtEpochMillis());
        while (true) {
            long remaining = deadline - Workflow.currentTimeMillis();
            if (remaining <= 0) {
                return expiredOrTimedOut(command);
            }
            boolean received =
                    Workflow.await(
                            Duration.ofMillis(remaining),
                            () -> !decisions.isEmpty());
            if (!received) {
                return expiredOrTimedOut(command);
            }
            HumanReviewSignal signal = decisions.removeFirst();
            String invalidReason = validate(command, signal);
            if (invalidReason != null) {
                activities.recordInvalidDecision(
                        command, signal, invalidReason);
                continue;
            }
            String status = STATUSES.get(signal.decision());
            String reviewId =
                    activities.persistDecision(command, signal, status);
            return new HumanReviewResult(
                    reviewId,
                    status,
                    "APPROVE".equals(signal.decision())
                            || "MODIFY_AND_APPROVE".equals(
                                    signal.decision()),
                    "MODIFY_AND_APPROVE".equals(signal.decision()),
                    null);
        }
    }

    private HumanReviewResult expiredOrTimedOut(
            HumanReviewCommand command) {
        if (Workflow.currentTimeMillis() >= command.expiresAtEpochMillis()) {
            return new HumanReviewResult(
                    null,
                    "EXPIRED",
                    false,
                    false,
                    "REVIEW_PACKET_EXPIRED");
        }
        return new HumanReviewResult(
                null,
                "TIMED_OUT",
                false,
                false,
                "REVIEW_DECISION_TIMEOUT");
    }

    private String validate(
            HumanReviewCommand command,
            HumanReviewSignal signal) {
        if (signal.reviewerId() == null || signal.reviewerId().isBlank()) {
            return "INVALID_REVIEWER";
        }
        if (!command.requiredRole().equals(signal.reviewerRole())) {
            return "UNAUTHORIZED_REVIEWER_ROLE";
        }
        if (command.reviewPacketVersion()
                != signal.reviewPacketVersion()) {
            return "STALE_REVIEW_PACKET";
        }
        if (!command.actionHash().equals(signal.actionHash())) {
            return "ACTION_HASH_MISMATCH";
        }
        if (!DECISIONS.contains(signal.decision())) {
            return "INVALID_REVIEW_DECISION";
        }
        if (signal.reason() == null || signal.reason().isBlank()) {
            return "REVIEW_REASON_REQUIRED";
        }
        if (Workflow.currentTimeMillis() >= command.expiresAtEpochMillis()) {
            return "REVIEW_PACKET_EXPIRED";
        }
        return null;
    }

    @Override
    public void submitDecision(HumanReviewSignal signal) {
        decisions.addLast(signal);
    }
}

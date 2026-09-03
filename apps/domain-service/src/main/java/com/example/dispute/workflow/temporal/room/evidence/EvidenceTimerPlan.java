package com.example.dispute.workflow.temporal.room.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record EvidenceTimerPlan(
    String schemaVersion,
    Instant warningAt,
    Instant deadlineAt,
    long deadlineRevision,
    String warningOperationKey,
    String expiryOperationKey) {

  public static final Duration WARNING_LEAD = Duration.ofMinutes(30);

  public EvidenceTimerPlan {
    if (!"evidence-timer-plan.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be evidence-timer-plan.v1");
    }
    Objects.requireNonNull(warningAt, "warningAt must not be null");
    Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
    if (!warningAt.equals(deadlineAt.minus(WARNING_LEAD))) {
      throw new IllegalArgumentException("warningAt must be exactly 30 minutes before deadlineAt");
    }
    if (deadlineRevision < 1) {
      throw new IllegalArgumentException("deadlineRevision must be positive");
    }
    EvidenceOperationKeys.requireValid(warningOperationKey);
    EvidenceOperationKeys.requireValid(expiryOperationKey);
  }

  public static EvidenceTimerPlan from(EvidenceRoomStart start) {
    Objects.requireNonNull(start, "start must not be null");
    return new EvidenceTimerPlan(
        "evidence-timer-plan.v1",
        start.originalDeadlineAt().minus(WARNING_LEAD),
        start.originalDeadlineAt(),
        start.deadlineRevision(),
        EvidenceOperationKeys.deadlineWarn(
            start.caseId(), start.roomEpoch(), start.deadlineRevision()),
        EvidenceOperationKeys.deadlineExpire(
            start.caseId(), start.roomEpoch(), start.deadlineRevision()));
  }
}

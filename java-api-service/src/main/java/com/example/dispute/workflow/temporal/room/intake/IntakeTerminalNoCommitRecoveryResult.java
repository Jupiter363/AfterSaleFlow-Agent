package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommitResult;

/** Cached value returned after one exact historical terminal-no-commit receipt is emitted. */
public record IntakeTerminalNoCommitRecoveryResult(
    String schemaVersion,
    Disposition disposition,
    IntakeTerminalNoCommitRecoveryRequest request,
    ResolveTargetIntakeTerminalNoCommitResult resolvedAuthority) {

  public static final String SCHEMA_VERSION = "intake-terminal-no-commit-recovery-result.v1";

  public IntakeTerminalNoCommitRecoveryResult {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-terminal-no-commit-recovery-result.v1");
    }
    if (disposition == null
        || request == null
        || resolvedAuthority == null
        || !request.authority().equals(resolvedAuthority.authority())) {
      throw new IllegalArgumentException("terminal-no-commit recovery result is invalid");
    }
  }

  public enum Disposition {
    EMITTED,
    ALREADY_EMITTED
  }
}

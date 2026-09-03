package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Cached value returned after one exact historical terminal-no-commit receipt is emitted. */
public record IntakeTerminalNoCommitRecoveryResult(
    String schemaVersion,
    Disposition disposition,
    IntakeTerminalNoCommitRecoveryRequest request,
    ResolveTargetIntakeTerminalNoCommitResult resolvedAuthority,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        ConvergeTargetIntakeTerminalNoCommitResult convergence) {

  public static final String SCHEMA_VERSION = "intake-terminal-no-commit-recovery-result.v1";
  public static final String V2_SCHEMA_VERSION =
      "intake-terminal-no-commit-recovery-result.v2";

  public IntakeTerminalNoCommitRecoveryResult(
      String schemaVersion,
      Disposition disposition,
      IntakeTerminalNoCommitRecoveryRequest request,
      ResolveTargetIntakeTerminalNoCommitResult resolvedAuthority) {
    this(schemaVersion, disposition, request, resolvedAuthority, null);
  }

  public IntakeTerminalNoCommitRecoveryResult {
    boolean v2 = V2_SCHEMA_VERSION.equals(schemaVersion);
    if (!SCHEMA_VERSION.equals(schemaVersion) && !v2) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-terminal-no-commit-recovery-result.v1 or .v2");
    }
    if (disposition == null
        || request == null
        || resolvedAuthority == null) {
      throw new IllegalArgumentException("terminal-no-commit recovery result is invalid");
    }
    if (!v2) {
      if (!request.authority().equals(resolvedAuthority.authority()) || convergence != null) {
        throw new IllegalArgumentException("v1 terminal-no-commit recovery result is invalid");
      }
    } else {
      TargetIntakeCommandTerminalNoCommit resolved = resolvedAuthority.authority();
      if (!IntakeTerminalNoCommitRecoveryRequest.V3_SCHEMA_VERSION.equals(
              request.schemaVersion())
          || !TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
              resolved.schemaVersion())
          || !request.authority().equals(resolved.asObservedV2Authority())
          || convergence == null
          || !resolved.equals(convergence.authority())
          || !resolved.receiptUri().equals(resolvedAuthority.receiptUri())
          || !resolved.receiptSha256().equals(resolvedAuthority.receiptSha256())
          || !resolved.receiptUri().equals(convergence.receiptUri())
          || !resolved.receiptSha256().equals(convergence.receiptSha256())) {
        throw new IllegalArgumentException("v2 terminal-no-commit recovery result is invalid");
      }
    }
  }

  public enum Disposition {
    EMITTED,
    ALREADY_EMITTED,
    PARENT_CONVERGED
  }
}

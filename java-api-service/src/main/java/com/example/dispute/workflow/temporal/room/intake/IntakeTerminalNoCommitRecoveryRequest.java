package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;

/** Exact acknowledged request for a historical Room run that could not publish its terminal receipt. */
public record IntakeTerminalNoCommitRecoveryRequest(
    String schemaVersion,
    String workflowId,
    String workflowRunId,
    TargetIntakeCommandTerminalNoCommit authority) {

  public static final String LEGACY_SCHEMA_VERSION =
      "intake-terminal-no-commit-recovery-request.v1";
  public static final String SCHEMA_VERSION = "intake-terminal-no-commit-recovery-request.v2";
  public static final String V3_SCHEMA_VERSION =
      "intake-terminal-no-commit-recovery-request.v3";

  public IntakeTerminalNoCommitRecoveryRequest {
    boolean legacy = LEGACY_SCHEMA_VERSION.equals(schemaVersion);
    boolean v3 = V3_SCHEMA_VERSION.equals(schemaVersion);
    if (!legacy && !SCHEMA_VERSION.equals(schemaVersion) && !v3) {
      throw new IllegalArgumentException(
          "schemaVersion must be intake-terminal-no-commit-recovery-request.v1, .v2, or .v3");
    }
    requireText(workflowId, "workflowId");
    requireText(workflowRunId, "workflowRunId");
    String expectedAuthoritySchema =
        legacy
            ? TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION
            : TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION;
    if (authority == null
        || !workflowId.equals(authority.roomWorkflowId())
        || !expectedAuthoritySchema.equals(authority.schemaVersion())) {
      throw new IllegalArgumentException("recovery workflow authority is invalid");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(field + " must be bounded nonblank text");
    }
  }
}

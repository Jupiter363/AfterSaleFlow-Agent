package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;

/**
 * Exact authority for rebinding one stranded target Intake command to a continued child run.
 *
 * <p>The persisted provisioning commitment continues to identify the child's first run. This
 * request authorizes only the signal target used to retry the single pending command; it does not
 * replace provisioning, revision, or command-ledger authority.
 */
public record CaseProcessTargetIntakeCurrentRunDispatchRecoveryRequest(
    String schemaVersion,
    String workflowId,
    String workflowRunId,
    String tenantSurrogate,
    String caseId,
    String childWorkflowId,
    String childStartedRunId,
    String childCurrentRunId,
    long roomEpoch,
    long fencingToken,
    long expectedProcessRevision,
    long expectedRoomRevision,
    long expectedNextCommandSequence,
    long expectedProcessedCommandCount,
    String expectedCommandId,
    String expectedRequestHash) {

  public static final String SCHEMA_VERSION =
      "case-process-target-intake-current-run-dispatch-recovery-request.v1";

  public CaseProcessTargetIntakeCurrentRunDispatchRecoveryRequest {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
    }
    requireText(workflowId, "workflowId");
    requireText(workflowRunId, "workflowRunId");
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    requireText(childWorkflowId, "childWorkflowId");
    requireText(childStartedRunId, "childStartedRunId");
    requireText(childCurrentRunId, "childCurrentRunId");
    requireText(expectedCommandId, "expectedCommandId");
    requireHash(expectedRequestHash, "expectedRequestHash");
    if (!CaseProcessWorkflowProtocol.caseWorkflowId(tenantSurrogate, caseId).equals(workflowId)) {
      throw new IllegalArgumentException("workflowId does not match tenant/case authority");
    }
    if (!CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.INTAKE, roomEpoch)
        .equals(childWorkflowId)) {
      throw new IllegalArgumentException("childWorkflowId does not match the Intake room authority");
    }
    if (childStartedRunId.equals(childCurrentRunId)) {
      throw new IllegalArgumentException("continued child run must differ from the started run");
    }
    if (roomEpoch < 0
        || fencingToken < 1
        || expectedProcessRevision < 0
        || expectedRoomRevision < 0
        || expectedNextCommandSequence < 1
        || expectedProcessedCommandCount < 0
        || expectedProcessedCommandCount != expectedNextCommandSequence - 1) {
      throw new IllegalArgumentException("dispatch recovery coordinates are invalid");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hash");
    }
  }
}

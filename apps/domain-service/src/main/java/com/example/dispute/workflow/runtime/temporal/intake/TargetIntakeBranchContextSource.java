package com.example.dispute.workflow.runtime.temporal.intake;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import java.util.Objects;

/** CONTROL-side authority source for a persisted Intake branch command and private thread. */
public interface TargetIntakeBranchContextSource {

  ResolvedBranchContext resolve(Request request);

  record Request(
      CaseCommandRef command,
      long roomFencingToken,
      IntakeParty party,
      String actorScopeHash,
      String activationId,
      String activationManifestHash) {
    public Request {
      Objects.requireNonNull(command, "command must not be null");
      Objects.requireNonNull(party, "party must not be null");
      if (roomFencingToken < 1) {
        throw new IllegalArgumentException("roomFencingToken must be positive");
      }
      if (actorScopeHash == null || !actorScopeHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("actorScopeHash must be lowercase SHA-256");
      }
      if (activationId == null || activationId.isBlank()) {
        throw new IllegalArgumentException("activationId must not be blank");
      }
      if (activationManifestHash == null
          || !activationManifestHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("activationManifestHash must be lowercase SHA-256");
      }
    }
  }

  record ResolvedBranchContext(
      String threadId,
      String agentSessionId,
      BranchOperation operation,
      PinnedVersions branchPinnedVersions) {

    /** Source-compatible constructor for legacy callers; the target bridge rejects missing pins. */
    public ResolvedBranchContext(
        String threadId, String agentSessionId, BranchOperation operation) {
      this(threadId, agentSessionId, operation, null);
    }

    public ResolvedBranchContext {
      if (threadId == null || !threadId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
        throw new IllegalArgumentException("threadId is invalid");
      }
      if (agentSessionId == null
          || !agentSessionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
        throw new IllegalArgumentException("agentSessionId is invalid");
      }
      Objects.requireNonNull(operation, "operation must not be null");
      if (branchPinnedVersions != null
          && !"intake-pinned-versions.v2".equals(branchPinnedVersions.schemaVersion())) {
        throw new IllegalArgumentException(
            "target branch private-thread pins must use intake-pinned-versions.v2");
      }
    }
  }
}

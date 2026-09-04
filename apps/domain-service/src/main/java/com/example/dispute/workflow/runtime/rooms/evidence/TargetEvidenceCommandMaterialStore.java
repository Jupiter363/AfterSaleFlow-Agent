package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import java.time.Instant;
import java.util.Optional;

/** Append-only material store; callers must use one transaction with command admission. */
public interface TargetEvidenceCommandMaterialStore {
  AppendResult append(CommandAdmission admission, TargetEvidenceCommandMaterial material);

  Optional<MaterialSnapshot> readByRoute(CommandLookup lookup);

  /** Exact command identity lookup used by the finalizer before it learns the admitted fence. */
  Optional<MaterialSnapshot> readByCommand(CommandIdentity identity);

  record AppendResult(String admissionId, Instant admittedAt, String materialSha256, boolean replay) {}

  record MaterialSnapshot(
      String admissionId,
      CommandAdmission admission,
      TargetEvidenceCommandMaterial material,
      String materialSha256,
      Instant storedAt) {}

  record CommandLookup(
      String tenantSurrogate, String caseId, String commandId, long roomEpoch, long roomFencingToken) {
    public CommandLookup {
      if (tenantSurrogate == null || tenantSurrogate.isBlank() || caseId == null || caseId.isBlank()
          || commandId == null || commandId.isBlank() || roomEpoch < 0 || roomFencingToken < 1) {
        throw new IllegalArgumentException("target Evidence command route is invalid");
      }
    }
  }

  record CommandIdentity(String tenantSurrogate, String caseId, String commandId, long roomEpoch) {
    public CommandIdentity {
      if (tenantSurrogate == null || tenantSurrogate.isBlank() || caseId == null || caseId.isBlank()
          || commandId == null || commandId.isBlank() || roomEpoch < 0) {
        throw new IllegalArgumentException("target Evidence command identity is invalid");
      }
    }
  }
}

package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import java.time.Instant;
import java.util.Optional;

/** Caller-transaction store for non-Graph Evidence completion material and durable provenance. */
public interface TargetEvidenceCompletionCommandMaterialStore {
  AppendResult append(CommandAdmission admission, TargetEvidenceCompletionCommandMaterial material);

  Optional<Provenance> readProvenance(Route route);

  record AppendResult(String admissionId, Instant admittedAt, String materialSha256, boolean replay) {}

  enum Provenance {
    IN_FLIGHT,
    APPLIED_EXACT
  }

  record Route(
      String tenantSurrogate,
      String caseId,
      String commandId,
      long roomEpoch,
      long roomFencingToken,
      String completionId) {
    public Route {
      if (tenantSurrogate == null || tenantSurrogate.isBlank()
          || caseId == null || caseId.isBlank()
          || commandId == null || commandId.isBlank()
          || completionId == null || completionId.isBlank()
          || roomEpoch < 0 || roomFencingToken < 1
          || !commandId.equals("evidence-complete:" + completionId)) {
        throw new IllegalArgumentException("target Evidence completion route is invalid");
      }
    }
  }
}

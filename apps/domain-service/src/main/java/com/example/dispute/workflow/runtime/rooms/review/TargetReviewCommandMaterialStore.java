package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Append-only material store. Admission and append must share one caller-owned transaction. */
public interface TargetReviewCommandMaterialStore {
  AppendResult append(CommandAdmission admission, TargetReviewCommandMaterial material);
  Optional<Snapshot> readByRoute(Route route);
  List<Snapshot> readByCommand(CommandRoute route);

  record AppendResult(String admissionId, Instant admittedAt, String materialSha256, boolean replay) {}
  record Snapshot(String admissionId, CommandAdmission admission, TargetReviewCommandMaterial material,
                  String materialSha256, Instant storedAt) {}
  record Route(String tenantSurrogate, String caseId, String commandId, long roomEpoch, long roomFencingToken) {
    public Route {
      if (tenantSurrogate == null || tenantSurrogate.isBlank() || caseId == null || caseId.isBlank()
          || commandId == null || commandId.isBlank() || roomEpoch < 0 || roomFencingToken < 1) {
        throw new IllegalArgumentException("target Review command route is invalid");
      }
    }
  }
  record CommandRoute(String tenantSurrogate, String caseId, String commandId, long roomEpoch) {
    public CommandRoute {
      if (tenantSurrogate == null || tenantSurrogate.isBlank() || caseId == null || caseId.isBlank()
          || commandId == null || commandId.isBlank() || roomEpoch < 0) {
        throw new IllegalArgumentException("target Review command route is invalid");
      }
    }
  }
}

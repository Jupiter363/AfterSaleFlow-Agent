package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import java.time.Instant;
import java.util.Optional;

/** Append-only target Hearing command material. Callers own the transaction used by append. */
public interface TargetHearingCommandMaterialStore {
  AppendResult append(TargetHearingCommandMaterial material);

  Optional<Snapshot> readByRoute(Route route);

  /**
   * Finalization lookup before the fence is exposed by a trusted runtime proof. Implementations
   * must reject ambiguous rows rather than selecting a newer epoch or fence opportunistically.
   */
  Optional<Snapshot> readByCommand(CommandRoute route);

  record Route(String tenantSurrogate, String caseId, String commandId, long roomEpoch, long fencingToken) {
    public Route {
      if (tenantSurrogate == null || tenantSurrogate.isBlank() || caseId == null || caseId.isBlank()
          || commandId == null || commandId.isBlank() || roomEpoch < 0 || fencingToken < 1) {
        throw new IllegalArgumentException("invalid Hearing material route");
      }
    }
  }

  record CommandRoute(String tenantSurrogate, String caseId, String commandId, long roomEpoch) {
    public CommandRoute {
      if (tenantSurrogate == null || tenantSurrogate.isBlank() || caseId == null || caseId.isBlank()
          || commandId == null || commandId.isBlank() || roomEpoch < 0) {
        throw new IllegalArgumentException("invalid Hearing command route");
      }
    }
  }

  record AppendResult(String admissionId, Instant admittedAt, boolean attachedIdentical) {}

  record Snapshot(String admissionId, CommandAdmission admission, TargetHearingCommandMaterial material,
                  String materialSha256, Instant storedAt) {}
}

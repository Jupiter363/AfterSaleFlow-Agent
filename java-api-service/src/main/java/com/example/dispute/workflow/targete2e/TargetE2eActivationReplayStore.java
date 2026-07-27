package com.example.dispute.workflow.targete2e;

import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.CaseScope;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.RoomType;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Atomic register-or-attach boundary shared by all replicas in one environment generation.
 *
 * <p>Implementations must uniquely index both activation ID and nonce. Existing rows may attach
 * only when the entire registration is equal; every other collision is a conflict.
 */
@FunctionalInterface
public interface TargetE2eActivationReplayStore {

  RegistrationResult registerOrAttach(Registration registration);

  enum RegistrationResult {
    REGISTERED,
    ATTACHED_EXISTING,
    CONFLICT
  }

  record Registration(
      String environmentId,
      long environmentGeneration,
      String activationId,
      String nonce,
      String manifestHash,
      BindingSnapshot bindings,
      Instant retainUntil) {

    public Registration {
      TargetE2eActivationContract.identifier(environmentId, "environmentId");
      TargetE2eActivationContract.generation(environmentGeneration);
      TargetE2eActivationContract.activationId(activationId);
      TargetE2eActivationContract.nonce(nonce);
      TargetE2eActivationContract.sha256(manifestHash, "manifestHash");
      Objects.requireNonNull(bindings, "bindings");
      Objects.requireNonNull(retainUntil, "retainUntil");
    }
  }

  record BindingSnapshot(
      String candidateSha,
      String tenantSurrogate,
      CaseScope caseScope,
      Set<RoomType> allowedRoomTypes,
      BuildBindings buildBindings,
      GraphBinding graphBinding,
      ImageDigests imageDigests,
      String temporalNamespace,
      DatabaseIdentities databaseIdentities) {

    public BindingSnapshot {
      TargetE2eActivationContract.candidateSha(candidateSha);
      TargetE2eActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
      Objects.requireNonNull(caseScope, "caseScope");
      allowedRoomTypes = Set.copyOf(Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes"));
      Objects.requireNonNull(buildBindings, "buildBindings");
      Objects.requireNonNull(graphBinding, "graphBinding");
      Objects.requireNonNull(imageDigests, "imageDigests");
      TargetE2eActivationContract.identifier(temporalNamespace, "temporalNamespace");
      Objects.requireNonNull(databaseIdentities, "databaseIdentities");
    }
  }
}

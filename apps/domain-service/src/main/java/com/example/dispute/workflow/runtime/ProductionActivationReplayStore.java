package com.example.dispute.workflow.runtime;

import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.CaseScope;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.IsolatedSyntheticNewCases;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.MeasuredAuthorityFacts;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.RoomType;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.SyntheticFixtureDeployment;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Atomic register-or-attach boundary shared by all replicas in one environment generation.
 *
 * <p>Implementations must uniquely index both activation ID and nonce. Existing rows may attach
 * only when the entire registration is equal; every other collision is a conflict. Registration and
 * the durable per-environment generation high-water update are one transaction. A first grant must
 * exceed the high-water; an identical HA attach may equal it; all other equal or lower generations
 * are rejected and high-water never decreases after drain or revocation. Grant rows are retained
 * through REVOKED_TERMINAL, not merely through signed expiry.
 */
public interface ProductionActivationReplayStore {

  RegistrationResult registerOrAttach(Registration registration);

  /** Never creates a registration; attaches only to one byte-identical existing grant. */
  RegistrationResult attachExistingForDrain(Registration registration);

  enum RegistrationResult {
    REGISTERED,
    ATTACHED_EXISTING,
    ENVIRONMENT_GENERATION_STALE,
    ENVIRONMENT_GENERATION_CONFLICT,
    CONFLICT
  }

  record Registration(
      String environmentId,
      long environmentGeneration,
      String activationId,
      String nonce,
      String manifestHash,
      BindingSnapshot bindings,
      Instant issuedAt,
      Instant expiresAt) {

    public Registration {
      ProductionActivationContract.identifier(environmentId, "environmentId");
      ProductionActivationContract.generation(environmentGeneration);
      ProductionActivationContract.activationId(activationId);
      ProductionActivationContract.nonce(nonce);
      ProductionActivationContract.sha256(manifestHash, "manifestHash");
      Objects.requireNonNull(bindings, "bindings");
      Objects.requireNonNull(issuedAt, "issuedAt");
      Objects.requireNonNull(expiresAt, "expiresAt");
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
      DatabaseIdentities databaseIdentities,
      Optional<SyntheticFixtureDeployment> syntheticFixtureDeployment,
      MeasuredAuthorityFacts authorityFacts) {

    public BindingSnapshot {
      ProductionActivationContract.candidateSha(candidateSha);
      ProductionActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
      Objects.requireNonNull(caseScope, "caseScope");
      allowedRoomTypes = Set.copyOf(Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes"));
      Objects.requireNonNull(buildBindings, "buildBindings");
      Objects.requireNonNull(graphBinding, "graphBinding");
      Objects.requireNonNull(imageDigests, "imageDigests");
      ProductionActivationContract.identifier(temporalNamespace, "temporalNamespace");
      Objects.requireNonNull(databaseIdentities, "databaseIdentities");
      syntheticFixtureDeployment =
          Objects.requireNonNull(syntheticFixtureDeployment, "syntheticFixtureDeployment");
      if ((caseScope instanceof IsolatedSyntheticNewCases)
          != syntheticFixtureDeployment.isPresent()) {
        throw new IllegalArgumentException(
            "replay snapshot fixture deployment is inconsistent with case scope");
      }
      if (caseScope instanceof IsolatedSyntheticNewCases synthetic) {
        SyntheticFixtureDeployment deployment = syntheticFixtureDeployment.orElseThrow();
        if (!synthetic.fixtureSetId().equals(deployment.fixtureSetId())
            || !synthetic.fixtureSetHash().equals(deployment.measuredCanonicalHash())) {
          throw new IllegalArgumentException(
              "replay snapshot fixture deployment does not match synthetic scope");
        }
      }
      Objects.requireNonNull(authorityFacts, "authorityFacts");
    }
  }
}

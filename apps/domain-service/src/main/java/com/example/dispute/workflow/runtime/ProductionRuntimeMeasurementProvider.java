package com.example.dispute.workflow.runtime;

import java.util.Objects;
import java.util.Set;

/** Trusted, fail-closed source of facts measured from the running production runtime artifact. */
public sealed interface ProductionRuntimeMeasurementProvider
    permits SpringJdbcProductionRuntimeMeasurementProvider,
        ProductionRuntimeMeasurementProvider.FixedMeasurementProvider {

  MeasuredRuntime measure(MeasurementChallenge challenge);

  record MeasurementChallenge(
      String activationKeyId, String activationNonce, String activationPublicKeyFingerprint) {

    public MeasurementChallenge {
      ProductionActivationContract.keyId(activationKeyId);
      ProductionActivationContract.nonce(activationNonce);
      ProductionActivationContract.sha256(
          activationPublicKeyFingerprint, "activationPublicKeyFingerprint");
    }
  }

  record MeasurementEvidence(
      Set<String> activeProfiles,
      String artifactMarker,
      String artifactDigest,
      String workerRole,
      DatabasePrivilegeEvidence domainPrivileges,
      DatabasePrivilegeEvidence graphPrivileges,
      ProductionIsolationAttestationVerifier.VerifiedAttestation isolationAttestation) {

    public MeasurementEvidence {
      activeProfiles = Set.copyOf(Objects.requireNonNull(activeProfiles, "activeProfiles"));
      Set<String> expectedProfiles =
          workerRole == null
              ? Set.of()
              : switch (workerRole) {
                case "CONTROL" -> Set.of("production-runtime", "control-worker");
                case "AGENT" -> Set.of("production-runtime", "agent-worker");
                default -> Set.of();
              };
      if (!activeProfiles.equals(expectedProfiles)) {
        throw new IllegalArgumentException("production runtime runtime profiles are not isolated");
      }
      if (!SpringJdbcProductionRuntimeMeasurementProvider.ARTIFACT_MARKER.equals(artifactMarker)) {
        throw new IllegalArgumentException("production runtime artifact marker is invalid");
      }
      ProductionActivationContract.sha256(artifactDigest, "artifactDigest");
      if (expectedProfiles.isEmpty()) {
        throw new IllegalArgumentException("production runtime worker role is invalid");
      }
      Objects.requireNonNull(domainPrivileges, "domainPrivileges");
      Objects.requireNonNull(graphPrivileges, "graphPrivileges");
      if (elevated(domainPrivileges)
          || elevated(graphPrivileges)
          || domainPrivileges.peerPrincipalCanConnect()
          || graphPrivileges.peerPrincipalCanConnect()) {
        throw new IllegalArgumentException("production runtime runtime retains elevated or peer privileges");
      }
      Objects.requireNonNull(isolationAttestation, "isolationAttestation");
    }

    private static boolean elevated(DatabasePrivilegeEvidence privileges) {
      return privileges.superuser()
          || privileges.createRole()
          || privileges.createDatabase()
          || privileges.replication()
          || privileges.bypassRowLevelSecurity();
    }
  }

  record DatabasePrivilegeEvidence(
      boolean superuser,
      boolean createRole,
      boolean createDatabase,
      boolean replication,
      boolean bypassRowLevelSecurity,
      boolean peerPrincipalCanConnect) {}

  /**
   * Package-only deterministic provider used by focused tests; external callers cannot implement.
   */
  final class FixedMeasurementProvider implements ProductionRuntimeMeasurementProvider {

    private final MeasuredRuntime measuredRuntime;

    FixedMeasurementProvider(MeasuredRuntime measuredRuntime) {
      this.measuredRuntime = Objects.requireNonNull(measuredRuntime, "measuredRuntime");
    }

    @Override
    public MeasuredRuntime measure(MeasurementChallenge challenge) {
      Objects.requireNonNull(challenge, "challenge");
      return measuredRuntime;
    }
  }
}

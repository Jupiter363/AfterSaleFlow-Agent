package com.example.dispute.workflow.targete2e;

import java.util.Objects;
import java.util.Set;

/** Trusted, fail-closed source of facts measured from the running target artifact. */
public sealed interface TargetE2eRuntimeMeasurementProvider
    permits SpringJdbcTargetE2eRuntimeMeasurementProvider,
        TargetE2eRuntimeMeasurementProvider.FixedMeasurementProvider {

  MeasuredRuntime measure(MeasurementChallenge challenge);

  record MeasurementChallenge(
      String activationKeyId, String activationNonce, String activationPublicKeyFingerprint) {

    public MeasurementChallenge {
      TargetE2eActivationContract.keyId(activationKeyId);
      TargetE2eActivationContract.nonce(activationNonce);
      TargetE2eActivationContract.sha256(
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
      TargetE2eIsolationAttestationVerifier.VerifiedAttestation isolationAttestation) {

    public MeasurementEvidence {
      activeProfiles = Set.copyOf(Objects.requireNonNull(activeProfiles, "activeProfiles"));
      if (!activeProfiles.equals(Set.of("target-e2e", "agent-worker"))) {
        throw new IllegalArgumentException("target E2E runtime profiles are not isolated");
      }
      if (!SpringJdbcTargetE2eRuntimeMeasurementProvider.ARTIFACT_MARKER.equals(artifactMarker)) {
        throw new IllegalArgumentException("target E2E artifact marker is invalid");
      }
      TargetE2eActivationContract.sha256(artifactDigest, "artifactDigest");
      if (!Set.of("CONTROL", "AGENT").contains(workerRole)) {
        throw new IllegalArgumentException("target E2E worker role is invalid");
      }
      Objects.requireNonNull(domainPrivileges, "domainPrivileges");
      Objects.requireNonNull(graphPrivileges, "graphPrivileges");
      if (elevated(domainPrivileges)
          || elevated(graphPrivileges)
          || domainPrivileges.peerPrincipalCanConnect()
          || graphPrivileges.peerPrincipalCanConnect()) {
        throw new IllegalArgumentException("target E2E runtime retains elevated or peer privileges");
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
  final class FixedMeasurementProvider implements TargetE2eRuntimeMeasurementProvider {

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

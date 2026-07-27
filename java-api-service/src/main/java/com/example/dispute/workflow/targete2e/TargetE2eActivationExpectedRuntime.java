package com.example.dispute.workflow.targete2e;

import java.util.Objects;
import java.util.Set;

/** Java-owned exact runtime binding against which one signed activation is armed. */
public record TargetE2eActivationExpectedRuntime(
    String appProfile,
    String environmentId,
    long environmentGeneration,
    String candidateSha,
    String tenantSurrogate,
    CaseScope caseScope,
    Set<RoomType> allowedRoomTypes,
    BuildBindings buildBindings,
    GraphBinding graphBinding,
    ImageDigests imageDigests,
    String temporalNamespace,
    DatabaseIdentities databaseIdentities) {

  public TargetE2eActivationExpectedRuntime {
    TargetE2eActivationContract.appProfile(appProfile);
    TargetE2eActivationContract.identifier(environmentId, "environmentId");
    TargetE2eActivationContract.generation(environmentGeneration);
    TargetE2eActivationContract.candidateSha(candidateSha);
    TargetE2eActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
    Objects.requireNonNull(caseScope, "caseScope");
    allowedRoomTypes = Set.copyOf(Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes"));
    if (allowedRoomTypes.isEmpty() || allowedRoomTypes.size() > 4) {
      throw new IllegalArgumentException("allowed room types must contain 1..4 values");
    }
    Objects.requireNonNull(buildBindings, "buildBindings");
    Objects.requireNonNull(graphBinding, "graphBinding");
    Objects.requireNonNull(imageDigests, "imageDigests");
    TargetE2eActivationContract.identifier(temporalNamespace, "temporalNamespace");
    Objects.requireNonNull(databaseIdentities, "databaseIdentities");
  }

  public enum RoomType {
    INTAKE,
    EVIDENCE,
    HEARING,
    REVIEW
  }

  public sealed interface CaseScope permits ExplicitCaseIds, IsolatedSyntheticNewCases {}

  public record ExplicitCaseIds(Set<String> allowedCaseIds) implements CaseScope {

    public ExplicitCaseIds {
      allowedCaseIds = Set.copyOf(Objects.requireNonNull(allowedCaseIds, "allowedCaseIds"));
      if (allowedCaseIds.isEmpty() || allowedCaseIds.size() > 100) {
        throw new IllegalArgumentException("explicit case IDs must contain 1..100 entries");
      }
      allowedCaseIds.forEach(TargetE2eActivationContract::caseId);
    }
  }

  public record IsolatedSyntheticNewCases(
      String caseIdPrefix,
      int maxCases,
      String fixtureSetId,
      String fixtureSetHash,
      boolean containsRealCaseOrPartyData,
      boolean externalEffectsAllowed)
      implements CaseScope {

    public IsolatedSyntheticNewCases {
      TargetE2eActivationContract.caseIdPrefix(caseIdPrefix);
      if (maxCases < 1 || maxCases > 16) {
        throw new IllegalArgumentException("maxCases must be inside 1..16");
      }
      TargetE2eActivationContract.identifier(fixtureSetId, "fixtureSetId");
      TargetE2eActivationContract.sha256(fixtureSetHash, "fixtureSetHash");
      if (containsRealCaseOrPartyData || externalEffectsAllowed) {
        throw new IllegalArgumentException("synthetic case scope exceeds isolated authority");
      }
    }
  }

  public record BuildBindings(String caseBuildId, String controlBuildId, String agentBuildId) {

    public BuildBindings {
      TargetE2eActivationContract.identifier(caseBuildId, "caseBuildId");
      TargetE2eActivationContract.identifier(controlBuildId, "controlBuildId");
      TargetE2eActivationContract.identifier(agentBuildId, "agentBuildId");
    }
  }

  public record GraphBinding(
      String key,
      String version,
      String checkpointSchemaVersion,
      String bindingHash,
      String codeBuildId) {

    public GraphBinding {
      if (!"all-rooms/target-e2e.v1".equals(key)) {
        throw new IllegalArgumentException("target E2E Graph key is invalid");
      }
      TargetE2eActivationContract.identifier(version, "graph version");
      TargetE2eActivationContract.identifier(checkpointSchemaVersion, "checkpointSchemaVersion");
      TargetE2eActivationContract.sha256(bindingHash, "bindingHash");
      TargetE2eActivationContract.identifier(codeBuildId, "Graph codeBuildId");
    }
  }

  public record ImageDigests(
      String javaApi,
      String temporalControlWorker,
      String temporalAgentWorker,
      String pythonAgent,
      String frontend) {

    public ImageDigests {
      TargetE2eActivationContract.imageDigest(javaApi);
      TargetE2eActivationContract.imageDigest(temporalControlWorker);
      TargetE2eActivationContract.imageDigest(temporalAgentWorker);
      TargetE2eActivationContract.imageDigest(pythonAgent);
      TargetE2eActivationContract.imageDigest(frontend);
    }
  }

  public record DatabaseIdentities(DatabaseIdentity domain, DatabaseIdentity graph) {

    public DatabaseIdentities {
      Objects.requireNonNull(domain, "domain");
      Objects.requireNonNull(graph, "graph");
      if (domain.equals(graph)) {
        throw new IllegalArgumentException("Domain and Graph database identities must be distinct");
      }
    }
  }

  public record DatabaseIdentity(
      String clusterIdentity, String databaseIdentity, String runtimePrincipalIdentity) {

    public DatabaseIdentity {
      TargetE2eActivationContract.identifier(clusterIdentity, "clusterIdentity");
      TargetE2eActivationContract.identifier(databaseIdentity, "databaseIdentity");
      TargetE2eActivationContract.identifier(runtimePrincipalIdentity, "runtimePrincipalIdentity");
    }
  }
}

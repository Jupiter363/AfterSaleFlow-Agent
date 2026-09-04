package com.example.dispute.workflow.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact runtime binding created only inside the trusted measurement package. */
public final class ProductionActivationExpectedRuntime {

  private final String appProfile;
  private final String environmentId;
  private final long environmentGeneration;
  private final String candidateSha;
  private final String tenantSurrogate;
  private final CaseScope caseScope;
  private final Set<RoomType> allowedRoomTypes;
  private final BuildBindings buildBindings;
  private final GraphBinding graphBinding;
  private final ImageDigests imageDigests;
  private final String temporalNamespace;
  private final DatabaseIdentities databaseIdentities;
  private final Optional<SyntheticFixtureDeployment> syntheticFixtureDeployment;
  private final MeasuredAuthorityFacts authorityFacts;

  ProductionActivationExpectedRuntime(
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
      DatabaseIdentities databaseIdentities,
      Optional<SyntheticFixtureDeployment> syntheticFixtureDeployment,
      MeasuredAuthorityFacts authorityFacts) {
    ProductionActivationContract.appProfile(appProfile);
    ProductionActivationContract.identifier(environmentId, "environmentId");
    ProductionActivationContract.generation(environmentGeneration);
    ProductionActivationContract.candidateSha(candidateSha);
    ProductionActivationContract.identifier(tenantSurrogate, "tenantSurrogate");
    Objects.requireNonNull(caseScope, "caseScope");
    allowedRoomTypes = Set.copyOf(Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes"));
    if (allowedRoomTypes.isEmpty() || allowedRoomTypes.size() > 4) {
      throw new IllegalArgumentException("allowed room types must contain 1..4 values");
    }
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
          "synthetic case scope requires exactly one fixture deployment binding");
    }
    if (caseScope instanceof IsolatedSyntheticNewCases synthetic) {
      SyntheticFixtureDeployment deployment = syntheticFixtureDeployment.orElseThrow();
      if (!synthetic.fixtureSetId().equals(deployment.fixtureSetId())
          || !synthetic.fixtureSetHash().equals(deployment.measuredCanonicalHash())) {
        throw new IllegalArgumentException(
            "synthetic fixture deployment must match the expected activation scope");
      }
    }
    Objects.requireNonNull(authorityFacts, "authorityFacts");
    this.appProfile = appProfile;
    this.environmentId = environmentId;
    this.environmentGeneration = environmentGeneration;
    this.candidateSha = candidateSha;
    this.tenantSurrogate = tenantSurrogate;
    this.caseScope = caseScope;
    this.allowedRoomTypes = allowedRoomTypes;
    this.buildBindings = buildBindings;
    this.graphBinding = graphBinding;
    this.imageDigests = imageDigests;
    this.temporalNamespace = temporalNamespace;
    this.databaseIdentities = databaseIdentities;
    this.syntheticFixtureDeployment = syntheticFixtureDeployment;
    this.authorityFacts = authorityFacts;
  }

  public String appProfile() {
    return appProfile;
  }

  public String environmentId() {
    return environmentId;
  }

  public long environmentGeneration() {
    return environmentGeneration;
  }

  public String candidateSha() {
    return candidateSha;
  }

  public String tenantSurrogate() {
    return tenantSurrogate;
  }

  public CaseScope caseScope() {
    return caseScope;
  }

  public Set<RoomType> allowedRoomTypes() {
    return allowedRoomTypes;
  }

  public BuildBindings buildBindings() {
    return buildBindings;
  }

  public GraphBinding graphBinding() {
    return graphBinding;
  }

  public ImageDigests imageDigests() {
    return imageDigests;
  }

  public String temporalNamespace() {
    return temporalNamespace;
  }

  public DatabaseIdentities databaseIdentities() {
    return databaseIdentities;
  }

  public Optional<SyntheticFixtureDeployment> syntheticFixtureDeployment() {
    return syntheticFixtureDeployment;
  }

  public MeasuredAuthorityFacts authorityFacts() {
    return authorityFacts;
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
      allowedCaseIds.forEach(ProductionActivationContract::caseId);
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
      ProductionActivationContract.caseIdPrefix(caseIdPrefix);
      if (maxCases < 1 || maxCases > 16) {
        throw new IllegalArgumentException("maxCases must be inside 1..16");
      }
      ProductionActivationContract.identifier(fixtureSetId, "fixtureSetId");
      ProductionActivationContract.sha256(fixtureSetHash, "fixtureSetHash");
      if (containsRealCaseOrPartyData || externalEffectsAllowed) {
        throw new IllegalArgumentException("synthetic case scope exceeds isolated authority");
      }
    }
  }

  public record BuildBindings(String caseBuildId, String controlBuildId, String agentBuildId) {

    public BuildBindings {
      ProductionActivationContract.identifier(caseBuildId, "caseBuildId");
      ProductionActivationContract.identifier(controlBuildId, "controlBuildId");
      ProductionActivationContract.identifier(agentBuildId, "agentBuildId");
    }
  }

  public record GraphBinding(
      String key,
      String version,
      String checkpointSchemaVersion,
      String bindingHash,
      String codeBuildId) {

    public GraphBinding {
      if (!"all-rooms.production-runtime.v2".equals(key)) {
        throw new IllegalArgumentException("production runtime Graph key is invalid");
      }
      ProductionActivationContract.identifier(version, "graph version");
      ProductionActivationContract.identifier(checkpointSchemaVersion, "checkpointSchemaVersion");
      ProductionActivationContract.sha256(bindingHash, "bindingHash");
      ProductionActivationContract.identifier(codeBuildId, "Graph codeBuildId");
    }
  }

  public record ImageDigests(
      String javaApi,
      String temporalControlWorker,
      String temporalAgentWorker,
      String pythonAgent,
      String frontend) {

    public ImageDigests {
      ProductionActivationContract.imageDigest(javaApi);
      ProductionActivationContract.imageDigest(temporalControlWorker);
      ProductionActivationContract.imageDigest(temporalAgentWorker);
      ProductionActivationContract.imageDigest(pythonAgent);
      ProductionActivationContract.imageDigest(frontend);
    }
  }

  public record DatabaseIdentities(DatabaseIdentity domain, DatabaseIdentity graph) {

    public DatabaseIdentities {
      Objects.requireNonNull(domain, "domain");
      Objects.requireNonNull(graph, "graph");
      if (domain.clusterIdentity().equals(graph.clusterIdentity())
          || domain.databaseIdentity().equals(graph.databaseIdentity())
          || domain.runtimePrincipalIdentity().equals(graph.runtimePrincipalIdentity())) {
        throw new IllegalArgumentException(
            "Domain and Graph cluster and database must be physically distinct and principals"
                + " distinct");
      }
    }
  }

  public record DatabaseIdentity(
      String clusterIdentity, String databaseIdentity, String runtimePrincipalIdentity) {

    public DatabaseIdentity {
      ProductionActivationContract.identifier(clusterIdentity, "clusterIdentity");
      ProductionActivationContract.identifier(databaseIdentity, "databaseIdentity");
      ProductionActivationContract.identifier(runtimePrincipalIdentity, "runtimePrincipalIdentity");
    }
  }

  public record SyntheticFixtureDeployment(
      String fixtureSetId, String readOnlyPathBinding, String measuredCanonicalHash) {

    public SyntheticFixtureDeployment {
      ProductionActivationContract.identifier(fixtureSetId, "fixtureSetId");
      ProductionSyntheticFixtureSource.requirePathBinding(readOnlyPathBinding);
      ProductionActivationContract.sha256(measuredCanonicalHash, "measuredCanonicalHash");
    }
  }

  /** Deployment facts measured independently of the signed manifest. */
  public record MeasuredAuthorityFacts(
      boolean isolatedDeployment,
      String environmentClass,
      String graphOutputAuthority,
      boolean graphDomainCredentialsPresent,
      boolean graphDomainPrivilegesPresent,
      boolean graphDomainWriteAllowed,
      String formalWriter,
      boolean javaDomainCommitAllowed,
      boolean externalEffectsAllowed,
      boolean productionTrafficAllowed,
      boolean productionPromotionAuthority,
      boolean migrationPromotionAuthority,
      String formalCaseSelectorDefault,
      String productionActivationDefault) {

    public MeasuredAuthorityFacts {
      if (!isolatedDeployment
          || !"ISOLATED_PREPRODUCTION".equals(environmentClass)
          || !"PROPOSAL_ONLY".equals(graphOutputAuthority)
          || graphDomainCredentialsPresent
          || graphDomainPrivilegesPresent
          || graphDomainWriteAllowed
          || !"JAVA_FINALIZER_ONLY".equals(formalWriter)
          || !javaDomainCommitAllowed
          || externalEffectsAllowed
          || productionTrafficAllowed
          || productionPromotionAuthority
          || migrationPromotionAuthority
          || !"LEGACY".equals(formalCaseSelectorDefault)
          || !"DISABLED".equals(productionActivationDefault)) {
        throw new IllegalArgumentException(
            "measured deployment authority exceeds the production runtime ceiling");
      }
    }
  }
}

package com.example.dispute.workflow.runtime;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.runtime.ActivationDecision.AuthorizationMode;
import com.example.dispute.workflow.runtime.ActivationDecision.Reason;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.CaseScope;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.DatabaseIdentity;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.ExplicitCaseIds;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.IsolatedSyntheticNewCases;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.MeasuredAuthorityFacts;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.RoomType;
import com.example.dispute.workflow.runtime.ProductionActivationExpectedRuntime.SyntheticFixtureDeployment;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.LifecycleState;
import com.example.dispute.workflow.runtime.ProductionActivationReplayStore.BindingSnapshot;
import com.example.dispute.workflow.runtime.ProductionActivationReplayStore.Registration;
import com.example.dispute.workflow.runtime.ProductionActivationReplayStore.RegistrationResult;
import com.example.dispute.workflow.runtime.ProductionRuntimeMeasurementProvider.MeasurementChallenge;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed compact-JWS verifier and deployment-scoped arming authority. */
public final class ProductionActivationManifestVerifier {

  private static final int MAXIMUM_JWS_CHARACTERS = 48 * 1024;
  private static final int MAXIMUM_HEADER_BYTES = 2 * 1024;
  private static final int MAXIMUM_PAYLOAD_BYTES = 32 * 1024;
  private static final long MAXIMUM_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
  private static final Duration MAXIMUM_LIFETIME = Duration.ofDays(30);
  private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
  private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "kid", "typ");
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of(
          "activationId",
          "allowedRoomTypes",
          "authority",
          "buildBindings",
          "candidateSha",
          "caseScope",
          "contractVersion",
          "databaseIdentities",
          "environmentGeneration",
          "environmentId",
          "executionLane",
          "expiresAt",
          "graphBinding",
          "imageDigests",
          "issuedAt",
          "manifestHash",
          "nonce",
          "productionDefaults",
          "temporalNamespace",
          "tenantSurrogate");
  private static final Set<String> EXPLICIT_CASE_SCOPE_FIELDS = Set.of("allowedCaseIds", "mode");
  private static final Set<String> SYNTHETIC_CASE_SCOPE_FIELDS =
      Set.of(
          "caseIdPrefix",
          "containsRealCaseOrPartyData",
          "externalEffectsAllowed",
          "fixtureSetHash",
          "fixtureSetId",
          "maxCases",
          "mode");
  private static final Set<String> BUILD_BINDING_FIELDS =
      Set.of("agentBuildId", "caseBuildId", "controlBuildId");
  private static final Set<String> GRAPH_BINDING_FIELDS =
      Set.of("bindingHash", "checkpointSchemaVersion", "codeBuildId", "key", "version");
  private static final Set<String> IMAGE_DIGEST_FIELDS =
      Set.of("frontend", "javaApi", "pythonAgent", "temporalAgentWorker", "temporalControlWorker");
  private static final Set<String> DATABASE_IDENTITIES_FIELDS = Set.of("domain", "graph");
  private static final Set<String> DATABASE_IDENTITY_FIELDS =
      Set.of("clusterIdentity", "databaseIdentity", "runtimePrincipalIdentity");
  private static final Set<String> AUTHORITY_FIELDS =
      Set.of(
          "environmentClass",
          "externalEffectsAllowed",
          "formalWriter",
          "graphDomainCredentialsPresent",
          "graphDomainWriteAllowed",
          "graphOutputAuthority",
          "javaDomainCommitAllowed",
          "migrationPromotionAuthority",
          "productionPromotionAuthority",
          "productionTrafficAllowed");
  private static final Set<String> PRODUCTION_DEFAULT_FIELDS =
      Set.of("formalCaseSelector", "productionActivation");
  private static final Set<String> FIXTURE_SET_FIELDS =
      Set.of(
          "caseIdPrefix",
          "fixtureSetId",
          "maximumCases",
          "roomTypes",
          "scenarios",
          "schemaVersion");
  private static final Set<String> FIXTURE_SCENARIO_FIELDS =
      Set.of("expectedTerminalClass", "fixtureId", "inputHash", "inputSchemaVersion", "roomType");

  private final ProductionActivationPublicKeySet publicKeys;
  private final ProductionActivationReplayStore replayStore;
  private final ProductionActivationCaseLedger caseLedger;
  private final ProductionActivationLifecycleStore lifecycleStore;
  private final ProductionSyntheticFixtureSource fixtureSource;
  private final ProductionRuntimeMeasurementProvider measurementProvider;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ProductionActivationManifestVerifier(
      ProductionActivationPublicKeySet publicKeys,
      ProductionActivationReplayStore replayStore,
      ProductionActivationCaseLedger caseLedger,
      ProductionActivationLifecycleStore lifecycleStore,
      ProductionSyntheticFixtureSource fixtureSource,
      ProductionRuntimeMeasurementProvider measurementProvider,
      Clock clock) {
    this(
        publicKeys,
        replayStore,
        caseLedger,
        lifecycleStore,
        fixtureSource,
        measurementProvider,
        clock,
        Duration.ZERO);
  }

  public ProductionActivationManifestVerifier(
      ProductionActivationPublicKeySet publicKeys,
      ProductionActivationReplayStore replayStore,
      ProductionActivationCaseLedger caseLedger,
      ProductionActivationLifecycleStore lifecycleStore,
      ProductionSyntheticFixtureSource fixtureSource,
      ProductionRuntimeMeasurementProvider measurementProvider,
      Clock clock,
      Duration clockSkew) {
    this.publicKeys = Objects.requireNonNull(publicKeys, "publicKeys");
    this.replayStore = Objects.requireNonNull(replayStore, "replayStore");
    this.caseLedger = Objects.requireNonNull(caseLedger, "caseLedger");
    this.lifecycleStore = Objects.requireNonNull(lifecycleStore, "lifecycleStore");
    this.fixtureSource = Objects.requireNonNull(fixtureSource, "fixtureSource");
    this.measurementProvider = Objects.requireNonNull(measurementProvider, "measurementProvider");
    this.clock = Objects.requireNonNull(clock, "clock");
    requireZeroClockSkew(clockSkew);
    this.mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  }

  public ProductionActivationAuthority arm(String compactJws) {
    return arm(compactJws, null);
  }

  public ProductionActivationAuthority armForDrain(
      String compactJws, DrainAcceptedCommand acceptedCommand) {
    return arm(compactJws, Objects.requireNonNull(acceptedCommand, "acceptedCommand"));
  }

  private ProductionActivationAuthority arm(String compactJws, DrainAcceptedCommand drainCommand) {
    if (compactJws == null || compactJws.isBlank()) {
      return denied(Reason.DEFAULT_DENY);
    }
    try {
      ParsedManifest manifest = verify(compactJws);
      if (manifest.expired() != (drainCommand != null)) {
        return denied(manifest.expired() ? Reason.EXPIRED : Reason.DRAIN_PROOF_REQUIRED);
      }
      RegistrationResult registration;
      try {
        registration =
            manifest.expired()
                ? replayStore.attachExistingForDrain(manifest.registration())
                : replayStore.registerOrAttach(manifest.registration());
      } catch (RuntimeException failure) {
        return denied(Reason.REPLAY_STORE_FAILURE);
      }
      if (registration == null) {
        return denied(Reason.REPLAY_STORE_FAILURE);
      }
      if (registration == RegistrationResult.CONFLICT) {
        return denied(Reason.REPLAYED);
      }
      if (registration == RegistrationResult.ENVIRONMENT_GENERATION_STALE) {
        return denied(Reason.ENVIRONMENT_GENERATION_STALE);
      }
      if (registration == RegistrationResult.ENVIRONMENT_GENERATION_CONFLICT) {
        return denied(Reason.ENVIRONMENT_GENERATION_CONFLICT);
      }
      if (manifest.expired() && registration != RegistrationResult.ATTACHED_EXISTING) {
        return denied(Reason.REPLAYED);
      }
      ActivationGrant grant = manifest.grant();
      ActivationIdentity identity = identity(grant);
      LifecycleState state;
      try {
            state = lifecycleStore.refresh(identity, grant.expiresAt(), clock.instant()).state();
      } catch (RuntimeException failure) {
        return denied(Reason.REPLAY_STORE_FAILURE);
      }
      if (manifest.expired()) {
        if (state != LifecycleState.DRAIN_ONLY
            || !drainCommand.admittedAt().isBefore(grant.expiresAt())
            || !hasAcceptedDrainCommand(identity, drainCommand, grant.expiresAt())) {
          return denied(Reason.DRAIN_PROOF_REQUIRED);
        }
      } else if (state != LifecycleState.ACTIVE) {
        return denied(lifecycleReason(state));
      }
      return new ArmedAuthority(
          grant, caseLedger, lifecycleStore, manifest.verifiedFixtureSet(), clock);
    } catch (Rejected failure) {
      return denied(failure.reason());
    } catch (RuntimeException failure) {
      return denied(Reason.MALFORMED_MANIFEST);
    }
  }

  private ParsedManifest verify(String compactJws) {
    if (compactJws.length() > MAXIMUM_JWS_CHARACTERS) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    String[] segments = compactJws.split("\\.", -1);
    if (segments.length != 3) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    ObjectNode header = parseCanonicalObject(decodeSegment(segments[0], MAXIMUM_HEADER_BYTES));
    ObjectNode payload = parseCanonicalObject(decodeSegment(segments[1], MAXIMUM_PAYLOAD_BYTES));
    requireExactFields(header, HEADER_FIELDS);
    if (!"ES256".equals(requiredText(header, "alg"))
        || !ProductionActivationContract.JWS_TYPE.equals(requiredText(header, "typ"))) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
    String keyId;
    try {
      keyId = ProductionActivationContract.keyId(requiredText(header, "kid"));
    } catch (IllegalArgumentException failure) {
      throw rejected(Reason.UNTRUSTED_KEY);
    }

    ManifestBindings bindings = parseManifest(payload);
    requireManifestHash(payload, bindings.manifestHash());
    ECPublicKey publicKey =
        publicKeys.resolve(keyId).orElseThrow(() -> rejected(Reason.UNTRUSTED_KEY));
    verifySignature(segments, publicKey);
    boolean expired = requireTimeWindow(bindings.issuedAt(), bindings.expiresAt());
    ProductionActivationExpectedRuntime expectedRuntime =
        measureRuntime(
            new MeasurementChallenge(keyId, bindings.nonce(), publicKeyFingerprint(publicKey)));
    requireRuntime(bindings, expectedRuntime);
    Optional<VerifiedFixtureSet> verifiedFixtureSet = verifyFixtureSet(bindings, expectedRuntime);

    ActivationGrant grant =
        new ActivationGrant(
            bindings.activationId(),
            bindings.manifestHash(),
            ProductionActivationContract.LANE,
            bindings.environmentId(),
            bindings.environmentGeneration(),
            bindings.candidateSha(),
            bindings.tenantSurrogate(),
            bindings.caseScope(),
            bindings.allowedRoomTypes(),
            bindings.buildBindings(),
            bindings.graphBinding(),
            bindings.imageDigests(),
            bindings.temporalNamespace(),
            bindings.databaseIdentities(),
            true,
            false,
            false,
            false,
            bindings.issuedAt(),
            bindings.expiresAt());
    Registration registration =
        new Registration(
            bindings.environmentId(),
            bindings.environmentGeneration(),
            bindings.activationId(),
            bindings.nonce(),
            bindings.manifestHash(),
            new BindingSnapshot(
                bindings.candidateSha(),
                bindings.tenantSurrogate(),
                bindings.caseScope(),
                bindings.allowedRoomTypes(),
                bindings.buildBindings(),
                bindings.graphBinding(),
                bindings.imageDigests(),
                bindings.temporalNamespace(),
                bindings.databaseIdentities(),
                expectedRuntime.syntheticFixtureDeployment(),
                bindings.authorityFacts()),
            bindings.issuedAt(),
            bindings.expiresAt());
    return new ParsedManifest(grant, registration, expired, verifiedFixtureSet);
  }

  private ProductionActivationExpectedRuntime measureRuntime(MeasurementChallenge challenge) {
    try {
      MeasuredRuntime measured = measurementProvider.measure(challenge);
      if (measured == null || measured.runtime() == null || measured.evidence() == null) {
        throw rejected(Reason.RUNTIME_MEASUREMENT_FAILED);
      }
      return measured.runtime();
    } catch (Rejected failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw rejected(Reason.RUNTIME_MEASUREMENT_FAILED);
    }
  }

  private static String publicKeyFingerprint(ECPublicKey publicKey) {
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
    } catch (GeneralSecurityException failure) {
      throw rejected(Reason.RUNTIME_MEASUREMENT_FAILED);
    }
  }

  private static ManifestBindings parseManifest(ObjectNode payload) {
    requireExactFields(payload, PAYLOAD_FIELDS);
    if (!ProductionActivationContract.CONTRACT_VERSION.equals(
            requiredText(payload, "contractVersion"))
        || !ProductionActivationContract.LANE.equals(requiredText(payload, "executionLane"))) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
    try {
      String activationId =
          ProductionActivationContract.activationId(requiredText(payload, "activationId"));
      String manifestHash =
          ProductionActivationContract.sha256(requiredText(payload, "manifestHash"), "manifestHash");
      String environmentId =
          ProductionActivationContract.identifier(
              requiredText(payload, "environmentId"), "environmentId");
      long environmentGeneration = requiredGeneration(payload, "environmentGeneration");
      String candidateSha =
          ProductionActivationContract.candidateSha(requiredText(payload, "candidateSha"));
      Instant issuedAt = requiredInstant(payload, "issuedAt");
      Instant expiresAt = requiredInstant(payload, "expiresAt");
      String nonce = ProductionActivationContract.nonce(requiredText(payload, "nonce"));
      String tenant =
          ProductionActivationContract.identifier(
              requiredText(payload, "tenantSurrogate"), "tenantSurrogate");
      CaseScope caseScope = requiredCaseScope(payload);
      Set<RoomType> roomTypes = requiredRoomTypes(payload);
      BuildBindings builds = requiredBuildBindings(payload);
      GraphBinding graph = requiredGraphBinding(payload);
      ImageDigests images = requiredImageDigests(payload);
      String namespace =
          ProductionActivationContract.identifier(
              requiredText(payload, "temporalNamespace"), "temporalNamespace");
      DatabaseIdentities databases = requiredDatabaseIdentities(payload);
      MeasuredAuthorityFacts authorityFacts = requiredAuthorityFacts(payload);
      return new ManifestBindings(
          activationId,
          manifestHash,
          environmentId,
          environmentGeneration,
          candidateSha,
          issuedAt,
          expiresAt,
          nonce,
          tenant,
          caseScope,
          roomTypes,
          builds,
          graph,
          images,
          namespace,
          databases,
          authorityFacts);
    } catch (Rejected failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
  }

  private static void requireManifestHash(ObjectNode payload, String claimedHash) {
    ObjectNode preimage = payload.deepCopy();
    preimage.remove("manifestHash");
    if (!ProductionActivationContract.same(ContractJson.sha256Hex(preimage), claimedHash)) {
      throw rejected(Reason.INVALID_MANIFEST_HASH);
    }
  }

  private static CaseScope requiredCaseScope(ObjectNode payload) {
    ObjectNode scope = requiredObject(payload, "caseScope");
    String mode = requiredText(scope, "mode");
    if ("EXPLICIT_CASE_IDS".equals(mode)) {
      requireExactFields(scope, EXPLICIT_CASE_SCOPE_FIELDS);
      ArrayNode cases = requiredArray(scope, "allowedCaseIds");
      if (cases.isEmpty() || cases.size() > 100) {
        throw rejected(Reason.WRONG_CONTRACT);
      }
      Set<String> values = new HashSet<>();
      for (JsonNode item : cases) {
        if (!item.isTextual()) {
          throw rejected(Reason.WRONG_CONTRACT);
        }
        values.add(ProductionActivationContract.caseId(item.textValue()));
      }
      if (values.size() != cases.size()) {
        throw rejected(Reason.WRONG_CONTRACT);
      }
      return new ExplicitCaseIds(values);
    }
    if ("ISOLATED_SYNTHETIC_NEW_CASES".equals(mode)) {
      requireExactFields(scope, SYNTHETIC_CASE_SCOPE_FIELDS);
      return new IsolatedSyntheticNewCases(
          requiredText(scope, "caseIdPrefix"),
          Math.toIntExact(requiredGeneration(scope, "maxCases")),
          requiredText(scope, "fixtureSetId"),
          requiredText(scope, "fixtureSetHash"),
          requiredBoolean(scope, "containsRealCaseOrPartyData"),
          requiredBoolean(scope, "externalEffectsAllowed"));
    }
    throw rejected(Reason.WRONG_CONTRACT);
  }

  private static Set<RoomType> requiredRoomTypes(ObjectNode payload) {
    return roomTypes(requiredArray(payload, "allowedRoomTypes"), 1, 4);
  }

  private static Set<RoomType> roomTypes(ArrayNode rooms, int minimum, int maximum) {
    if (rooms.size() < minimum || rooms.size() > maximum) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
    EnumSet<RoomType> result = EnumSet.noneOf(RoomType.class);
    for (JsonNode room : rooms) {
      if (!room.isTextual()) {
        throw rejected(Reason.WRONG_CONTRACT);
      }
      RoomType roomType;
      try {
        roomType = RoomType.valueOf(room.textValue());
      } catch (IllegalArgumentException failure) {
        throw rejected(Reason.WRONG_CONTRACT);
      }
      if (!result.add(roomType)) {
        throw rejected(Reason.WRONG_CONTRACT);
      }
    }
    return Set.copyOf(result);
  }

  private static BuildBindings requiredBuildBindings(ObjectNode payload) {
    ObjectNode builds = requiredObject(payload, "buildBindings");
    requireExactFields(builds, BUILD_BINDING_FIELDS);
    return new BuildBindings(
        requiredText(builds, "caseBuildId"),
        requiredText(builds, "controlBuildId"),
        requiredText(builds, "agentBuildId"));
  }

  private static GraphBinding requiredGraphBinding(ObjectNode payload) {
    ObjectNode graph = requiredObject(payload, "graphBinding");
    requireExactFields(graph, GRAPH_BINDING_FIELDS);
    String bindingHash = requiredText(graph, "bindingHash");
    ObjectNode preimage = graph.deepCopy();
    preimage.remove("bindingHash");
    if (!ProductionActivationContract.same(ContractJson.sha256Hex(preimage), bindingHash)) {
      throw rejected(Reason.INVALID_GRAPH_BINDING_HASH);
    }
    return new GraphBinding(
        requiredText(graph, "key"),
        requiredText(graph, "version"),
        requiredText(graph, "checkpointSchemaVersion"),
        bindingHash,
        requiredText(graph, "codeBuildId"));
  }

  private static ImageDigests requiredImageDigests(ObjectNode payload) {
    ObjectNode images = requiredObject(payload, "imageDigests");
    requireExactFields(images, IMAGE_DIGEST_FIELDS);
    return new ImageDigests(
        requiredText(images, "javaApi"),
        requiredText(images, "temporalControlWorker"),
        requiredText(images, "temporalAgentWorker"),
        requiredText(images, "pythonAgent"),
        requiredText(images, "frontend"));
  }

  private static DatabaseIdentities requiredDatabaseIdentities(ObjectNode payload) {
    ObjectNode databases = requiredObject(payload, "databaseIdentities");
    requireExactFields(databases, DATABASE_IDENTITIES_FIELDS);
    return new DatabaseIdentities(
        requiredDatabaseIdentity(databases, "domain"),
        requiredDatabaseIdentity(databases, "graph"));
  }

  private static DatabaseIdentity requiredDatabaseIdentity(ObjectNode parent, String field) {
    ObjectNode database = requiredObject(parent, field);
    requireExactFields(database, DATABASE_IDENTITY_FIELDS);
    return new DatabaseIdentity(
        requiredText(database, "clusterIdentity"),
        requiredText(database, "databaseIdentity"),
        requiredText(database, "runtimePrincipalIdentity"));
  }

  private static MeasuredAuthorityFacts requiredAuthorityFacts(ObjectNode payload) {
    ObjectNode authority = requiredObject(payload, "authority");
    requireExactFields(authority, AUTHORITY_FIELDS);
    String environmentClass = requiredText(authority, "environmentClass");
    String graphOutputAuthority = requiredText(authority, "graphOutputAuthority");
    boolean graphCredentials = requiredBoolean(authority, "graphDomainCredentialsPresent");
    boolean graphWrites = requiredBoolean(authority, "graphDomainWriteAllowed");
    String formalWriter = requiredText(authority, "formalWriter");
    boolean javaCommit = requiredBoolean(authority, "javaDomainCommitAllowed");
    boolean externalEffects = requiredBoolean(authority, "externalEffectsAllowed");
    boolean productionTraffic = requiredBoolean(authority, "productionTrafficAllowed");
    boolean productionPromotion = requiredBoolean(authority, "productionPromotionAuthority");
    boolean migrationPromotion = requiredBoolean(authority, "migrationPromotionAuthority");
    ObjectNode defaults = requiredObject(payload, "productionDefaults");
    requireExactFields(defaults, PRODUCTION_DEFAULT_FIELDS);
    String formalCaseSelector = requiredText(defaults, "formalCaseSelector");
    String targetActivation = requiredText(defaults, "productionActivation");
    if (!"ISOLATED_PREPRODUCTION".equals(environmentClass)
        || !"PROPOSAL_ONLY".equals(graphOutputAuthority)
        || graphCredentials
        || graphWrites
        || !"JAVA_FINALIZER_ONLY".equals(formalWriter)
        || !javaCommit
        || externalEffects
        || productionTraffic
        || productionPromotion
        || migrationPromotion
        || !"LEGACY".equals(formalCaseSelector)
        || !"DISABLED".equals(targetActivation)) {
      throw rejected(Reason.AUTHORITY_VIOLATION);
    }
    return new MeasuredAuthorityFacts(
        true,
        environmentClass,
        graphOutputAuthority,
        graphCredentials,
        false,
        graphWrites,
        formalWriter,
        javaCommit,
        externalEffects,
        productionTraffic,
        productionPromotion,
        migrationPromotion,
        formalCaseSelector,
        targetActivation);
  }

  private boolean requireTimeWindow(Instant issuedAt, Instant expiresAt) {
    Duration lifetime;
    try {
      lifetime = Duration.between(issuedAt, expiresAt);
    } catch (RuntimeException failure) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    if (!isPermittedLifetime(lifetime)) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
    Instant now = clock.instant();
    if (issuedAt.isAfter(now)) {
      throw rejected(Reason.NOT_YET_VALID);
    }
    return !now.isBefore(expiresAt);
  }

  static boolean isPermittedLifetime(Duration lifetime) {
    return lifetime != null
        && !lifetime.isZero()
        && !lifetime.isNegative()
        && lifetime.compareTo(MAXIMUM_LIFETIME) <= 0;
  }

  private static void requireRuntime(
      ManifestBindings manifest, ProductionActivationExpectedRuntime expected) {
    if (!ProductionActivationContract.same(manifest.environmentId(), expected.environmentId())
        || manifest.environmentGeneration() != expected.environmentGeneration()
        || !ProductionActivationContract.same(manifest.candidateSha(), expected.candidateSha())
        || !ProductionActivationContract.same(manifest.tenantSurrogate(), expected.tenantSurrogate())
        || !manifest.caseScope().equals(expected.caseScope())
        || !manifest.allowedRoomTypes().equals(expected.allowedRoomTypes())
        || !manifest.buildBindings().equals(expected.buildBindings())
        || !manifest.graphBinding().equals(expected.graphBinding())
        || !manifest.imageDigests().equals(expected.imageDigests())
        || !ProductionActivationContract.same(
            manifest.temporalNamespace(), expected.temporalNamespace())
        || !manifest.databaseIdentities().equals(expected.databaseIdentities())
        || !manifest.authorityFacts().equals(expected.authorityFacts())) {
      throw rejected(Reason.WRONG_RUNTIME);
    }
  }

  private Optional<VerifiedFixtureSet> verifyFixtureSet(
      ManifestBindings manifest, ProductionActivationExpectedRuntime expected) {
    if (!(manifest.caseScope() instanceof IsolatedSyntheticNewCases synthetic)) {
      return Optional.empty();
    }
    SyntheticFixtureDeployment deployment =
        expected.syntheticFixtureDeployment().orElseThrow(() -> rejected(Reason.WRONG_RUNTIME));
    ProductionSyntheticFixtureSource.ConfiguredFixture loaded;
    try {
      loaded = fixtureSource.loadConfigured(synthetic.fixtureSetId());
    } catch (RuntimeException failure) {
      throw rejected(Reason.WRONG_RUNTIME);
    }
    if (loaded == null
        || !ProductionActivationContract.same(
            loaded.readOnlyPathBinding(), deployment.readOnlyPathBinding())) {
      throw rejected(Reason.WRONG_RUNTIME);
    }
    byte[] bytes = loaded.bytes();
    ObjectNode fixture = parseCanonicalObject(bytes);
    requireExactFields(fixture, FIXTURE_SET_FIELDS);
    if (!"production-runtime-synthetic-fixture-set.v1".equals(requiredText(fixture, "schemaVersion"))
        || !ProductionActivationContract.same(
            requiredText(fixture, "fixtureSetId"), synthetic.fixtureSetId())
        || !ProductionActivationContract.same(
            requiredText(fixture, "caseIdPrefix"), synthetic.caseIdPrefix())
        || requiredGeneration(fixture, "maximumCases") != synthetic.maxCases()) {
      throw rejected(Reason.WRONG_RUNTIME);
    }
    Set<RoomType> fixtureRooms = roomTypes(requiredArray(fixture, "roomTypes"), 4, 4);
    if (!fixtureRooms.equals(Set.of(RoomType.values()))
        || !fixtureRooms.equals(manifest.allowedRoomTypes())) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
    ArrayNode scenarios = requiredArray(fixture, "scenarios");
    if (scenarios.size() < 4 || scenarios.size() > 16) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
    for (JsonNode item : scenarios) {
      if (!(item instanceof ObjectNode scenario)) {
        throw rejected(Reason.WRONG_CONTRACT);
      }
      requireExactFields(scenario, FIXTURE_SCENARIO_FIELDS);
      ProductionActivationContract.identifier(requiredText(scenario, "fixtureId"), "fixtureId");
      try {
        RoomType.valueOf(requiredText(scenario, "roomType"));
      } catch (IllegalArgumentException failure) {
        throw rejected(Reason.WRONG_CONTRACT);
      }
      ProductionActivationContract.identifier(
          requiredText(scenario, "inputSchemaVersion"), "inputSchemaVersion");
      ProductionActivationContract.sha256(requiredText(scenario, "inputHash"), "inputHash");
      String terminalClass = requiredText(scenario, "expectedTerminalClass");
      if (!Set.of("NEEDS_INPUT", "COMPLETED", "NEEDS_REVIEW").contains(terminalClass)) {
        throw rejected(Reason.WRONG_CONTRACT);
      }
    }
    String actualHash = ContractJson.sha256Hex(fixture);
    if (!ProductionActivationContract.same(actualHash, synthetic.fixtureSetHash())
        || !ProductionActivationContract.same(actualHash, deployment.measuredCanonicalHash())) {
      throw rejected(Reason.WRONG_RUNTIME);
    }
    return Optional.of(new VerifiedFixtureSet(actualHash, loaded.readOnlyPathBinding(), bytes));
  }

  private void verifySignature(String[] segments, ECPublicKey publicKey) {
    byte[] signatureBytes = decodeSegment(segments[2], 64);
    if (signatureBytes.length != 64) {
      throw rejected(Reason.INVALID_SIGNATURE);
    }
    try {
      Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
      verifier.initVerify(publicKey);
      verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(signatureBytes)) {
        throw rejected(Reason.INVALID_SIGNATURE);
      }
    } catch (GeneralSecurityException failure) {
      throw rejected(Reason.INVALID_SIGNATURE);
    }
  }

  private ObjectNode parseCanonicalObject(byte[] bytes) {
    try (JsonParser parser = mapper.createParser(bytes)) {
      JsonNode parsed = mapper.readTree(parser);
      if (!(parsed instanceof ObjectNode object) || parser.nextToken() != null) {
        throw rejected(Reason.MALFORMED_MANIFEST);
      }
      if (!MessageDigest.isEqual(bytes, ContractJson.canonicalize(object))) {
        throw rejected(Reason.NON_CANONICAL_MANIFEST);
      }
      return object;
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException failure) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
  }

  private static byte[] decodeSegment(String segment, int maximumBytes) {
    if (segment == null || !BASE64_URL.matcher(segment).matches()) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    try {
      byte[] decoded = BASE64_URL_DECODER.decode(segment);
      if (decoded.length == 0
          || decoded.length > maximumBytes
          || !ProductionActivationContract.same(
              segment, BASE64_URL_ENCODER.encodeToString(decoded))) {
        throw rejected(Reason.MALFORMED_MANIFEST);
      }
      return decoded;
    } catch (IllegalArgumentException failure) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
  }

  private static ObjectNode requiredObject(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (!(value instanceof ObjectNode object)) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    return object;
  }

  private static ArrayNode requiredArray(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (!(value instanceof ArrayNode array)) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    return array;
  }

  private static String requiredText(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual()) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    return value.textValue();
  }

  private static long requiredGeneration(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    long result = value.longValue();
    if (result < 1 || result > MAXIMUM_SAFE_JSON_INTEGER) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
    return result;
  }

  private static boolean requiredBoolean(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isBoolean()) {
      throw rejected(Reason.MALFORMED_MANIFEST);
    }
    return value.booleanValue();
  }

  private static Instant requiredInstant(ObjectNode parent, String field) {
    try {
      return Instant.parse(requiredText(parent, field));
    } catch (DateTimeParseException failure) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
  }

  private static void requireExactFields(ObjectNode object, Set<String> expectedFields) {
    Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expectedFields)) {
      throw rejected(Reason.WRONG_CONTRACT);
    }
  }

  private static void requireZeroClockSkew(Duration clockSkew) {
    Objects.requireNonNull(clockSkew, "clockSkew");
    if (!clockSkew.isZero()) {
      throw new IllegalArgumentException("activation validation policy requires zero clock skew");
    }
  }

  private boolean hasAcceptedDrainCommand(
      ActivationIdentity identity, DrainAcceptedCommand command, Instant expiresAt) {
    try {
      return lifecycleStore.hasAcceptedCommandBeforeExpiry(identity, command, expiresAt);
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private static ActivationIdentity identity(ActivationGrant grant) {
    return new ActivationIdentity(
        grant.environmentId(),
        grant.environmentGeneration(),
        grant.activationId(),
        grant.manifestHash());
  }

  private static Reason lifecycleReason(LifecycleState state) {
    if (state == LifecycleState.DRAIN_ONLY) {
      return Reason.DRAIN_PROOF_REQUIRED;
    }
    if (state == LifecycleState.DRAINED) {
      return Reason.DRAINED;
    }
    if (state == LifecycleState.REVOKED_TERMINAL) {
      return Reason.REVOKED;
    }
    return Reason.REPLAY_STORE_FAILURE;
  }

  private static ProductionActivationAuthority denied(Reason reason) {
    ActivationDecision decision = ActivationDecision.denied(reason);
    return request -> decision;
  }

  private static Rejected rejected(Reason reason) {
    return new Rejected(reason);
  }

  private record ManifestBindings(
      String activationId,
      String manifestHash,
      String environmentId,
      long environmentGeneration,
      String candidateSha,
      Instant issuedAt,
      Instant expiresAt,
      String nonce,
      String tenantSurrogate,
      CaseScope caseScope,
      Set<RoomType> allowedRoomTypes,
      BuildBindings buildBindings,
      GraphBinding graphBinding,
      ImageDigests imageDigests,
      String temporalNamespace,
      DatabaseIdentities databaseIdentities,
      MeasuredAuthorityFacts authorityFacts) {}

  private record ParsedManifest(
      ActivationGrant grant,
      Registration registration,
      boolean expired,
      Optional<VerifiedFixtureSet> verifiedFixtureSet) {}

  private record VerifiedFixtureSet(
      String canonicalHash, String readOnlyPathBinding, byte[] canonicalBytes) {

    private VerifiedFixtureSet {
      ProductionActivationContract.sha256(canonicalHash, "fixtureSetHash");
      ProductionSyntheticFixtureSource.requirePathBinding(readOnlyPathBinding);
      canonicalBytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }

  private record ArmedAuthority(
      ActivationGrant grant,
      ProductionActivationCaseLedger caseLedger,
      ProductionActivationLifecycleStore lifecycleStore,
      Optional<VerifiedFixtureSet> verifiedFixtureSet,
      Clock clock)
      implements ProductionActivationAuthority {

    private ArmedAuthority {
      Objects.requireNonNull(grant, "grant");
      Objects.requireNonNull(caseLedger, "caseLedger");
      Objects.requireNonNull(lifecycleStore, "lifecycleStore");
      verifiedFixtureSet = Objects.requireNonNull(verifiedFixtureSet, "verifiedFixtureSet");
      if ((grant.caseScope() instanceof IsolatedSyntheticNewCases)
          != verifiedFixtureSet.isPresent()) {
        throw new IllegalArgumentException("synthetic activation fixture cache is inconsistent");
      }
      Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ActivationDecision authorize(ActivationRequest request) {
      LifecycleState state;
      Instant now = clock.instant();
      try {
            state = lifecycleStore.refresh(identity(grant), grant.expiresAt(), now).state();
      } catch (RuntimeException failure) {
        return ActivationDecision.denied(Reason.REPLAY_STORE_FAILURE);
      }
      if (state == null || state == LifecycleState.REGISTERED) {
        return ActivationDecision.denied(Reason.REPLAY_STORE_FAILURE);
      }
      if (!now.isBefore(grant.expiresAt()) && state == LifecycleState.ACTIVE) {
        return ActivationDecision.denied(Reason.REPLAY_STORE_FAILURE);
      }
      if (state == LifecycleState.DRAINED) {
        return ActivationDecision.denied(Reason.DRAINED);
      }
      if (state == LifecycleState.REVOKED_TERMINAL) {
        return ActivationDecision.denied(Reason.REVOKED);
      }
      if (state == LifecycleState.DRAIN_ONLY && !validDrainRequest(request)) {
        return ActivationDecision.denied(Reason.DRAIN_PROOF_REQUIRED);
      }
      if (state == LifecycleState.ACTIVE
          && request != null
          && request.purpose() == ActivationPurpose.DRAIN_ACCEPTED_COMMAND
          && !validDrainRequest(request)) {
        return ActivationDecision.denied(Reason.DRAIN_PROOF_REQUIRED);
      }
      if (request == null
          || !ProductionActivationContract.same(
              grant.tenantSurrogate(), request.tenantSurrogate())) {
        return ActivationDecision.denied(Reason.WRONG_TARGET);
      }
      if (!grant.allowedRoomTypes().contains(request.roomType())) {
        return ActivationDecision.denied(Reason.WRONG_TARGET);
      }
      ActivationDecision caseDecision = authorizeCase(request, state);
      if (caseDecision != null) {
        return caseDecision;
      }
      AuthorizationMode mode =
          state == LifecycleState.DRAIN_ONLY
                  || request.purpose() == ActivationPurpose.DRAIN_ACCEPTED_COMMAND
              ? AuthorizationMode.DRAIN_ACCEPTED_COMMAND
              : AuthorizationMode.ACTIVE;
      return ActivationDecision.activated(grant, mode);
    }

    private boolean validDrainRequest(ActivationRequest request) {
      if (request == null
          || request.scope() == ActivationScope.ROOM_SELECTOR
          || request.purpose() != ActivationPurpose.DRAIN_ACCEPTED_COMMAND
          || request.drainAcceptedCommand() == null
          || !request.drainAcceptedCommand().admittedAt().isBefore(grant.expiresAt())) {
        return false;
      }
      try {
        return lifecycleStore.hasAcceptedCommandBeforeExpiry(
            identity(grant), request.drainAcceptedCommand(), grant.expiresAt());
      } catch (RuntimeException failure) {
        return false;
      }
    }

    private ActivationDecision authorizeCase(ActivationRequest request, LifecycleState state) {
      if (grant.caseScope() instanceof ExplicitCaseIds explicit) {
        return explicit.allowedCaseIds().contains(request.caseId())
            ? null
            : ActivationDecision.denied(Reason.WRONG_TARGET);
      }
      IsolatedSyntheticNewCases synthetic = (IsolatedSyntheticNewCases) grant.caseScope();
      VerifiedFixtureSet fixture = verifiedFixtureSet.orElseThrow();
      if (!ProductionActivationContract.same(fixture.canonicalHash(), synthetic.fixtureSetHash())) {
        return ActivationDecision.denied(Reason.CASE_LEDGER_FAILURE);
      }
      if (!request.caseId().startsWith(synthetic.caseIdPrefix())
          || request.caseId().length() == synthetic.caseIdPrefix().length()) {
        return ActivationDecision.denied(Reason.WRONG_TARGET);
      }
      if (request.syntheticCaseSlot() == null
          || request.syntheticCaseSlot() > synthetic.maxCases()) {
        return ActivationDecision.denied(Reason.WRONG_TARGET);
      }
      ProductionActivationCaseLedger.Action action =
          state == LifecycleState.ACTIVE
                  && request.purpose() == ActivationPurpose.NEW_ADMISSION
                  && request.scope() == ActivationScope.ROOM_SELECTOR
              ? ProductionActivationCaseLedger.Action.RESERVE_BEFORE_EPOCH_SELECTION
              : ProductionActivationCaseLedger.Action.REQUIRE_EXISTING;
      ProductionActivationCaseLedger.Reservation reservation =
          new ProductionActivationCaseLedger.Reservation(
              grant.environmentId(),
              grant.environmentGeneration(),
              grant.activationId(),
              request.syntheticCaseSlot(),
              request.caseId(),
              synthetic.caseIdPrefix(),
              synthetic.maxCases(),
              synthetic.fixtureSetId(),
              synthetic.fixtureSetHash());
      ProductionActivationCaseLedger.ReservationResult result;
      try {
        result = caseLedger.apply(action, reservation);
      } catch (RuntimeException failure) {
        return ActivationDecision.denied(Reason.CASE_LEDGER_FAILURE);
      }
      if (result == ProductionActivationCaseLedger.ReservationResult.ALREADY_RESERVED_IDENTICALLY
          || (action == ProductionActivationCaseLedger.Action.RESERVE_BEFORE_EPOCH_SELECTION
              && result == ProductionActivationCaseLedger.ReservationResult.RESERVED)) {
        return null;
      }
      if (result == ProductionActivationCaseLedger.ReservationResult.NOT_RESERVED) {
        return ActivationDecision.denied(Reason.CASE_NOT_RESERVED);
      }
      if (result == ProductionActivationCaseLedger.ReservationResult.CAPACITY_EXHAUSTED) {
        return ActivationDecision.denied(Reason.CASE_CAPACITY_EXHAUSTED);
      }
      if (result
          == ProductionActivationCaseLedger.ReservationResult.GENERATED_CASE_ID_GLOBAL_CONFLICT) {
        return ActivationDecision.denied(Reason.GENERATED_CASE_ID_CONFLICT);
      }
      return ActivationDecision.denied(Reason.CASE_LEDGER_FAILURE);
    }
  }

  private static final class Rejected extends RuntimeException {

    private final Reason reason;

    private Rejected(Reason reason) {
      super(null, null, false, false);
      this.reason = Objects.requireNonNull(reason, "reason");
    }

    private Reason reason() {
      return reason;
    }
  }
}

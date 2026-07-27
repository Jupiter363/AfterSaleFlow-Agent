package com.example.dispute.workflow.targete2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.ActivationDecision.Reason;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.BuildBindings;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.DatabaseIdentity;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.ExplicitCaseIds;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.GraphBinding;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.IsolatedSyntheticNewCases;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.RoomType;
import com.example.dispute.workflow.targete2e.TargetE2eActivationReplayStore.Registration;
import com.example.dispute.workflow.targete2e.TargetE2eActivationReplayStore.RegistrationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TargetE2eActivationManifestVerifierTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
  private static final Instant NOW = Instant.parse("2026-07-27T08:00:00Z");
  private static final String KEY_ID = "target-e2e-activation-2026-07";
  private static final String ACTIVATION_ID = "p9act.v1." + "1".repeat(32);
  private static final String CANDIDATE_SHA = "a".repeat(40);
  private static final String NONCE = "activation-nonce-" + "0".repeat(20);
  private static final String FIXTURE_HASH = "3".repeat(64);
  private static final String CASE_ID = "CASE_EXPLICIT_001";

  private static KeyPair trustedKey;
  private static KeyPair otherKey;

  @BeforeAll
  static void generateKeys() throws Exception {
    trustedKey = keyPair("secp256r1");
    otherKey = keyPair("secp256r1");
  }

  @Test
  void armsTheExactCandidateAndReleasesFrozenBindingsToEveryCallSite() throws Exception {
    RecordingReplayStore replayStore = new RecordingReplayStore();
    TargetE2eActivationAuthority authority =
        verifier(replayStore, TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(payload(), trustedKey, KEY_ID), expectedRuntime());

    ActivationDecision selector = authority.authorize(request(ActivationScope.ROOM_SELECTOR));
    ActivationDecision graph = authority.authorize(request(ActivationScope.GRAPH_CLIENT));
    ActivationDecision agent = authority.authorize(request(ActivationScope.AGENT_RUN));
    ActivationDecision finalizer = authority.authorize(request(ActivationScope.FINALIZER));

    assertThat(selector.allowed()).isTrue();
    assertThat(graph.activationId()).contains(ACTIVATION_ID);
    assertThat(agent.activationId()).contains(ACTIVATION_ID);
    assertThat(finalizer.activationId()).contains(ACTIVATION_ID);
    ActivationGrant grant = finalizer.grant().orElseThrow();
    assertThat(grant.executionLane()).isEqualTo("TARGET_E2E_CANDIDATE");
    assertThat(grant.environmentId()).isEqualTo("target-e2e-env-01");
    assertThat(grant.environmentGeneration()).isEqualTo(17);
    assertThat(grant.candidateSha()).isEqualTo(CANDIDATE_SHA);
    assertThat(grant.buildBindings()).isEqualTo(expectedRuntime().buildBindings());
    assertThat(grant.graphBinding()).isEqualTo(expectedRuntime().graphBinding());
    assertThat(grant.imageDigests()).isEqualTo(expectedRuntime().imageDigests());
    assertThat(grant.databaseIdentities()).isEqualTo(expectedRuntime().databaseIdentities());
    assertThat(grant.javaDomainCommitAllowed()).isTrue();
    assertThat(grant.graphDomainWriteAllowed()).isFalse();
    assertThat(grant.externalEffectsAllowed()).isFalse();
    assertThat(grant.productionTrafficAllowed()).isFalse();
    assertThat(grant.issuedAt()).isEqualTo(NOW.minusSeconds(10));
    assertThat(grant.expiresAt()).isEqualTo(NOW.plusSeconds(3_600));
    assertThat(replayStore.calls()).isEqualTo(1);
  }

  @Test
  void defaultsToDenyForNoAuthorityBeanOrNoManifest() {
    assertThat(TargetE2eActivationAuthority.denyAll().authorize(request(ActivationScope.FINALIZER)))
        .extracting(ActivationDecision::allowed, ActivationDecision::reason)
        .containsExactly(false, Reason.DEFAULT_DENY);
    TargetE2eActivationAuthority missing =
        verifier(new RecordingReplayStore(), TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(null, expectedRuntime());
    assertThat(missing.authorize(request(ActivationScope.AGENT_RUN)).reason())
        .isEqualTo(Reason.DEFAULT_DENY);
  }

  @Test
  void verifiesManifestAndGraphSelfHashesBeforeRegistration() throws Exception {
    RecordingReplayStore replayStore = new RecordingReplayStore();
    ObjectNode wrongManifestHash = payload();
    wrongManifestHash.put("manifestHash", "f".repeat(64));
    ActivationDecision manifestFailure =
        verifier(replayStore, TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(wrongManifestHash, trustedKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(manifestFailure.reason()).isEqualTo(Reason.INVALID_MANIFEST_HASH);

    ObjectNode wrongGraphHash = payload();
    ((ObjectNode) wrongGraphHash.get("graphBinding")).put("bindingHash", "f".repeat(64));
    refreshManifestHash(wrongGraphHash);
    ActivationDecision graphFailure =
        verifier(replayStore, TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(wrongGraphHash, trustedKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(graphFailure.reason()).isEqualTo(Reason.INVALID_GRAPH_BINDING_HASH);
    assertThat(replayStore.calls()).isZero();
  }

  @Test
  void rejectsExpiredFutureAndOverlongManifestsWithoutClockTolerance() throws Exception {
    assertDenied(
        value -> {
          value.put("issuedAt", NOW.minusSeconds(3_600).toString());
          value.put("expiresAt", NOW.toString());
        },
        Reason.EXPIRED);
    assertDenied(
        value -> {
          value.put("issuedAt", NOW.plusSeconds(1).toString());
          value.put("expiresAt", NOW.plusSeconds(300).toString());
        },
        Reason.NOT_YET_VALID);
    assertDenied(
        value -> value.put("expiresAt", NOW.plusSeconds(7_201).toString()), Reason.WRONG_CONTRACT);
  }

  @Test
  void acceptsOnlyExactCandidateEnvironmentGenerationAndNamespace() throws Exception {
    assertDenied(value -> value.put("candidateSha", "d".repeat(40)), Reason.WRONG_RUNTIME);
    assertDenied(value -> value.put("environmentId", "another-env"), Reason.WRONG_RUNTIME);
    assertDenied(value -> value.put("environmentGeneration", 18), Reason.WRONG_RUNTIME);
    assertDenied(
        value -> value.put("temporalNamespace", "another-namespace"), Reason.WRONG_RUNTIME);
  }

  @Test
  void acceptsOnlyExactBuildGraphImageAndDatabaseBindings() throws Exception {
    assertDenied(
        value -> ((ObjectNode) value.get("buildBindings")).put("agentBuildId", "agent-v10"),
        Reason.WRONG_RUNTIME);
    assertDenied(
        value -> ((ObjectNode) value.get("graphBinding")).put("version", "graph-v10"),
        Reason.WRONG_RUNTIME);
    assertDenied(
        value ->
            ((ObjectNode) value.get("imageDigests")).put("pythonAgent", "sha256:" + "9".repeat(64)),
        Reason.WRONG_RUNTIME);
    assertDenied(
        value ->
            ((ObjectNode) ((ObjectNode) value.get("databaseIdentities")).get("domain"))
                .put("databaseIdentity", "domain-db-other"),
        Reason.WRONG_RUNTIME);
  }

  @Test
  void rejectsWrongVersionLaneTenantRoomAndCaseScope() throws Exception {
    assertDenied(
        value -> value.put("contractVersion", "target-e2e-activation.v2"), Reason.WRONG_CONTRACT);
    assertDenied(value -> value.put("executionLane", "SHADOW"), Reason.WRONG_CONTRACT);
    assertDenied(value -> value.put("tenantSurrogate", "tenant-other"), Reason.WRONG_RUNTIME);

    TargetE2eActivationAuthority authority =
        verifier(new RecordingReplayStore(), TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(payload(), trustedKey, KEY_ID), expectedRuntime());
    assertThat(
            authority
                .authorize(
                    new ActivationRequest(
                        ActivationScope.AGENT_RUN, "tenant-e2e", RoomType.REVIEW, CASE_ID))
                .reason())
        .isEqualTo(Reason.WRONG_TARGET);
    assertThat(
            authority
                .authorize(
                    new ActivationRequest(
                        ActivationScope.AGENT_RUN,
                        "tenant-e2e",
                        RoomType.INTAKE,
                        "CASE_NOT_ALLOWED"))
                .reason())
        .isEqualTo(Reason.WRONG_TARGET);
  }

  @Test
  void rejectsAuthorityCeilingProductionDefaultAndDatabaseSeparationViolations() throws Exception {
    assertDenied(
        value -> ((ObjectNode) value.get("authority")).put("graphDomainWriteAllowed", true),
        Reason.AUTHORITY_VIOLATION);
    assertDenied(
        value -> ((ObjectNode) value.get("authority")).put("productionTrafficAllowed", true),
        Reason.AUTHORITY_VIOLATION);
    assertDenied(
        value ->
            ((ObjectNode) value.get("productionDefaults")).put("targetE2EActivation", "ENABLED"),
        Reason.AUTHORITY_VIOLATION);

    ObjectNode collision = payload();
    ((ObjectNode) collision.get("databaseIdentities"))
        .set("graph", ((ObjectNode) collision.get("databaseIdentities")).get("domain").deepCopy());
    refreshHashes(collision);
    ActivationDecision collisionDecision =
        verifier(new RecordingReplayStore(), TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(collision, trustedKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(collisionDecision.reason()).isEqualTo(Reason.WRONG_CONTRACT);
  }

  @Test
  void verifiesTrustedP256KeyAndExactJwtProtectedHeader() throws Exception {
    RecordingReplayStore replayStore = new RecordingReplayStore();
    ActivationDecision untrusted =
        verifier(replayStore, TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(payload(), trustedKey, "untrusted-key"), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(untrusted.reason()).isEqualTo(Reason.UNTRUSTED_KEY);

    ActivationDecision invalidSignature =
        verifier(replayStore, TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(payload(), otherKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(invalidSignature.reason()).isEqualTo(Reason.INVALID_SIGNATURE);

    ActivationDecision wrongType =
        verifier(replayStore, TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(
                sign(payload(), trustedKey, KEY_ID, "target-e2e-activation+jws"), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(wrongType.reason()).isEqualTo(Reason.WRONG_CONTRACT);
    assertThat(replayStore.calls()).isZero();
  }

  @Test
  void requiresCanonicalUniqueMemberAndClosedSchemaJson() throws Exception {
    byte[] prettyPayload = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload());
    ActivationDecision nonCanonical =
        verifier(new RecordingReplayStore(), TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(signRaw(prettyPayload, trustedKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(nonCanonical.reason()).isEqualTo(Reason.NON_CANONICAL_MANIFEST);

    ActivationDecision malformed =
        verifier(new RecordingReplayStore(), TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm("not-a-compact-jws", expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(malformed.reason()).isEqualTo(Reason.MALFORMED_MANIFEST);

    ObjectNode expanded = payload();
    expanded.put("bypass", true);
    refreshManifestHash(expanded);
    ActivationDecision unknownField =
        verifier(new RecordingReplayStore(), TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(expanded, trustedKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(unknownField.reason()).isEqualTo(Reason.WRONG_CONTRACT);
  }

  @Test
  void attachesIdenticalReplicaButRejectsActivationIdOrNonceBindingConflict() throws Exception {
    RecordingReplayStore replayStore = new RecordingReplayStore();
    TargetE2eActivationManifestVerifier verifier =
        verifier(replayStore, TargetE2eActivationCaseLedger.denyAll(), fixedClock());
    String original = sign(payload(), trustedKey, KEY_ID);

    assertThat(
            verifier
                .arm(original, expectedRuntime())
                .authorize(request(ActivationScope.FINALIZER))
                .allowed())
        .isTrue();
    assertThat(
            verifier
                .arm(original, expectedRuntime())
                .authorize(request(ActivationScope.FINALIZER))
                .allowed())
        .isTrue();

    ObjectNode conflict = payload();
    conflict.put("nonce", "activation-nonce-" + "9".repeat(20));
    refreshManifestHash(conflict);
    ActivationDecision replay =
        verifier
            .arm(sign(conflict, trustedKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.FINALIZER));
    assertThat(replay.reason()).isEqualTo(Reason.REPLAYED);
    assertThat(replayStore.calls()).isEqualTo(3);
  }

  @Test
  void failsClosedWhenReplayRegistrationFails() throws Exception {
    TargetE2eActivationReplayStore failing =
        registration -> {
          throw new IllegalStateException("store unavailable");
        };
    ActivationDecision failure =
        verifier(failing, TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(payload(), trustedKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(failure.reason()).isEqualTo(Reason.REPLAY_STORE_FAILURE);
  }

  @Test
  void reservesSyntheticCaseBeforeEpochSelectionAndRequiresItDownstream() throws Exception {
    RecordingCaseLedger caseLedger = new RecordingCaseLedger();
    TargetE2eActivationAuthority authority =
        verifier(new RecordingReplayStore(), caseLedger, fixedClock())
            .arm(sign(syntheticPayload(), trustedKey, KEY_ID), syntheticRuntime());
    ActivationRequest graphBeforeSelector =
        new ActivationRequest(
            ActivationScope.GRAPH_CLIENT, "tenant-e2e", RoomType.INTAKE, "CASE_NEW_001");
    assertThat(authority.authorize(graphBeforeSelector).reason())
        .isEqualTo(Reason.CASE_NOT_RESERVED);

    ActivationRequest selector =
        new ActivationRequest(
            ActivationScope.ROOM_SELECTOR, "tenant-e2e", RoomType.INTAKE, "CASE_NEW_001");
    assertThat(authority.authorize(selector).allowed()).isTrue();
    assertThat(authority.authorize(graphBeforeSelector).allowed()).isTrue();
    assertThat(
            authority
                .authorize(
                    new ActivationRequest(
                        ActivationScope.FINALIZER, "tenant-e2e", RoomType.INTAKE, "WRONG_001"))
                .reason())
        .isEqualTo(Reason.WRONG_TARGET);
  }

  @Test
  void enforcesSyntheticCaseCapacityAndNoRealDataOrWildcardPrefix() throws Exception {
    RecordingCaseLedger caseLedger = new RecordingCaseLedger();
    TargetE2eActivationAuthority authority =
        verifier(new RecordingReplayStore(), caseLedger, fixedClock())
            .arm(sign(syntheticPayload(), trustedKey, KEY_ID), syntheticRuntime());
    assertThat(
            authority
                .authorize(
                    new ActivationRequest(
                        ActivationScope.ROOM_SELECTOR,
                        "tenant-e2e",
                        RoomType.INTAKE,
                        "CASE_NEW_001"))
                .allowed())
        .isTrue();
    assertThat(
            authority
                .authorize(
                    new ActivationRequest(
                        ActivationScope.ROOM_SELECTOR,
                        "tenant-e2e",
                        RoomType.INTAKE,
                        "CASE_NEW_002"))
                .reason())
        .isEqualTo(Reason.CASE_CAPACITY_EXHAUSTED);

    assertThatThrownBy(
            () -> new IsolatedSyntheticNewCases("*", 1, "fixtures-v1", FIXTURE_HASH, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new IsolatedSyntheticNewCases(
                    "CASE_NEW_", 1, "fixtures-v1", FIXTURE_HASH, true, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rechecksExpiryOnEveryAuthorityDecision() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    ObjectNode shortLived = payload();
    shortLived.put("expiresAt", NOW.plusSeconds(60).toString());
    refreshManifestHash(shortLived);
    TargetE2eActivationAuthority authority =
        verifier(new RecordingReplayStore(), TargetE2eActivationCaseLedger.denyAll(), clock)
            .arm(sign(shortLived, trustedKey, KEY_ID), expectedRuntime());
    assertThat(authority.authorize(request(ActivationScope.FINALIZER)).allowed()).isTrue();
    clock.advance(Duration.ofSeconds(60));
    assertThat(authority.authorize(request(ActivationScope.FINALIZER)).reason())
        .isEqualTo(Reason.EXPIRED);
  }

  @Test
  void permitsOnlyLocalProfilesAndFrozenZeroSkewPolicy() throws Exception {
    TargetE2eActivationExpectedRuntime target = expectedRuntime();
    TargetE2eActivationExpectedRuntime local = withProfile(target, "local");
    assertThat(
            verifier(
                    new RecordingReplayStore(),
                    TargetE2eActivationCaseLedger.denyAll(),
                    fixedClock())
                .arm(sign(payload(), trustedKey, KEY_ID), local)
                .authorize(request(ActivationScope.AGENT_RUN))
                .allowed())
        .isTrue();
    assertThatThrownBy(() -> withProfile(target, "prod"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("local or target-e2e");
    assertThatThrownBy(
            () ->
                new TargetE2eActivationManifestVerifier(
                    keySet(),
                    new RecordingReplayStore(),
                    TargetE2eActivationCaseLedger.denyAll(),
                    fixedClock(),
                    Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero clock skew");
  }

  @Test
  void rejectsNonP256PublicKeys() throws Exception {
    KeyPair p384 = keyPair("secp384r1");
    assertThatThrownBy(
            () ->
                TargetE2eActivationPublicKeySet.allowlisted(
                    Map.of("wrong-curve", (java.security.interfaces.ECPublicKey) p384.getPublic())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("P-256");
  }

  private static void assertDenied(Consumer<ObjectNode> mutation, Reason expectedReason)
      throws Exception {
    ObjectNode changed = payload();
    mutation.accept(changed);
    refreshHashes(changed);
    ActivationDecision decision =
        verifier(new RecordingReplayStore(), TargetE2eActivationCaseLedger.denyAll(), fixedClock())
            .arm(sign(changed, trustedKey, KEY_ID), expectedRuntime())
            .authorize(request(ActivationScope.AGENT_RUN));
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo(expectedReason);
  }

  private static TargetE2eActivationManifestVerifier verifier(
      TargetE2eActivationReplayStore replayStore,
      TargetE2eActivationCaseLedger caseLedger,
      Clock clock) {
    return new TargetE2eActivationManifestVerifier(
        keySet(), replayStore, caseLedger, clock, Duration.ZERO);
  }

  private static TargetE2eActivationPublicKeySet keySet() {
    return TargetE2eActivationPublicKeySet.allowlisted(
        Map.of(KEY_ID, (java.security.interfaces.ECPublicKey) trustedKey.getPublic()));
  }

  private static TargetE2eActivationExpectedRuntime expectedRuntime() {
    return runtime(new ExplicitCaseIds(Set.of(CASE_ID)));
  }

  private static TargetE2eActivationExpectedRuntime syntheticRuntime() {
    return runtime(
        new IsolatedSyntheticNewCases("CASE_NEW_", 1, "fixtures-v1", FIXTURE_HASH, false, false));
  }

  private static TargetE2eActivationExpectedRuntime runtime(
      TargetE2eActivationExpectedRuntime.CaseScope caseScope) {
    return new TargetE2eActivationExpectedRuntime(
        "target-e2e",
        "target-e2e-env-01",
        17,
        CANDIDATE_SHA,
        "tenant-e2e",
        caseScope,
        Set.of(RoomType.INTAKE, RoomType.EVIDENCE),
        new BuildBindings("case-v9", "control-v9", "agent-v9"),
        new GraphBinding(
            "all-rooms/target-e2e.v1",
            "graph-v9",
            "checkpoint-v9",
            graphBindingHash(),
            "graph-code-v9"),
        new ImageDigests(digest('4'), digest('5'), digest('6'), digest('7'), digest('8')),
        "target-e2e-namespace",
        databaseIdentities());
  }

  private static TargetE2eActivationExpectedRuntime withProfile(
      TargetE2eActivationExpectedRuntime source, String profile) {
    return new TargetE2eActivationExpectedRuntime(
        profile,
        source.environmentId(),
        source.environmentGeneration(),
        source.candidateSha(),
        source.tenantSurrogate(),
        source.caseScope(),
        source.allowedRoomTypes(),
        source.buildBindings(),
        source.graphBinding(),
        source.imageDigests(),
        source.temporalNamespace(),
        source.databaseIdentities());
  }

  private static DatabaseIdentities databaseIdentities() {
    return new DatabaseIdentities(
        new DatabaseIdentity("domain-cluster", "domain-db", "java-domain-principal"),
        new DatabaseIdentity("graph-cluster", "graph-db", "python-graph-principal"));
  }

  private static ObjectNode payload() {
    ObjectNode payload = basePayload();
    ObjectNode scope = payload.putObject("caseScope");
    scope.put("mode", "EXPLICIT_CASE_IDS");
    scope.putArray("allowedCaseIds").add(CASE_ID);
    refreshHashes(payload);
    return payload;
  }

  private static ObjectNode syntheticPayload() {
    ObjectNode payload = basePayload();
    ObjectNode scope = payload.putObject("caseScope");
    scope.put("mode", "ISOLATED_SYNTHETIC_NEW_CASES");
    scope.put("caseIdPrefix", "CASE_NEW_");
    scope.put("maxCases", 1);
    scope.put("fixtureSetId", "fixtures-v1");
    scope.put("fixtureSetHash", FIXTURE_HASH);
    scope.put("containsRealCaseOrPartyData", false);
    scope.put("externalEffectsAllowed", false);
    refreshHashes(payload);
    return payload;
  }

  private static ObjectNode basePayload() {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("contractVersion", "target-e2e-activation.v1");
    payload.put("activationId", ACTIVATION_ID);
    payload.put("executionLane", "TARGET_E2E_CANDIDATE");
    payload.put("environmentId", "target-e2e-env-01");
    payload.put("environmentGeneration", 17);
    payload.put("candidateSha", CANDIDATE_SHA);
    payload.put("issuedAt", NOW.minusSeconds(10).toString());
    payload.put("expiresAt", NOW.plusSeconds(3_600).toString());
    payload.put("nonce", NONCE);
    payload.put("tenantSurrogate", "tenant-e2e");
    ArrayNode rooms = payload.putArray("allowedRoomTypes");
    rooms.add("INTAKE");
    rooms.add("EVIDENCE");
    ObjectNode builds = payload.putObject("buildBindings");
    builds.put("caseBuildId", "case-v9");
    builds.put("controlBuildId", "control-v9");
    builds.put("agentBuildId", "agent-v9");
    ObjectNode graph = payload.putObject("graphBinding");
    graph.put("key", "all-rooms/target-e2e.v1");
    graph.put("version", "graph-v9");
    graph.put("checkpointSchemaVersion", "checkpoint-v9");
    graph.put("codeBuildId", "graph-code-v9");
    ObjectNode images = payload.putObject("imageDigests");
    images.put("javaApi", digest('4'));
    images.put("temporalControlWorker", digest('5'));
    images.put("temporalAgentWorker", digest('6'));
    images.put("pythonAgent", digest('7'));
    images.put("frontend", digest('8'));
    payload.put("temporalNamespace", "target-e2e-namespace");
    ObjectNode databases = payload.putObject("databaseIdentities");
    database(databases.putObject("domain"), "domain-cluster", "domain-db", "java-domain-principal");
    database(databases.putObject("graph"), "graph-cluster", "graph-db", "python-graph-principal");
    ObjectNode authority = payload.putObject("authority");
    authority.put("environmentClass", "ISOLATED_PREPRODUCTION");
    authority.put("graphOutputAuthority", "PROPOSAL_ONLY");
    authority.put("graphDomainCredentialsPresent", false);
    authority.put("graphDomainWriteAllowed", false);
    authority.put("formalWriter", "JAVA_FINALIZER_ONLY");
    authority.put("javaDomainCommitAllowed", true);
    authority.put("externalEffectsAllowed", false);
    authority.put("productionTrafficAllowed", false);
    authority.put("productionPromotionAuthority", false);
    authority.put("migrationPromotionAuthority", false);
    ObjectNode defaults = payload.putObject("productionDefaults");
    defaults.put("formalCaseSelector", "LEGACY");
    defaults.put("targetE2EActivation", "DISABLED");
    return payload;
  }

  private static void database(
      ObjectNode target, String cluster, String database, String principal) {
    target.put("clusterIdentity", cluster);
    target.put("databaseIdentity", database);
    target.put("runtimePrincipalIdentity", principal);
  }

  private static void refreshHashes(ObjectNode payload) {
    ObjectNode graph = (ObjectNode) payload.get("graphBinding");
    graph.remove("bindingHash");
    graph.put("bindingHash", ContractJson.sha256Hex(graph));
    refreshManifestHash(payload);
  }

  private static void refreshManifestHash(ObjectNode payload) {
    payload.remove("manifestHash");
    payload.put("manifestHash", ContractJson.sha256Hex(payload));
  }

  private static String graphBindingHash() {
    ObjectNode graph = MAPPER.createObjectNode();
    graph.put("key", "all-rooms/target-e2e.v1");
    graph.put("version", "graph-v9");
    graph.put("checkpointSchemaVersion", "checkpoint-v9");
    graph.put("codeBuildId", "graph-code-v9");
    return ContractJson.sha256Hex(graph);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static ActivationRequest request(ActivationScope scope) {
    return new ActivationRequest(scope, "tenant-e2e", RoomType.INTAKE, CASE_ID);
  }

  private static String sign(ObjectNode payload, KeyPair key, String keyId) throws Exception {
    return sign(payload, key, keyId, "target-e2e-activation+jwt");
  }

  private static String sign(ObjectNode payload, KeyPair key, String keyId, String type)
      throws Exception {
    ObjectNode header = MAPPER.createObjectNode();
    header.put("alg", "ES256");
    header.put("kid", keyId);
    header.put("typ", type);
    return signRaw(ContractJson.canonicalize(header), ContractJson.canonicalize(payload), key);
  }

  private static String signRaw(byte[] payload, KeyPair key, String keyId) throws Exception {
    ObjectNode header = MAPPER.createObjectNode();
    header.put("alg", "ES256");
    header.put("kid", keyId);
    header.put("typ", "target-e2e-activation+jwt");
    return signRaw(ContractJson.canonicalize(header), payload, key);
  }

  private static String signRaw(byte[] header, byte[] payload, KeyPair key) throws Exception {
    String encodedHeader = BASE64_URL.encodeToString(header);
    String encodedPayload = BASE64_URL.encodeToString(payload);
    String signingInput = encodedHeader + "." + encodedPayload;
    Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
    signer.initSign(key.getPrivate());
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + BASE64_URL.encodeToString(signer.sign());
  }

  private static KeyPair keyPair(String curve) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec(curve));
    return generator.generateKeyPair();
  }

  private static Clock fixedClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static final class RecordingReplayStore implements TargetE2eActivationReplayStore {

    private final Map<String, Registration> byActivationId = new HashMap<>();
    private final Map<String, Registration> byNonce = new HashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public synchronized RegistrationResult registerOrAttach(Registration registration) {
      calls.incrementAndGet();
      Registration activation = byActivationId.get(registration.activationId());
      Registration nonce = byNonce.get(registration.nonce());
      if (activation == null && nonce == null) {
        byActivationId.put(registration.activationId(), registration);
        byNonce.put(registration.nonce(), registration);
        return RegistrationResult.REGISTERED;
      }
      return registration.equals(activation) && registration.equals(nonce)
          ? RegistrationResult.ATTACHED_EXISTING
          : RegistrationResult.CONFLICT;
    }

    int calls() {
      return calls.get();
    }
  }

  private static final class RecordingCaseLedger implements TargetE2eActivationCaseLedger {

    private final Map<String, Reservation> reservations = new HashMap<>();

    @Override
    public synchronized ReservationResult apply(Action action, Reservation reservation) {
      Reservation existing = reservations.get(reservation.caseId());
      if (existing != null) {
        return existing.equals(reservation)
            ? ReservationResult.ALREADY_RESERVED_IDENTICALLY
            : ReservationResult.CONFLICT;
      }
      if (action == Action.REQUIRE_EXISTING) {
        return ReservationResult.NOT_RESERVED;
      }
      if (reservations.size() >= reservation.maxCases()) {
        return ReservationResult.CAPACITY_EXHAUSTED;
      }
      reservations.put(reservation.caseId(), reservation);
      return ReservationResult.RESERVED;
    }
  }

  private static final class MutableClock extends Clock {

    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("test clock is UTC only");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}

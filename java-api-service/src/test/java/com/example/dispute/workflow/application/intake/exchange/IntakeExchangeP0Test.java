package com.example.dispute.workflow.application.intake.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.api.intake.IntakeExchangeController;
import com.example.dispute.workflow.api.intake.IntakeExchangeRequestCodec;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadClaim;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.ProposalPutClaim;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.ProposalPutGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.Authority;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ObjectReference;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.PayloadLoadRequest;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ProposalDocument;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ProposalPutRequest;
import com.example.dispute.workflow.config.IntakeSyntheticExchangeConfiguration;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakePrivateObjectStoreExchangeAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IntakeExchangeP0Test {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String OBJECT_VERSION = "VERSION_P4_EXACT_1";

    private static final String SYNTHETIC_ENABLED =
            "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled=true";
    private static final String SHADOW_SELECTION =
            "app.orchestration.intake-epoch-selection.mode=SHADOW";
    private static final String SHADOW_COHORT =
            "app.orchestration.intake-epoch-selection.shadow-cohort-basis-points=1";
    private static final String SHADOW_POLICY =
            "app.orchestration.intake-epoch-selection.cohort-policy-version=synthetic.v1";
    private static final String GRAPH_SHADOW = "app.agent-run-v2.graph-client.mode=SHADOW";
    private static final String GRAPH_ENDPOINT =
            "app.agent-run-v2.graph-client.base-uri=https://python-agent-service:18000";

    @Test
    void exchangeAssemblyIsAbsentUnlessSyntheticShadowAndEveryRealPortExist() {
        exchangeRunnerWithPorts().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IntakeExchangeService.class);
        });

        exchangeRunnerWithPorts()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        GRAPH_SHADOW,
                        GRAPH_ENDPOINT)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IntakeExchangeService.class);
                });

        exchangeRunnerWithoutPayloadStore()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        GRAPH_SHADOW,
                        GRAPH_ENDPOINT)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IntakeExchangeService.class);
                });
    }

    @Test
    void exchangeAssemblyRejectsFormalOrIncompleteRuntimeModes() {
        exchangeRunnerWithPorts()
                .withPropertyValues(SYNTHETIC_ENABLED, GRAPH_SHADOW, GRAPH_ENDPOINT)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("complete signed synthetic SHADOW selection");
                });

        exchangeRunnerWithPorts()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        GRAPH_SHADOW,
                        GRAPH_ENDPOINT,
                        "app.agent-run-v2.enabled=true",
                        "app.agent-run-v2.scheduler-mode=DETECTOR")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("formal AgentRunV2 path is enabled");
                });
    }

    @Test
    void loadEchoesAuthorityAfterExactGrantAndCanonicalPayloadValidation() throws Exception {
        byte[] payload = canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-domain-snapshot-valid.json");
        PayloadLoadRequest request = loadRequest(payload);
        CapturingAuthority authority = new CapturingAuthority();
        CapturingStore store = new CapturingStore();
        store.loaded = new IntakeExchangeObjectStore.LoadedPayload(
                request.objectRef().artifactId(),
                request.objectRef().schemaVersion(),
                request.objectRef().uri(),
                OBJECT_VERSION,
                request.objectRef().sha256(),
                request.objectRef().sizeBytes(),
                payload);

        var response = service(authority, store).load(request);

        assertThat(response.authority()).isEqualTo(request.authority());
        assertThat(response.receipt().objectVersion()).isEqualTo(OBJECT_VERSION);
        assertThat(response.canonicalPayloadBase64())
                .isEqualTo(Base64.getEncoder().encodeToString(payload));
        assertThat(authority.payloadLoadClaims).isEqualTo(1);
        assertThat(store.loads).isEqualTo(1);
    }

    @Test
    void rejectedLoadAuthorityNeverTouchesStore() throws Exception {
        CapturingAuthority authority = new CapturingAuthority();
        authority.rejectPayload = true;
        CapturingStore store = new CapturingStore();

        assertThatThrownBy(() -> service(authority, store).load(loadRequest(canonicalFixture(
                        "../contracts/agent-platform/intake/v2/fixtures/valid/"
                                + "intake-domain-snapshot-valid.json"))))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class);

        assertThat(authority.payloadLoadClaims).isEqualTo(1);
        assertThat(store.loads).isZero();
    }

    @Test
    void loadRejectsObjectVersionDriftFromExactGrant() throws Exception {
        byte[] payload = canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-domain-snapshot-valid.json");
        PayloadLoadRequest request = loadRequest(payload);
        CapturingStore store = new CapturingStore();
        store.loaded = new IntakeExchangeObjectStore.LoadedPayload(
                request.objectRef().artifactId(),
                request.objectRef().schemaVersion(),
                request.objectRef().uri(),
                "VERSION_DRIFT",
                request.objectRef().sha256(),
                request.objectRef().sizeBytes(),
                payload);

        assertThatThrownBy(() -> service(new CapturingAuthority(), store).load(request))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class)
                .hasMessageContaining("receipt differs");
    }

    @Test
    void putValidatesCanonicalProposalBeforeAuthorityAndEchoesTerminalCheckpoint()
            throws Exception {
        byte[] payload = canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-turn-proposal-valid.json");
        ProposalPutRequest request = putRequest(payload);
        CapturingAuthority authority = new CapturingAuthority();
        CapturingStore store = new CapturingStore();
        store.stored = new IntakeExchangeObjectStore.StoredProposal(
                request.proposal().artifactId(),
                request.proposal().schemaVersion(),
                "s3://intake-private/proposals/COMMAND_P4_USER_2.json",
                OBJECT_VERSION,
                request.proposal().sha256(),
                request.proposal().sizeBytes());

        var response = service(authority, store).put(request);

        assertThat(response.authority()).isEqualTo(request.authority());
        assertThat(response.checkpointNs()).isEqualTo("thread");
        assertThat(response.checkpointId()).isEqualTo("checkpoint-2");
        assertThat(response.cognitiveRevision()).isEqualTo(2);
        assertThat(authority.proposalPutClaims).isEqualTo(1);
        assertThat(store.puts).isEqualTo(1);
    }

    @Test
    void putRejectsBadHashBeforeAuthorityOrStore() throws Exception {
        byte[] payload = canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-turn-proposal-valid.json");
        ProposalPutRequest request = putRequest(
                payload,
                "0000000000000000000000000000000000000000000000000000000000000000");
        CapturingAuthority authority = new CapturingAuthority();
        CapturingStore store = new CapturingStore();

        assertThatThrownBy(() -> service(authority, store).put(request))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class)
                .hasMessageContaining("self-hash");

        assertThat(authority.proposalPutClaims).isZero();
        assertThat(store.puts).isZero();
    }

    @Test
    void codecUsesSnakeCaseAndRejectsUnknownOrDuplicateMembers() throws Exception {
        PayloadLoadRequest request = loadRequest(canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-domain-snapshot-valid.json"));
        byte[] json = MAPPER.writeValueAsBytes(request);
        String text = new String(json, StandardCharsets.UTF_8);
        IntakeExchangeRequestCodec codec = new IntakeExchangeRequestCodec(MAPPER);

        assertThat(text).contains("schema_version", "tenant_surrogate", "object_ref");
        assertThat(codec.decodeLoad(json)).isEqualTo(request);
        assertThatThrownBy(() -> codec.decodeLoad(
                        ("{\"schema_version\":\"intake-payload-load-request.v1\","
                                        + "\"schema_version\":\"intake-payload-load-request.v1\"}")
                                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decodeLoad(
                        (text.substring(0, text.length() - 1) + ",\"extra\":1}")
                                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controllerRejectsWrongSecretBeforeDecodeOrService() {
        AppProperties properties = mock(AppProperties.class);
        AppProperties.Security security = mock(AppProperties.Security.class);
        when(properties.security()).thenReturn(security);
        when(security.serviceSecret()).thenReturn("java-service-secret");
        CapturingAuthority authority = new CapturingAuthority();
        CapturingStore store = new CapturingStore();
        IntakeExchangeController controller = new IntakeExchangeController(
                service(authority, store), properties, new IntakeExchangeRequestCodec(MAPPER));

        assertThatThrownBy(() -> controller.load(
                        "wrong-secret",
                        "not-json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ForbiddenException.class);
        assertThat(authority.payloadLoadClaims).isZero();
        assertThat(store.loads).isZero();
    }

    @Test
    void privateAdapterUsesExactVersionedReadAndPassesPutIdempotency() throws Exception {
        byte[] payload = canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-turn-proposal-valid.json");
        ProposalPutRequest put = putRequest(payload);
        PayloadLoadRequest load = loadRequest(canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-domain-snapshot-valid.json"));
        CapturingPayloadGateway gateway = new CapturingPayloadGateway(load, OBJECT_VERSION);
        CapturingPublisher publisher = new CapturingPublisher();
        publisher.stored = new IntakeImmutablePayloadPublisher.StoredPayload(
                put.proposal().artifactId(),
                put.proposal().schemaVersion(),
                "minio://intake-private/proposals/COMMAND_P4_USER_2.json",
                OBJECT_VERSION,
                put.proposal().sha256(),
                put.proposal().sizeBytes());
        IntakePrivateObjectStoreExchangeAdapter adapter =
                new IntakePrivateObjectStoreExchangeAdapter(gateway, publisher);

        adapter.load(new PayloadLoadGrant(load, OBJECT_VERSION));
        adapter.put(new ProposalPutGrant(put), payload);

        assertThat(gateway.lastRequest.objectVersion()).isEqualTo(OBJECT_VERSION);
        assertThat(gateway.lastRequest.uri()).isEqualTo(load.objectRef().uri());
        assertThat(publisher.lastRequest.putIdempotencyKey()).isEqualTo(put.idempotencyKey());
    }

    @Test
    void exchangeSourcesDoNotReferenceFinalizerOrGlobalWiring() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/example/dispute/workflow");
        String combined = Files.walk(sourceRoot)
                .filter(path -> path.toString().contains("intake")
                        && path.toString().contains("exchange")
                        && path.toString().endsWith(".java"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .reduce("", String::concat);

        assertThat(combined).doesNotContain("Finalizer", "@Configuration", "@Bean");
    }

    private static IntakeExchangeService service(
            IntakeExchangeAuthorityValidationPort authority, IntakeExchangeObjectStore store) {
        return new IntakeExchangeService(
                authority, store, new IntakeExchangeCanonicalPayloadValidator());
    }

    private static ApplicationContextRunner exchangeRunnerWithPorts() {
        return exchangeRunnerWithoutPayloadStore()
                .withBean(
                        IntakeExchangePayloadObjectStoreGateway.class,
                        () -> mock(IntakeExchangePayloadObjectStoreGateway.class));
    }

    private static ApplicationContextRunner exchangeRunnerWithoutPayloadStore() {
        return new ApplicationContextRunner()
                .withUserConfiguration(IntakeSyntheticExchangeConfiguration.class)
                .withBean(
                        IntakeExchangeAuthorityValidationPort.class,
                        () -> mock(IntakeExchangeAuthorityValidationPort.class))
                .withBean(
                        IntakeImmutablePayloadPublisher.class,
                        () -> mock(IntakeImmutablePayloadPublisher.class));
    }

    private static PayloadLoadRequest loadRequest(byte[] payload) throws Exception {
        JsonNode document = MAPPER.readTree(payload);
        return new PayloadLoadRequest(
                "intake-payload-load-request.v1",
                authority(),
                new ObjectReference(
                        "SNAPSHOT_P4_USER_1",
                        "intake-domain-snapshot.v2",
                        "s3://intake-private/snapshots/SNAPSHOT_P4_USER_1.json",
                        document.get("snapshot_hash").textValue(),
                        payload.length));
    }

    private static ProposalPutRequest putRequest(byte[] payload) throws Exception {
        JsonNode document = MAPPER.readTree(payload);
        return putRequest(payload, document.get("proposal_hash").textValue());
    }

    private static ProposalPutRequest putRequest(byte[] payload, String sha256) {
        return new ProposalPutRequest(
                "intake-proposal-put-request.v1",
                authority(),
                "intake.proposal:grt.v1.018f6b7ec30a7430982fffc520c8195c:"
                        + "COMMAND_P4_USER_2:"
                        + sha256,
                "thread",
                "checkpoint-2",
                2,
                new ProposalDocument(
                        "PROPOSAL_P4_USER_2",
                        "intake-turn-proposal.v2",
                        sha256,
                        payload.length,
                        Base64.getEncoder().encodeToString(payload)));
    }

    private static Authority authority() {
        return new Authority(
                "intake-exchange-authority.v1",
                "TENANT_SYNTHETIC",
                "CASE_P4_SYNTHETIC_1",
                "INTAKE",
                1,
                "grt.v1.018f6b7ec30a7430982fffc520c8195c",
                "USER_P4",
                ActorRole.USER,
                Audience.USER,
                List.of("graph.thread.execute"),
                "52f01901287fe5e5465ddcd7d7baf9074aa77e3d88a64da747bf1f530916a5d2",
                "AGENT_SESSION_P4_USER_1",
                "COMMAND_P4_USER_2",
                "RUN_P4_USER_2",
                "ATTEMPT_P4_USER_2_1",
                "1111111111111111111111111111111111111111111111111111111111111111",
                "intake.v2",
                "2.0.0",
                "intake-checkpoint.v2",
                7,
                "INTAKE_ACTIVE",
                3);
    }

    private static byte[] canonicalFixture(String relativePath) throws Exception {
        JsonNode document = MAPPER.readTree(Path.of(relativePath).toFile());
        return ContractJson.canonicalize(document);
    }

    private static final class CapturingAuthority implements IntakeExchangeAuthorityValidationPort {
        int payloadLoadClaims;
        int proposalPutClaims;
        boolean rejectPayload;

        @Override
        public PayloadLoadGrant requirePayloadLoad(PayloadLoadClaim claim) {
            payloadLoadClaims++;
            if (rejectPayload) {
                throw new Rejected("payload rejected");
            }
            return new PayloadLoadGrant(claim.request(), OBJECT_VERSION);
        }

        @Override
        public ProposalPutGrant requireProposalPut(ProposalPutClaim claim) {
            proposalPutClaims++;
            return new ProposalPutGrant(claim.request());
        }
    }

    private static final class CapturingStore implements IntakeExchangeObjectStore {
        int loads;
        int puts;
        LoadedPayload loaded;
        StoredProposal stored;

        @Override
        public LoadedPayload load(PayloadLoadGrant grant) {
            loads++;
            return Objects.requireNonNull(loaded, "loaded");
        }

        @Override
        public StoredProposal put(ProposalPutGrant grant, byte[] canonicalProposal) {
            puts++;
            return Objects.requireNonNull(stored, "stored");
        }
    }

    private static final class CapturingPayloadGateway
            implements IntakeExchangePayloadObjectStoreGateway {
        private final PayloadLoadRequest load;
        private final String objectVersion;
        ReadRequest lastRequest;

        CapturingPayloadGateway(PayloadLoadRequest load, String objectVersion) {
            this.load = load;
            this.objectVersion = objectVersion;
        }

        @Override
        public StoredPayload readExact(ReadRequest request) {
            lastRequest = request;
            return new StoredPayload(
                    load.objectRef().artifactId(),
                    load.objectRef().schemaVersion(),
                    load.objectRef().uri(),
                    objectVersion,
                    load.objectRef().sha256(),
                    load.objectRef().sizeBytes(),
                    new byte[(int) load.objectRef().sizeBytes()]);
        }
    }

    private static final class CapturingPublisher implements IntakeImmutablePayloadPublisher {
        PublishRequest lastRequest;
        StoredPayload stored;

        @Override
        public StoredPayload publish(PublishRequest request) {
            lastRequest = request;
            return Objects.requireNonNull(stored, "stored");
        }
    }
}

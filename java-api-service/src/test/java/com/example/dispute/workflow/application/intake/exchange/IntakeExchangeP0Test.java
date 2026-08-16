package com.example.dispute.workflow.application.intake.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.api.SignedSyntheticIntakeIngressController;
import com.example.dispute.workflow.api.intake.IntakeExchangeController;
import com.example.dispute.workflow.api.intake.IntakeExchangeRequestCodec;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
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
import com.example.dispute.workflow.config.IntakeSyntheticShadowConfiguration;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.infrastructure.objectstore.intake.IntakePrivateObjectStoreExchangeAdapter;
import com.example.dispute.workflow.infrastructure.objectstore.intake.MinioIntakeSyntheticExchangeStore;
import com.example.dispute.workflow.infrastructure.persistence.authority.intake.JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticWorkerRegistration;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeIngressService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class IntakeExchangeP0Test {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String OBJECT_VERSION = "VERSION_P4_EXACT_1";

    private static final String SYNTHETIC_ENABLED =
            "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled=true";
    private static final String EXCHANGE_ENABLED =
            "app.orchestration.intake-synthetic-exchange.enabled=true";
    private static final String API_ROLE = "app.temporal.worker.enabled=false";
    private static final String SHADOW_SELECTION =
            "app.orchestration.intake-epoch-selection.mode=SHADOW";
    private static final String SHADOW_COHORT =
            "app.orchestration.intake-epoch-selection.shadow-cohort-basis-points=1";
    private static final String SHADOW_POLICY =
            "app.orchestration.intake-epoch-selection.cohort-policy-version=synthetic.v1";
    private static final String GRAPH_DISABLED = "app.agent-run-v2.graph-client.mode=DISABLED";

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
                        API_ROLE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IntakeExchangeService.class);
                });

        exchangeRunnerWithPorts()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        EXCHANGE_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        API_ROLE,
                        GRAPH_DISABLED)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IntakeExchangeService.class);
                    assertThat(context).doesNotHaveBean(AgentGraphCommandClient.class);
                });

        exchangeRunnerWithoutPayloadStore()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        EXCHANGE_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        API_ROLE,
                        GRAPH_DISABLED)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining(
                                    IntakeExchangePayloadObjectStoreGateway.class.getName());
                });
    }

    @Test
    void syntheticShadowCreatesConcreteJdbcAndMinioExchangeAdapters() {
        new ApplicationContextRunner()
                .withUserConfiguration(IntakeSyntheticExchangeConfiguration.class)
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        EXCHANGE_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        API_ROLE,
                        GRAPH_DISABLED)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(MinioClient.class, () -> mock(MinioClient.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(
                                    JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort.class);
                    assertThat(context)
                            .hasSingleBean(MinioIntakeSyntheticExchangeStore.class)
                            .hasSingleBean(IntakeExchangeAuthorityValidationPort.class)
                            .hasSingleBean(IntakeExchangePayloadObjectStoreGateway.class)
                            .hasSingleBean(IntakeImmutablePayloadPublisher.class)
                            .hasSingleBean(IntakeExchangeService.class);
                });
    }

    @Test
    void exchangeAssemblyRejectsFormalOrIncompleteRuntimeModes() {
        exchangeRunnerWithPorts()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        EXCHANGE_ENABLED,
                        API_ROLE,
                        GRAPH_DISABLED)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("complete signed synthetic SHADOW selection");
                });

        exchangeRunnerWithPorts()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        EXCHANGE_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        API_ROLE,
                        GRAPH_DISABLED,
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
    void apiAssemblyExposesIngressAndExchangeWhileGraphClientStaysDisabled() {
        signedSyntheticApiRunner()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        EXCHANGE_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        API_ROLE,
                        GRAPH_DISABLED)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SignedSyntheticIntakeDriver.class);
                    assertThat(context).hasSingleBean(SignedSyntheticIntakeIngressService.class);
                    assertThat(context).hasSingleBean(SignedSyntheticIntakeIngressController.class);
                    assertThat(context).hasSingleBean(IntakeExchangeService.class);
                    assertThat(context).hasSingleBean(IntakeExchangeController.class);
                    assertThat(context).doesNotHaveBean(AgentGraphCommandClient.class);
                    assertThat(context).doesNotHaveBean(IntakeSyntheticWorkerRegistration.class);
                });
    }

    @Test
    void signedSyntheticApiComponentsAreAbsentByDefaultAndOnWorkerRoles() {
        signedSyntheticApiRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SignedSyntheticIntakeIngressService.class);
            assertThat(context).doesNotHaveBean(SignedSyntheticIntakeIngressController.class);
            assertThat(context).doesNotHaveBean(IntakeExchangeController.class);
        });

        signedSyntheticApiRunner()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        EXCHANGE_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        "app.temporal.worker.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SignedSyntheticIntakeIngressService.class);
                    assertThat(context)
                            .doesNotHaveBean(SignedSyntheticIntakeIngressController.class);
                    assertThat(context).doesNotHaveBean(IntakeExchangeController.class);
                    assertThat(context).doesNotHaveBean(IntakeExchangeService.class);
                });
    }

    @Test
    void exchangeAssemblyIsUnreachableFromWorkerRoles() {
        exchangeRunnerWithPorts()
                .withPropertyValues(
                        SYNTHETIC_ENABLED,
                        EXCHANGE_ENABLED,
                        SHADOW_SELECTION,
                        SHADOW_COHORT,
                        SHADOW_POLICY,
                        "app.temporal.worker.enabled=true",
                        "app.temporal.worker.role=AGENT")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IntakeExchangeService.class);
                    assertThat(context)
                            .doesNotHaveBean(IntakeExchangeCanonicalPayloadValidator.class);
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
    void canonicalSnapshotRequiresAnExplicitKnownFormSource() throws Exception {
        ObjectNode valid = (ObjectNode) MAPPER.readTree(Path.of(
                        "../contracts/agent-platform/intake/v2/fixtures/valid/"
                                + "intake-domain-snapshot-valid.json")
                .toFile());
        var validator = new IntakeExchangeCanonicalPayloadValidator();
        byte[] validPayload = ContractJson.canonicalize(valid);

        assertThat(validator.requireValid(
                        "intake-domain-snapshot.v2",
                        valid.required("snapshot_hash").textValue(),
                        validPayload.length,
                        validPayload)
                .at("/initial_case_facts/form_source")
                .textValue())
                .isEqualTo("FORM_SUBMISSION");

        ObjectNode missing = valid.deepCopy();
        ((ObjectNode) missing.required("initial_case_facts")).remove("form_source");
        missing.put(
                "snapshot_hash",
                IntakeContractHashes.canonicalHashExcluding(missing, "snapshot_hash"));
        byte[] missingPayload = ContractJson.canonicalize(missing);

        assertThatThrownBy(() -> validator.requireValid(
                        "intake-domain-snapshot.v2",
                        missing.required("snapshot_hash").textValue(),
                        missingPayload.length,
                        missingPayload))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class)
                .hasMessageContaining("violates intake-domain-snapshot.v2");

        ObjectNode unsupported = valid.deepCopy();
        ((ObjectNode) unsupported.required("initial_case_facts"))
                .put("form_source", "LEGACY_IMPORT");
        unsupported.put(
                "snapshot_hash",
                IntakeContractHashes.canonicalHashExcluding(unsupported, "snapshot_hash"));
        byte[] unsupportedPayload = ContractJson.canonicalize(unsupported);

        assertThatThrownBy(() -> validator.requireValid(
                        "intake-domain-snapshot.v2",
                        unsupported.required("snapshot_hash").textValue(),
                        unsupportedPayload.length,
                        unsupportedPayload))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class)
                .hasMessageContaining("violates intake-domain-snapshot.v2");
    }

    @Test
    void canonicalProposalAcceptsExactIndependentPartyStateAndRejectsStructuralDrift()
            throws Exception {
        ObjectNode proposal = (ObjectNode) MAPPER.readTree(Path.of(
                        "../contracts/agent-platform/intake/v2/fixtures/valid/"
                                + "intake-turn-proposal-valid.json")
                .toFile());
        proposal.put("conversation_action", "ASK_SUBSTANTIVE");
        ObjectNode dossier = (ObjectNode) proposal.required("dossier_patch");
        dossier.set("party_intake_state", partyIntakeState());
        dossier.set("handoff_remark_partition", handoffRemarkPartition());
        byte[] acceptedPayload = canonicalProposal(proposal);
        var validator = new IntakeExchangeCanonicalPayloadValidator();

        assertThat(validator.requireValid(
                                "intake-turn-proposal.v2",
                                proposal.required("proposal_hash").textValue(),
                                acceptedPayload.length,
                                acceptedPayload)
                        .at("/dossier_patch/party_intake_state/schema_version")
                        .textValue())
                .isEqualTo("party-intake-state.v1");

        ObjectNode oneSided = proposal.deepCopy();
        ((ObjectNode) oneSided.at("/dossier_patch/party_intake_state"))
                .remove("MERCHANT");
        byte[] oneSidedPayload = canonicalProposal(oneSided);
        assertThatThrownBy(() -> validator.requireValid(
                        "intake-turn-proposal.v2",
                        oneSided.required("proposal_hash").textValue(),
                        oneSidedPayload.length,
                        oneSidedPayload))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class)
                .hasMessageContaining("violates intake-turn-proposal.v2");

        ObjectNode unknownPartitionBranch = proposal.deepCopy();
        ((ObjectNode) unknownPartitionBranch.at("/dossier_patch/handoff_remark_partition"))
                .putObject("internal_notes");
        byte[] unknownPartitionPayload = canonicalProposal(unknownPartitionBranch);
        assertThatThrownBy(() -> validator.requireValid(
                        "intake-turn-proposal.v2",
                        unknownPartitionBranch.required("proposal_hash").textValue(),
                        unknownPartitionPayload.length,
                        unknownPartitionPayload))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class)
                .hasMessageContaining("violates intake-turn-proposal.v2");

        ObjectNode unknownBranch = proposal.deepCopy();
        ((ObjectNode) unknownBranch.at("/dossier_patch/party_intake_state/MERCHANT"))
                .putObject("shared_readiness");
        byte[] unknownBranchPayload = canonicalProposal(unknownBranch);
        assertThatThrownBy(() -> validator.requireValid(
                        "intake-turn-proposal.v2",
                        unknownBranch.required("proposal_hash").textValue(),
                        unknownBranchPayload.length,
                        unknownBranchPayload))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class)
                .hasMessageContaining("violates intake-turn-proposal.v2");
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
    void jdbcAuthorityRequiresOneExactCurrentAdmissionAndReturnsItsObjectVersion()
            throws Exception {
        byte[] payload = canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-domain-snapshot-valid.json");
        PayloadLoadRequest request = loadRequest(payload);
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(List.of(Map.of(
                        "artifact_id", request.objectRef().artifactId(),
                        "schema_version", request.objectRef().schemaVersion(),
                        "object_uri", request.objectRef().uri(),
                        "object_version", OBJECT_VERSION,
                        "content_sha256", request.objectRef().sha256(),
                        "size_bytes", request.objectRef().sizeBytes())));
        var authority = new JdbcSignedSyntheticIntakeExchangeAuthorityValidationPort(
                jdbc,
                MAPPER,
                Clock.fixed(Instant.parse("2026-07-22T08:00:00Z"), ZoneOffset.UTC));

        assertThat(authority.requirePayloadLoad(new PayloadLoadClaim(request)).objectVersion())
                .isEqualTo(OBJECT_VERSION);

        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> authority.requirePayloadLoad(new PayloadLoadClaim(request)))
                .isInstanceOf(IntakeExchangeAuthorityValidationPort.Rejected.class)
                .hasMessageContaining("not current and exact");
    }

    @Test
    void minioStorePublishesAndReadsOnlyItsExactContentAddress() throws Exception {
        byte[] payload = canonicalFixture(
                "../contracts/agent-platform/intake/v2/fixtures/valid/"
                        + "intake-turn-event-valid.json");
        JsonNode document = MAPPER.readTree(payload);
        String hash = document.get("event_hash").textValue();
        MinioClient minio = mock(MinioClient.class);
        var store = new MinioIntakeSyntheticExchangeStore(
                minio,
                new IntakeExchangeCanonicalPayloadValidator(),
                "intake-synthetic-private",
                "signed-synthetic/intake");
        var published = store.publish(new IntakeImmutablePayloadPublisher.PublishRequest(
                "EVENT_P4_EXCHANGE_1",
                "intake-turn-event.v2",
                hash,
                payload,
                IntakeExchangeContract.EVENT_MAX_BYTES));
        String objectKey = java.net.URI.create(published.uri()).getPath().substring(1);
        when(minio.getObject(any())).thenReturn(new GetObjectResponse(
                new Headers.Builder().build(),
                "intake-synthetic-private",
                null,
                objectKey,
                new ByteArrayInputStream(payload)));

        var loaded = store.readExact(new IntakeExchangePayloadObjectStoreGateway.ReadRequest(
                published.artifactId(),
                published.schemaVersion(),
                published.uri(),
                published.objectVersion(),
                published.contentSha256(),
                published.sizeBytes()));

        assertThat(loaded.canonicalPayload()).isEqualTo(payload);
        assertThat(published.objectVersion()).isEqualTo(hash);
        assertThatThrownBy(() -> store.readExact(
                        new IntakeExchangePayloadObjectStoreGateway.ReadRequest(
                                published.artifactId(),
                                published.schemaVersion(),
                                published.uri(),
                                "0".repeat(64),
                                published.contentSha256(),
                                published.sizeBytes())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("content address");
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

    private static ApplicationContextRunner signedSyntheticApiRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        SignedSyntheticApiComponentScan.class,
                        IntakeSyntheticShadowConfiguration.class,
                        IntakeSyntheticExchangeConfiguration.class)
                .withBean(
                        IntakeSignedSyntheticAdmissionPort.class,
                        () -> mock(IntakeSignedSyntheticAdmissionPort.class))
                .withBean(
                        IntakeExchangeAuthorityValidationPort.class,
                        () -> mock(IntakeExchangeAuthorityValidationPort.class))
                .withBean(
                        IntakeExchangePayloadObjectStoreGateway.class,
                        () -> mock(IntakeExchangePayloadObjectStoreGateway.class))
                .withBean(
                        IntakeImmutablePayloadPublisher.class,
                        () -> mock(IntakeImmutablePayloadPublisher.class))
                .withBean(CaseCommandService.class, () -> mock(CaseCommandService.class))
                .withBean(AppProperties.class, () -> mock(AppProperties.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(Clock.class, Clock::systemUTC);
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = {
                SignedSyntheticIntakeIngressService.class,
                SignedSyntheticIntakeIngressController.class,
                IntakeExchangeController.class
            },
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.ASSIGNABLE_TYPE,
                            classes = {
                                SignedSyntheticIntakeIngressService.class,
                                SignedSyntheticIntakeIngressController.class,
                                IntakeExchangeController.class
                            }))
    static class SignedSyntheticApiComponentScan {}

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

    private static byte[] canonicalProposal(ObjectNode proposal) {
        proposal.put(
                "proposal_hash",
                IntakeContractHashes.canonicalHashExcluding(proposal, "proposal_hash"));
        return ContractJson.canonicalize(proposal);
    }

    private static ObjectNode partyIntakeState() {
        ObjectNode state = MAPPER.createObjectNode();
        state.put("schema_version", "party-intake-state.v1");
        state.set("USER", partyIntakeEntry());
        state.set("MERCHANT", partyIntakeEntry());
        return state;
    }

    private static ObjectNode partyIntakeEntry() {
        ObjectNode entry = MAPPER.createObjectNode();
        ObjectNode quality = entry.putObject("intake_quality");
        quality.put("score", 0);
        quality.put("threshold", 85);
        quality.put("ready_for_next_step", false);
        ObjectNode breakdown = quality.putObject("score_breakdown");
        for (String component : List.of(
                "references",
                "event_story",
                "party_positions",
                "requested_resolution",
                "risk_and_conflicts",
                "next_action_clarity")) {
            breakdown.put(component, 0);
        }
        quality.put("improvement_reason", "Waiting for this party's Intake statement.");
        ObjectNode missing = entry.putObject("missing_information");
        missing.putArray("blocking_gaps");
        missing.putArray("nice_to_have_gaps");
        missing.putArray("next_questions");
        ObjectNode handoff = entry.putObject("handoff_notes");
        handoff.put("remark_status", "NOT_READY");
        handoff.put("phase_source_message_id", "");
        handoff.put("latest_remark", "");
        handoff.putArray("remarks");
        handoff.put("instruction", "Continue current-party Intake.");
        ObjectNode admission = entry.putObject("admission");
        admission.put("recommendation", "NEED_MORE_INFO");
        admission.put("reasoning", "");
        admission.put("confidence", 0.0d);
        return entry;
    }

    private static ObjectNode handoffRemarkPartition() {
        ObjectNode partition = MAPPER.createObjectNode();
        partition.put("schema_version", "handoff_remark_partition.v1");
        partition.put("case_fact_matrix_id", "MATRIX_P4");
        partition.put("case_fact_matrix_version", 1);
        partition.put("case_fact_matrix_hash", "a".repeat(64));
        ObjectNode parties = partition.putObject("parties");

        ObjectNode user = parties.putObject("USER");
        user.put("party_role", "USER");
        user.put("remark_status", "HAS_REMARKS");
        ObjectNode source = user.putObject("source");
        source.put("source_kind", "ROOM_MESSAGE");
        source.put("message_id", "MESSAGE_P4_USER_2");
        source.put("message_hash", "b".repeat(64));
        user.put("latest_remark", "The delivery date was added.");
        ObjectNode remark = user.putArray("remarks").addObject();
        remark.put("party_role", "USER");
        remark.put("text", "The delivery date was added.");
        remark.put("source_message_id", "MESSAGE_P4_USER_2");
        remark.put("source_message_hash", "b".repeat(64));
        remark.put("turn_source", "ROOM_MESSAGE");

        ObjectNode merchant = parties.putObject("MERCHANT");
        merchant.put("party_role", "MERCHANT");
        merchant.put("remark_status", "NOT_READY");
        merchant.put("latest_remark", "");
        merchant.putArray("remarks");
        return partition;
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
        return authority("intake.v2");
    }

    @Test
    void authorityDtoAllowsOnlyThePinnedShadowAndTargetGraphs() {
        assertThat(authority("all-rooms.target-e2e.v1").graphKey())
                .isEqualTo("all-rooms.target-e2e.v1");
        assertThatThrownBy(() -> authority("other-graph.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed Intake exchange graph");
    }

    private static Authority authority(String graphKey) {
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
                graphKey,
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

package com.example.dispute.workflow.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AgentPlatformContractV1Test {

    private static final Path CONTRACT_ROOT =
            Path.of("..", "contracts", "agent-platform", "v1").normalize();
    private static final Path FIXTURE_ROOT = CONTRACT_ROOT.resolve("fixtures");
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Map<String, Class<?>> TYPES =
            Map.of(
                    "case-command-ref.schema.json", CaseCommandRef.class,
                    "room-graph-command.schema.json", RoomGraphCommand.class,
                    "room-graph-result.schema.json", RoomGraphResult.class,
                    "graph-reconcile-response.schema.json", GraphReconcileResponse.class,
                    "artifact-ref.schema.json", ArtifactRef.class,
                    "process-projection.schema.json", ProcessProjection.class,
                    "agent-stream-event.schema.json", AgentStreamEvent.class,
                    "agent-stream-event-v4.schema.json", AgentStreamEventV4.class,
                    "agent-execution-manifest.schema.json", AgentExecutionManifest.class);

    private static AgentPlatformContractCodec codec;

    @BeforeAll
    static void setUp() {
        codec = new AgentPlatformContractCodec(CONTRACT_ROOT);
    }

    static Stream<Path> validFixtures() throws IOException {
        try (Stream<Path> paths = Files.list(FIXTURE_ROOT.resolve("valid"))) {
            return paths.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    static Stream<Path> invalidFixtures() throws IOException {
        try (Stream<Path> paths = Files.list(FIXTURE_ROOT.resolve("invalid"))) {
            return paths.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    static Stream<Path> canonicalFixtures() throws IOException {
        try (Stream<Path> paths = Files.list(FIXTURE_ROOT.resolve("canonical-hash"))) {
            return paths.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest
    @MethodSource("validFixtures")
    void validSharedFixtureRoundTrips(Path path) throws IOException {
        JsonNode fixture = MAPPER.readTree(path.toFile());
        String schemaFile = fixture.required("schema").asText();
        JsonNode instance = fixture.required("instance");

        Object decoded = codec.decode(schemaFile, instance, TYPES.get(schemaFile));
        JsonNode encoded = codec.encode(schemaFile, decoded);

        assertThat(ContractJson.canonicalize(encoded))
                .isEqualTo(ContractJson.canonicalize(instance));
    }

    @ParameterizedTest
    @MethodSource("invalidFixtures")
    void invalidSharedFixtureFailsClosed(Path path) throws IOException {
        JsonNode fixture = MAPPER.readTree(path.toFile());
        String schemaFile = fixture.required("schema").asText();

        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        schemaFile,
                                        fixture.required("instance"),
                                        TYPES.get(schemaFile)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSchemaFailsClosed() {
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        "room-graph-command.v99.schema.json",
                                        MAPPER.createObjectNode(),
                                        RoomGraphCommand.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown contract schema");
    }

    @Test
    void parallelStreamProjectionBindsItsLocalCursor() throws IOException {
        JsonNode fixture =
                MAPPER.readTree(
                        FIXTURE_ROOT.resolve("valid/agent-stream-event-v4-valid.json").toFile());
        ObjectNode instance = (ObjectNode) fixture.required("instance").deepCopy();
        ((ObjectNode) instance.required("payload")).put("next_local_index", 2);

        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        fixture.required("schema").asText(),
                                        instance,
                                        AgentStreamEventV4.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be decoded");
    }

    @Test
    void packagedContractCodecLoadsWithoutAnExternalFilesystemPath() {
        AgentPlatformContractCodec packaged = new AgentPlatformContractCodec();

        assertThat(packaged).isNotNull();
    }

    @Test
    void packagedContractsExactlyMatchTheAuthoritativeContractPack() throws IOException {
        TreeSet<String> files = new TreeSet<>(TYPES.keySet());
        files.add("compatibility-matrix.yaml");
        ClassLoader classLoader = AgentPlatformContractCodec.class.getClassLoader();

        for (String file : files) {
            try (InputStream packaged = classLoader.getResourceAsStream(
                    "contracts/agent-platform/v1/" + file)) {
                assertThat(packaged).as("packaged resource %s", file).isNotNull();
                assertThat(packaged.readAllBytes())
                        .as("packaged resource %s", file)
                        .isEqualTo(Files.readAllBytes(CONTRACT_ROOT.resolve(file)));
            }
        }
    }

    @Test
    void aggregateProviderBudgetRequiresTheExplicitHearingEvidenceSynthesisStage()
            throws IOException {
        JsonNode fixture =
                MAPPER.readTree(
                        FIXTURE_ROOT.resolve("valid/room-graph-command-valid.json").toFile());
        String schemaFile = fixture.required("schema").asText();
        ObjectNode authorized = (ObjectNode) fixture.required("instance").deepCopy();
        authorized.put("room_type", "HEARING");
        authorized.put("stage_code", "EVIDENCE_SYNTHESIZING");
        ((ObjectNode) authorized.required("retry_budget"))
                .put("provider_attempts_remaining", 6);
        ObjectNode authorizedPreimage = authorized.deepCopy();
        authorizedPreimage.remove("request_hash");
        authorized.put("request_hash", ContractJson.sha256Hex(authorizedPreimage));

        RoomGraphCommand decoded = codec.decode(schemaFile, authorized, RoomGraphCommand.class);
        assertThat(decoded.retryBudget().providerAttemptsRemaining()).isEqualTo(6);

        ObjectNode judgeV1 = authorized.deepCopy();
        judgeV1.put("stage_code", "JUDGE_V1_GENERATING");
        assertThatThrownBy(() -> codec.decode(schemaFile, judgeV1, RoomGraphCommand.class))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode evidenceRoom = authorized.deepCopy();
        evidenceRoom.put("room_type", "EVIDENCE");
        assertThatThrownBy(() -> codec.decode(schemaFile, evidenceRoom, RoomGraphCommand.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parallelIntakeCommandBindsSignedRoomAndAggregateProviderBudget()
            throws IOException {
        for (int providerBudget : new int[] {3, 6}) {
            ObjectNode parallel = parallelIntakeCommand(providerBudget);

            RoomGraphCommand decoded = codec.decode(
                    "room-graph-command.schema.json", parallel, RoomGraphCommand.class);

            assertThat(decoded.roomId()).isEqualTo("ROOM_PARALLEL_1");
            assertThat(decoded.retryBudget().providerAttemptsRemaining())
                    .isEqualTo(providerBudget);
            assertThat(decoded.isExactParallelIntakeProfile()).isTrue();
        }
    }

    @Test
    void parallelIntakeCommandRejectsPartialAuthorityButNotTheSharedOutputSchema()
            throws IOException {
        ObjectNode insufficientBudget = parallelIntakeCommand(2);
        assertThatThrownBy(() -> codec.decode(
                        "room-graph-command.schema.json",
                        insufficientBudget,
                        RoomGraphCommand.class))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode missingRoom = parallelIntakeCommand(6);
        missingRoom.remove("room_id");
        assertThatThrownBy(() -> codec.decode(
                        "room-graph-command.schema.json", missingRoom, RoomGraphCommand.class))
                .isInstanceOf(IllegalArgumentException.class);

        JsonNode fixture = MAPPER.readTree(
                FIXTURE_ROOT.resolve("valid/room-graph-command-valid.json").toFile());
        ObjectNode legacy = (ObjectNode) fixture.required("instance").deepCopy();
        ((ObjectNode) legacy.required("invocation_context"))
                .put(
                        "output_schema_version",
                        RoomGraphCommand.PARALLEL_INTAKE_OUTPUT_SCHEMA);
        RoomGraphCommand decoded = codec.decode(
                "room-graph-command.schema.json", legacy, RoomGraphCommand.class);
        assertThat(decoded.isExactParallelIntakeProfile()).isFalse();
    }

    @Test
    void schemaValidShapeStillRespectsTotalPayloadLimit() throws IOException {
        JsonNode fixture =
                MAPPER.readTree(
                        FIXTURE_ROOT.resolve("valid/room-graph-result-valid.json").toFile());
        ObjectNode instance = (ObjectNode) fixture.required("instance").deepCopy();
        ObjectNode proposal =
                (ObjectNode) instance.required("public_event_proposals").required(0).deepCopy();
        proposal.put("payload_ref", "s3://bucket/" + "a".repeat(980));
        ArrayNode proposals = MAPPER.createArrayNode();
        for (int index = 0; index < 100; index++) {
            proposals.add(proposal.deepCopy());
        }
        instance.set("public_event_proposals", proposals);

        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        fixture.required("schema").asText(),
                                        instance,
                                        RoomGraphResult.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds max_serialized_bytes");
    }

    @Test
    void reconcileWrapperMustMatchItsNestedImmutableResult() throws IOException {
        JsonNode fixture =
                MAPPER.readTree(
                        FIXTURE_ROOT
                                .resolve("valid/graph-reconcile-response-valid.json")
                                .toFile());
        ObjectNode instance = (ObjectNode) fixture.required("instance").deepCopy();
        ((ObjectNode) instance.required("result")).put("attempt_id", "attempt-forged");

        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        fixture.required("schema").asText(),
                                        instance,
                                        GraphReconcileResponse.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be decoded");
    }

    @ParameterizedTest
    @MethodSource("canonicalFixtures")
    void rfc8785VectorsMatchSharedBytesAndHash(Path path) throws IOException {
        JsonNode fixture = MAPPER.readTree(path.toFile());
        JsonNode input = fixture.required("input");

        assertThat(new String(ContractJson.canonicalize(input), StandardCharsets.UTF_8))
                .isEqualTo(fixture.required("canonical_utf8").asText());
        assertThat(ContractJson.sha256Hex(input)).isEqualTo(fixture.required("sha256").asText());
    }

    private static ObjectNode parallelIntakeCommand(int providerBudget) throws IOException {
        JsonNode fixture = MAPPER.readTree(
                FIXTURE_ROOT.resolve("valid/room-graph-command-valid.json").toFile());
        ObjectNode instance = (ObjectNode) fixture.required("instance").deepCopy();
        instance.put("room_id", "ROOM_PARALLEL_1");
        ObjectNode event = MAPPER.createObjectNode();
        event.put("artifact_id", "intake.event.parallel-1");
        event.put("schema_version", "intake-turn-event.v2");
        event.put("uri", "urn:intake:event:parallel-1");
        event.put("sha256", "e".repeat(64));
        event.put("size_bytes", 256);
        instance.set("event_ref", event);
        ObjectNode invocation = (ObjectNode) instance.required("invocation_context");
        invocation.put(
                "agent_profile_id", RoomGraphCommand.PARALLEL_INTAKE_AGENT_PROFILE_ID);
        invocation.put(
                "output_schema_version", RoomGraphCommand.PARALLEL_INTAKE_OUTPUT_SCHEMA);
        ((ObjectNode) instance.required("retry_budget"))
                .put("provider_attempts_remaining", providerBudget);
        ObjectNode preimage = instance.deepCopy();
        preimage.remove("request_hash");
        instance.put("request_hash", ContractJson.sha256Hex(preimage));
        return instance;
    }
}

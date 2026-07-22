package com.example.dispute.workflow.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

class EvidenceV2ContractFixtureTest {

    private static final Path CONTRACT_ROOT =
            Path.of("..", "contracts", "agent-platform", "evidence", "v2").normalize();
    private static final Path FIXTURE_ROOT = CONTRACT_ROOT.resolve("fixtures");
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Set<String> CONTRACT_FILES =
            Set.of(
                    "compatibility-matrix.yaml",
                    "evidence-asset-capability.schema.json",
                    "evidence-batch-manifest.schema.json",
                    "evidence-finalization-receipt.schema.json",
                    "evidence-item-proposal.schema.json",
                    "evidence-process-projection.schema.json",
                    "evidence-terminal-proposal.schema.json");
    private static final Map<String, String> INVALID_FIXTURE_REASONS =
            Map.of(
                    "evidence-asset-capability-credential.json",
                    "credential_field_forbidden",
                    "evidence-batch-manifest-formal-action.json",
                    "manifest_formal_action_forbidden",
                    "evidence-batch-manifest-legacy-output-pin.json",
                    "legacy_output_schema_version_forbidden",
                    "evidence-batch-manifest-public-51.json",
                    "public_submission_over_50",
                    "evidence-batch-manifest-signature-algorithm.json",
                    "invalid_manifest_signature_algorithm",
                    "evidence-batch-manifest-unsigned.json",
                    "missing_manifest_signature",
                    "evidence-finalization-receipt-real-formal-write.json",
                    "formal_domain_write_forbidden",
                    "evidence-item-proposal-formal-action.json",
                    "assessment_formal_action_forbidden",
                    "evidence-process-projection-temporal-real-shadow.json",
                    "temporal_real_shadow_forbidden",
                    "evidence-terminal-proposal-formal-action.json",
                    "terminal_formal_action_forbidden");
    private static final List<String> PROFILE_PIN_FIELDS =
            List.of(
                    "graph_version",
                    "checkpoint_schema_version",
                    "state_schema_version",
                    "prompt_version",
                    "model_profile_id",
                    "assessment_output_schema_version",
                    "terminal_output_schema_version",
                    "policy_version",
                    "guardrail_version",
                    "tool_policy_version");
    private static final Map<String, String> EXPECTED_VALID_FIXTURE_HASHES =
            Map.of(
                    "evidence-asset-capability-valid.json",
                    "da72b1e2ef63ef2df4d6e6d4eb7fa3af0696f25d2d5525b2ab90b6a3932c6e27",
                    "evidence-batch-manifest-synthetic-1-valid.json",
                    "6bc875c51b4b5b20f3bcfa1a378ae373bb051b657e1c633de0c6de1c0180adb1",
                    "evidence-batch-manifest-synthetic-100-valid.json",
                    "ed73bff340c633379607f72ed4dac3f06baca92275644175eac1373a32725275",
                    "evidence-batch-manifest-synthetic-8-valid.json",
                    "c124add3fe340a004d2bf2137ea0ea05e12255e308baad4f863b60077bef2364",
                    "evidence-finalization-receipt-valid.json",
                    "26610a9836e63cc5ef91e7673eb12c5199023080a5fbf31ba080823681eead38",
                    "evidence-item-proposal-valid.json",
                    "629688355567e0350ac60cccae3da2bc512ce1b303f23781879c23a1dda38929",
                    "evidence-process-projection-legacy-unavailable-valid.json",
                    "73965859c19d9f96c6b662d7d11b628374a0e84dbdb84aaec0de00b3431ab424",
                    "evidence-process-projection-valid.json",
                    "18a39b7f1c3a99cec98d7879e633e64922b503e36600fb26493d72475b857297",
                    "evidence-terminal-proposal-valid.json",
                    "576449dc4411599966f6819a92de56f35391bb02694c4787ecb4f52580cb64d2");

    private static Map<String, ContractSchema> schemasByVersion;

    @BeforeAll
    static void loadSchemas() throws IOException {
        JsonSchemaFactory factory =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        Map<String, ContractSchema> schemas = new LinkedHashMap<>();
        for (Path path : schemaFiles()) {
            JsonNode document = MAPPER.readTree(path.toFile());
            String schemaVersion =
                    document.required("properties").required("schema_version").required("const").asText();
            assertThat(schemas.put(schemaVersion, new ContractSchema(document, factory.getSchema(document))))
                    .as("unique schema_version in %s", path.getFileName())
                    .isNull();
        }
        schemasByVersion = Map.copyOf(schemas);
    }

    static Stream<Path> validFixtures() throws IOException {
        return fixtureFiles("valid").stream();
    }

    static Stream<Path> invalidFixtures() throws IOException {
        return fixtureFiles("invalid").stream();
    }

    static Stream<SchemaRejectionCase> namedSchemaRejectionCases() {
        return Stream.of(
                new SchemaRejectionCase(
                        "missing_manifest_signature",
                        "evidence-batch-manifest.v1",
                        "evidence-batch-manifest-synthetic-1-valid.json",
                        value -> value.remove("signature"),
                        "signature"),
                new SchemaRejectionCase(
                        "invalid_manifest_signature",
                        "evidence-batch-manifest.v1",
                        "evidence-batch-manifest-synthetic-1-valid.json",
                        value -> value.put("signature", "not+base64url="),
                        "signature"),
                new SchemaRejectionCase(
                        "assessment_output_schema_pin_mismatch",
                        "evidence-item-assessment.v1",
                        "evidence-item-proposal-valid.json",
                        value ->
                                ((ObjectNode) value.required("profile_versions"))
                                        .put(
                                                "assessment_output_schema_version",
                                                "evidence-item-assessment.v0"),
                        "assessment_output_schema_version"),
                new SchemaRejectionCase(
                        "terminal_output_schema_pin_mismatch",
                        "evidence-batch-proposal.v1",
                        "evidence-terminal-proposal-valid.json",
                        value ->
                                ((ObjectNode) value.required("profile_versions"))
                                        .put(
                                                "terminal_output_schema_version",
                                                "evidence-batch-proposal.v0"),
                        "terminal_output_schema_version"),
                new SchemaRejectionCase(
                        "forbidden_authorization_proof_ref",
                        "evidence-batch-manifest.v1",
                        "evidence-batch-manifest-synthetic-1-valid.json",
                        value -> value.putObject("authorization_proof_ref").put("proof_id", "FORBIDDEN"),
                        "authorization_proof_ref"));
    }

    @Test
    void contractPackContainsExactlySixSchemasAndItsCompatibilityMatrix() throws IOException {
        TreeSet<String> authoritativeFiles;
        try (Stream<Path> paths = Files.list(CONTRACT_ROOT)) {
            authoritativeFiles =
                    paths.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
        assertThat(authoritativeFiles).containsExactlyElementsOf(new TreeSet<>(CONTRACT_FILES));
        assertThat(schemasByVersion).hasSize(6);
        assertThat(
                        fixtureFiles("valid").stream()
                                .map(path -> path.getFileName().toString())
                                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)))
                .containsExactlyElementsOf(new TreeSet<>(EXPECTED_VALID_FIXTURE_HASHES.keySet()));
        assertThat(
                        fixtureFiles("invalid").stream()
                                .map(path -> path.getFileName().toString())
                                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)))
                .containsExactlyElementsOf(new TreeSet<>(INVALID_FIXTURE_REASONS.keySet()));

        JsonNode matrix;
        try (InputStream input =
                Files.newInputStream(CONTRACT_ROOT.resolve("compatibility-matrix.yaml"))) {
            matrix = MAPPER.valueToTree(new Yaml().load(input));
        }
        TreeSet<String> matrixSchemaFiles = new TreeSet<>();
        matrix.required("contracts")
                .elements()
                .forEachRemaining(contract -> matrixSchemaFiles.add(contract.required("file").asText()));
        assertThat(matrixSchemaFiles)
                .containsExactlyElementsOf(
                        CONTRACT_FILES.stream()
                                .filter(file -> file.endsWith(".schema.json"))
                                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));

        JsonNode runtimeGate = matrix.required("runtime_gate");
        assertThat(runtimeGate.required("public_submission_max").intValue()).isEqualTo(50);
        List<Integer> signedSyntheticCounts = new ArrayList<>();
        runtimeGate
                .required("signed_synthetic_counts")
                .forEach(value -> signedSyntheticCounts.add(value.intValue()));
        assertThat(signedSyntheticCounts).containsExactly(1, 8, 100);
        assertThat(runtimeGate.required("formal_graph_sink_allowed").asBoolean()).isFalse();
        assertThat(runtimeGate.required("real_case_shadow_allowed").asBoolean()).isFalse();
        assertThat(runtimeGate.required("temporal_evidence_allocation_allowed").asBoolean())
                .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validFixtures")
    void validFixturePassesDraft202012AndItsDeclaredSelfHash(Path path) throws IOException {
        JsonNode fixture = MAPPER.readTree(path.toFile());
        ContractSchema schema = schemaFor(fixture, path);
        String fixtureName = path.getFileName().toString();

        assertThat(schema.validator().validate(fixture)).as("schema errors for %s", path).isEmpty();
        assertSelfHash(fixture, schema.document(), path);
        assertThat(EXPECTED_VALID_FIXTURE_HASHES)
                .as("frozen expected hash for %s", path)
                .containsKey(fixtureName);
        String hashField = schema.document().required("x-self-hash").required("field").asText();
        assertThat(fixture.required(hashField).asText())
                .as("frozen fixture hash for %s", path)
                .isEqualTo(EXPECTED_VALID_FIXTURE_HASHES.get(fixtureName));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFixtures")
    void invalidFixtureIsRejectedByDraft202012(Path path) throws IOException {
        JsonNode fixture = MAPPER.readTree(path.toFile());
        ContractSchema schema = schemaFor(fixture, path);
        Set<ValidationMessage> errors = schema.validator().validate(fixture);
        String reason = INVALID_FIXTURE_REASONS.get(path.getFileName().toString());

        assertThat(reason).as("named rejection reason for %s", path).isNotBlank();
        assertThat(errors)
                .as("invalid fixture must fail closed [%s]: %s", reason, path)
                .isNotEmpty();
    }

    @Test
    void manifestDeclaresDirectJavaEs256AuthorityAndGatewayBinding() {
        JsonNode schema = schemasByVersion.get("evidence-batch-manifest.v1").document();

        assertThat(schema.required("x-self-hash"))
                .isEqualTo(
                        MAPPER.valueToTree(
                                Map.of(
                                        "algorithm", "SHA-256",
                                        "field", "manifest_hash",
                                        "preimage", "omit_top_level_fields",
                                        "omit_fields", List.of("manifest_hash", "signature"))));
        assertThat(schema.required("x-signature"))
                .isEqualTo(
                        MAPPER.valueToTree(
                                Map.of(
                                        "algorithm", "ES256",
                                        "covers", "manifest_hash",
                                        "encoding", "JOSE_P1363_BASE64URL")));
        assertThat(schema.required("x-gateway-cross-binding"))
                .isEqualTo(
                        MAPPER.valueToTree(
                                Map.of(
                                        "source_schema_version", "room-graph-command.v1",
                                        "manifest_ref_field", "domain_snapshot_ref",
                                        "requires_verified_java_envelope", true,
                                        "failure", "BEFORE_CHECKPOINT_MUTATION",
                                        "room_fence_is_graph_lease_fence", false)));
        assertThat(jsonText(schema.required("required")))
                .contains("signature_algorithm", "signing_key_id", "signature");

        String constraints = jsonText(schema.required("x-semantic-constraints"));
        assertThat(constraints)
                .containsIgnoringCase("room-graph-command.v1")
                .containsIgnoringCase("gateway")
                .contains(
                        "GATEWAY_COMMAND_EXACT_BINDING",
                        "command/run/attempt",
                        "epoch/room fence",
                        "registry/profile pins");
    }

    @Test
    void contractPackContainsNoDetachedAuthorizationProofReference() throws IOException {
        for (Path path : schemaFiles()) {
            assertThat(jsonText(MAPPER.readTree(path.toFile())))
                    .as("detached authority is forbidden in %s", path)
                    .doesNotContain("authorization_proof_ref");
        }
        for (String disposition : List.of("valid", "invalid")) {
            for (Path path : fixtureFiles(disposition)) {
                assertThat(jsonText(MAPPER.readTree(path.toFile())))
                        .as("detached authority is forbidden in %s", path)
                        .doesNotContain("authorization_proof_ref");
            }
        }
        assertThat(Files.readString(CONTRACT_ROOT.resolve("compatibility-matrix.yaml")))
                .doesNotContain("authorization_proof_ref");
    }

    @Test
    void validFixturesHaveDualOutputPinsAndDirectManifestSignatureShape() throws IOException {
        for (Path path : fixtureFiles("valid")) {
            JsonNode fixture = MAPPER.readTree(path.toFile());
            if (fixture.has("profile_versions")) {
                assertDualOutputPins(fixture.required("profile_versions"), path);
            }
            if (fixture.has("version_pins")) {
                assertDualOutputPins(fixture.required("version_pins"), path);
            }
            if ("evidence-batch-manifest.v1".equals(fixture.path("schema_version").asText())) {
                assertThat(fixture.required("signature_algorithm").asText()).isEqualTo("ES256");
                assertThat(fixture.required("signing_key_id").asText())
                        .matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
                assertThat(fixture.required("signature").asText())
                        .matches("^[A-Za-z0-9_-]{86}$")
                        .doesNotContain("=");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namedSchemaRejectionCases")
    void trustAndVersionConfusionFailuresHaveNamedReasons(SchemaRejectionCase rejection)
            throws IOException {
        ObjectNode fixture =
                (ObjectNode)
                        MAPPER.readTree(
                                FIXTURE_ROOT.resolve("valid").resolve(rejection.fixtureName()).toFile());
        rejection.mutation().accept(fixture);

        Set<ValidationMessage> errors =
                schemasByVersion.get(rejection.schemaVersion()).validator().validate(fixture);
        assertThat(errors).as(rejection.reason()).isNotEmpty();
        assertThat(errors.toString()).as(rejection.reason()).contains(rejection.expectedErrorToken());
    }

    @Test
    void legacySingleOutputSchemaVersionIsRejectedEverywhere() throws IOException {
        for (String fixtureName :
                List.of(
                        "evidence-batch-manifest-synthetic-1-valid.json",
                        "evidence-item-proposal-valid.json",
                        "evidence-terminal-proposal-valid.json")) {
            ObjectNode fixture = readValidObject(fixtureName);
            ((ObjectNode) fixture.required("profile_versions"))
                    .put("output_schema_version", "evidence-batch-proposal.v1");
            assertThat(schemaFor(fixture, FIXTURE_ROOT.resolve("valid").resolve(fixtureName))
                            .validator()
                            .validate(fixture))
                    .as("legacy output_schema_version must be rejected in %s", fixtureName)
                    .isNotEmpty();
        }

        ObjectNode projection = readValidObject("evidence-process-projection-valid.json");
        ((ObjectNode) projection.required("version_pins"))
                .put("output_schema_version", "evidence-batch-proposal.v1");
        assertThat(schemasByVersion
                        .get("evidence-process-projection.v1")
                        .validator()
                        .validate(projection))
                .as("legacy output_schema_version must be rejected in projection pins")
                .isNotEmpty();
    }

    @Test
    void validDocumentsAreExactlyCrossBoundToTheVerifiedRoomGraphCommand()
            throws IOException {
        JsonNode manifest = readValidObject("evidence-batch-manifest-synthetic-1-valid.json");
        JsonNode capability = readValidObject("evidence-asset-capability-valid.json");
        JsonNode assessment = readValidObject("evidence-item-proposal-valid.json");
        JsonNode terminal = readValidObject("evidence-terminal-proposal-valid.json");
        JsonNode projection = readValidObject("evidence-process-projection-valid.json");
        JsonNode item = manifest.required("items").required(0);
        JsonNode command = manifest.required("command_binding");

        assertEqualFields(
                manifest,
                capability,
                "registration_id",
                "manifest_id",
                "manifest_hash",
                "tenant_surrogate",
                "case_id",
                "room_epoch",
                "fencing_token",
                "thread_id",
                "actor_scope_hash",
                "agent_session_id");
        assertEqualFields(
                item,
                capability,
                "evidence_id",
                "item_hash",
                "owner_participant_id",
                "owner_role",
                "visibility",
                "object_ref",
                "immutable_object_version",
                "object_sha256",
                "content_type",
                "byte_size",
                "privacy_basis",
                "parse_ref",
                "parse_hash",
                "parse_status",
                "permitted_modalities");
        assertEqualFields(manifest, assessment, "manifest_id", "manifest_hash", "thread_id");
        assertEqualFields(manifest, terminal, "manifest_id", "manifest_hash", "thread_id");
        assertEqualFields(
                command, assessment, "command_id", "logical_run_id", "attempt_id");
        assertEqualFields(command, terminal, "command_id", "logical_run_id", "attempt_id");
        assertThat(assessment.required("profile_versions"))
                .isEqualTo(manifest.required("profile_versions"));
        assertThat(terminal.required("profile_versions"))
                .isEqualTo(manifest.required("profile_versions"));
        assertThat(capability.required("profile_versions_hash").asText())
                .isEqualTo(ContractJson.sha256Hex(manifest.required("profile_versions")));
        assertThat(terminal.required("assessment_refs").required(0).required("assessment_hash"))
                .isEqualTo(assessment.required("assessment_hash"));

        JsonNode activeRun = projection.required("active_graph_run");
        assertEqualFields(command, activeRun, "command_id", "logical_run_id", "attempt_id");
        assertEqualFields(manifest, activeRun, "manifest_id", "manifest_hash");
        assertThat(projection.required("room_epoch")).isEqualTo(manifest.required("room_epoch"));
        assertThat(projection.required("fencing_token"))
                .isEqualTo(manifest.required("fencing_token"));
        for (String field : PROFILE_PIN_FIELDS) {
            assertThat(projection.required("version_pins").required(field))
                    .as("projection version pin %s", field)
                    .isEqualTo(manifest.required("profile_versions").required(field));
        }

        ObjectNode gatewayBinding = verifiedGatewayBinding(manifest);
        assertVerifiedRoomGraphCommandBinding(manifest, gatewayBinding);
        gatewayBinding.put("room_epoch", manifest.required("room_epoch").longValue() + 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> assertVerifiedRoomGraphCommandBinding(manifest, gatewayBinding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("room_graph_command_binding_mismatch:room_epoch");

        long currentRoomFence = manifest.required("fencing_token").longValue();
        assertCurrentRoomFence(manifest, currentRoomFence);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> assertCurrentRoomFence(manifest, currentRoomFence + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("room_fence_mismatch");
    }

    @Test
    void signedSyntheticManifestFixturesCoverExactlyOneEightAndOneHundredItems()
            throws IOException {
        TreeSet<Integer> counts = new TreeSet<>();
        for (Path path : fixtureFiles("valid")) {
            JsonNode fixture = MAPPER.readTree(path.toFile());
            if (!"evidence-batch-manifest.v1".equals(fixture.path("schema_version").asText())) {
                continue;
            }
            int itemCount = fixture.required("item_count").intValue();
            counts.add(itemCount);
            assertThat(fixture.required("execution_scope").asText())
                    .isEqualTo("SIGNED_SYNTHETIC_ONLY");
            assertThat(fixture.required("formal_sink_eligible").asBoolean()).isFalse();
            assertThat(fixture.required("ordered_item_keys")).hasSize(itemCount);
            assertThat(fixture.required("items")).hasSize(itemCount);
        }

        assertThat(counts).containsExactly(1, 8, 100);
    }

    private static ContractSchema schemaFor(JsonNode fixture, Path path) {
        String schemaVersion = fixture.required("schema_version").asText();
        assertThat(schemasByVersion)
                .as("fixture %s declares a known schema_version", path)
                .containsKey(schemaVersion);
        return schemasByVersion.get(schemaVersion);
    }

    private static ObjectNode readValidObject(String fixtureName) throws IOException {
        return (ObjectNode)
                MAPPER.readTree(FIXTURE_ROOT.resolve("valid").resolve(fixtureName).toFile());
    }

    private static void assertDualOutputPins(JsonNode pins, Path path) {
        assertThat(pins.has("output_schema_version"))
                .as("ambiguous legacy output pin in %s", path)
                .isFalse();
        assertThat(pins.required("assessment_output_schema_version").asText())
                .as("assessment output pin in %s", path)
                .isEqualTo("evidence-item-assessment.v1");
        assertThat(pins.required("terminal_output_schema_version").asText())
                .as("terminal output pin in %s", path)
                .isEqualTo("evidence-batch-proposal.v1");
    }

    private static void assertEqualFields(JsonNode expected, JsonNode actual, String... fields) {
        for (String field : fields) {
            assertThat(actual.required(field))
                    .as("cross-document binding for %s", field)
                    .isEqualTo(expected.required(field));
        }
    }

    private static ObjectNode verifiedGatewayBinding(JsonNode manifest) {
        ObjectNode binding = MAPPER.createObjectNode();
        JsonNode command = manifest.required("command_binding");
        for (String field : List.of("command_id", "logical_run_id", "attempt_id")) {
            binding.set(field, command.required(field));
        }
        for (String field :
                List.of(
                        "registration_id",
                        "tenant_surrogate",
                        "case_id",
                        "room_type",
                        "room_epoch",
                        "thread_id",
                        "actor_id",
                        "actor_role",
                        "actor_scope_hash")) {
            binding.set(field, manifest.required(field));
        }
        binding.set("profile_versions", manifest.required("profile_versions"));
        return binding;
    }

    private static void assertVerifiedRoomGraphCommandBinding(
            JsonNode manifest, JsonNode gatewayBinding) {
        ObjectNode expected = verifiedGatewayBinding(manifest);
        expected.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!expected.required(field).equals(gatewayBinding.path(field))) {
                                throw new IllegalArgumentException(
                                        "room_graph_command_binding_mismatch:" + field);
                            }
                        });
    }

    private static void assertCurrentRoomFence(JsonNode manifest, long currentRoomFence) {
        if (manifest.required("fencing_token").longValue() != currentRoomFence) {
            throw new IllegalArgumentException("room_fence_mismatch");
        }
    }

    private static String jsonText(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot render contract JSON", exception);
        }
    }

    private static void assertSelfHash(JsonNode fixture, JsonNode schema, Path path) {
        JsonNode declaration = schema.required("x-self-hash");
        String hashField = declaration.required("field").asText();
        ObjectNode preimage = fixture.deepCopy();
        JsonNode omitted = declaration.path("omit_fields");
        if (omitted.isArray()) {
            omitted.forEach(field -> preimage.remove(field.asText()));
        } else {
            preimage.remove(hashField);
        }

        assertThat(ContractJson.sha256Hex(preimage))
                .as("declared self hash for %s", path)
                .isEqualTo(fixture.required(hashField).asText());
    }

    private static List<Path> schemaFiles() throws IOException {
        try (Stream<Path> paths = Files.list(CONTRACT_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> fixtureFiles(String disposition) throws IOException {
        try (Stream<Path> paths = Files.list(FIXTURE_ROOT.resolve(disposition))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private record ContractSchema(JsonNode document, JsonSchema validator) {}

    private record SchemaRejectionCase(
            String reason,
            String schemaVersion,
            String fixtureName,
            Consumer<ObjectNode> mutation,
            String expectedErrorToken) {
        @Override
        public String toString() {
            return reason;
        }
    }
}

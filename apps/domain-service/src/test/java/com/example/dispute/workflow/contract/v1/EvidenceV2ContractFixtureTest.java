package com.example.dispute.workflow.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
            Path.of("..", "..", "contracts", "agent-platform", "evidence", "v2").normalize();
    private static final Path FIXTURE_ROOT = CONTRACT_ROOT.resolve("fixtures");
    private static final Path ROOM_GRAPH_COMMAND_FIXTURE =
            Path.of(
                            "..",
                            "..",
                            "contracts",
                            "agent-platform",
                            "v1",
                            "fixtures",
                            "valid",
                            "room-graph-command-evidence-valid.json")
                    .normalize();
    private static final Path ROOM_GRAPH_COMMAND_SCHEMA =
            Path.of(
                            "..",
                            "..",
                            "contracts",
                            "agent-platform",
                            "v1",
                            "room-graph-command.schema.json")
                    .normalize();
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Set<String> PRODUCT_CONTRACT_FILES =
            Set.of(
                    "compatibility-matrix.yaml",
                    "evidence-asset-capability.schema.json",
                    "evidence-batch-manifest.schema.json",
                    "evidence-finalization-receipt.schema.json",
                    "evidence-item-proposal.schema.json",
                    "evidence-process-projection.schema.json",
                    "evidence-terminal-proposal.schema.json");
    private static final Map<String, String> GOVERNANCE_SCHEMA_VERSIONS =
            Map.of(
                    "phase5-wave-a-acceptance.schema.json",
                    "phase5-wave-a-acceptance.v1");
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
                    "bdd7c2d9065320c2d55e398589d6b46f25f38a26fefb9dc966566163670a44ba",
                    "evidence-batch-manifest-synthetic-1-valid.json",
                    "cd6153b05b81e9362cced88872f596bea0cf8e456889cb27bb34fce290be04e3",
                    "evidence-batch-manifest-synthetic-100-valid.json",
                    "45946fb38d24320317650c117a2eee69ad6dbb233bcdf353cc4b00f4c7c09dbf",
                    "evidence-batch-manifest-synthetic-8-valid.json",
                    "c0b5ef18c5578cd903d86e4affe1d546e0f9a741f309d1426746e9db6906c18f",
                    "evidence-finalization-receipt-valid.json",
                    "22e2243f8250c198333215c9c554ece42032d31821b9fd35e174728711919560",
                    "evidence-item-proposal-valid.json",
                    "2945f7364bce768063509edf28972cad140bddec3d894170f5818e88853ecb23",
                    "evidence-process-projection-legacy-unavailable-valid.json",
                    "ffbd340481fd1647afe2882308c24292d7b4e284e28adb114fce16f785002056",
                    "evidence-process-projection-target-temporal-valid.json",
                    "47b505669b2c9214b05af9894f6d788f068a592ef3caa5ecdbe105d94dc0d8cf",
                    "evidence-process-projection-valid.json",
                    "2f1037416b62ae524ec7882eeb2e57bfcf773a2267e09449e77cbf82bf170908",
                    "evidence-terminal-proposal-valid.json",
                    "3648869670940a1475f743cce003faa1389f8fb376cbc8e4078bc2dc76a00d72");

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
        TreeSet<String> expectedFiles = new TreeSet<>(PRODUCT_CONTRACT_FILES);
        expectedFiles.addAll(GOVERNANCE_SCHEMA_VERSIONS.keySet());
        assertThat(authoritativeFiles).containsExactlyElementsOf(expectedFiles);
        assertThat(schemasByVersion).hasSize(6);
        for (Map.Entry<String, String> governanceSchema :
                GOVERNANCE_SCHEMA_VERSIONS.entrySet()) {
            JsonNode document =
                    MAPPER.readTree(CONTRACT_ROOT.resolve(governanceSchema.getKey()).toFile());
            assertThat(document.required("$schema").asText())
                    .as("governance schema dialect for %s", governanceSchema.getKey())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(document.required("properties")
                            .required("schema_version")
                            .required("const")
                            .asText())
                    .as("governance schema version for %s", governanceSchema.getKey())
                    .isEqualTo(governanceSchema.getValue());
        }
        TreeSet<String> validFixtureFiles =
                fixtureFiles("valid").stream()
                        .map(path -> path.getFileName().toString())
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        TreeSet<String> invalidFixtureFiles =
                fixtureFiles("invalid").stream()
                        .map(path -> path.getFileName().toString())
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertThat(validFixtureFiles)
                .containsExactlyElementsOf(new TreeSet<>(EXPECTED_VALID_FIXTURE_HASHES.keySet()));
        assertThat(invalidFixtureFiles)
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
                        PRODUCT_CONTRACT_FILES.stream()
                                .filter(file -> file.endsWith(".schema.json"))
                                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
        for (String signedContract :
                List.of("evidence-batch-manifest.v1", "evidence-asset-capability.v1")) {
            assertThat(matrix
                            .required("contracts")
                            .required(signedContract)
                            .required("signature_input_encoding")
                            .asText())
                    .as("unambiguous signing input for %s", signedContract)
                    .isEqualTo("ASCII_LOWERCASE_HEX_TEXT");
        }
        JsonNode commandBinding =
                matrix.required("authorization").required("room_graph_command_cross_binding");
        assertThat(commandBinding
                        .required("canonical_command_fixture")
                        .required("path")
                        .asText())
                .isEqualTo(
                        "contracts/agent-platform/v1/fixtures/valid/"
                                + "room-graph-command-evidence-valid.json");
        JsonNode profileVersioning = matrix.required("profile_versioning");
        assertThat(profileVersioning
                        .required("room_graph_command_output_schema_maps_to")
                        .asText())
                .isEqualTo("terminal_output_schema_version");
        assertThat(profileVersioning
                        .required("graph_registry_output_schema_maps_to")
                        .asText())
                .isEqualTo("terminal_output_schema_version");
        assertThat(profileVersioning
                        .required("internal_item_lcel_parser_output_schema_maps_to")
                        .asText())
                .isEqualTo("assessment_output_schema_version");

        assertThat(matrix.required("runtime_gate_scope").asText())
                .isEqualTo("PHASE5_DEFAULT_AND_SIGNED_SYNTHETIC_SHADOW");
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
        JsonNode targetProfile =
                matrix.required("projection_compatibility").required("target_e2e_temporal");
        assertThat(targetProfile.required("scope").asText())
                .isEqualTo("ACTIVATION_BOUND_TARGET_E2E_ONLY");
        assertThat(targetProfile.required("writer_mode").asText()).isEqualTo("TEMPORAL");
        assertThat(targetProfile.required("graph_runtime_mode").asText())
                .isEqualTo("TARGET_E2E_CANDIDATE");
        assertThat(targetProfile.required("activation_case_scope").asText())
                .isEqualTo("persisted_target_e2e_case_reservation");
        assertThat(targetProfile.required("temporal_evidence_allocation_allowed").asBoolean())
                .isTrue();
        assertThat(targetProfile.required("formal_graph_sink_allowed").asBoolean()).isFalse();
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
    void manifestAndCapabilityDeclareUnambiguousJavaEs256SigningInput() {
        JsonNode schema = schemasByVersion.get("evidence-batch-manifest.v1").document();
        JsonNode capabilitySchema =
                schemasByVersion.get("evidence-asset-capability.v1").document();

        assertThat(schema.required("x-self-hash"))
                .isEqualTo(
                        MAPPER.valueToTree(
                                Map.of(
                                        "algorithm", "SHA-256",
                                        "field", "manifest_hash",
                                        "preimage", "omit_top_level_fields",
                                        "omit_fields", List.of("manifest_hash", "signature"))));
        assertSignatureDeclaration(schema, "manifest_hash");
        assertSignatureDeclaration(capabilitySchema, "capability_hash");
        ObjectNode gateway = (ObjectNode) schema.required("x-gateway-cross-binding").deepCopy();
        assertThat(gateway.remove("manifest_ref_payload_binding"))
                .isEqualTo(
                        MAPPER.valueToTree(
                                Map.of(
                                        "canonicalization", "RFC_8785",
                                        "encoding", "UTF-8",
                                        "sha256_field", "domain_snapshot_ref.sha256",
                                        "size_bytes_field", "domain_snapshot_ref.size_bytes",
                                        "covers", "FULL_CANONICAL_SIGNED_MANIFEST_PAYLOAD")));
        assertThat(gateway.remove("manifest_internal_self_hash"))
                .isEqualTo(
                        MAPPER.valueToTree(
                                Map.of(
                                        "field", "manifest_hash",
                                        "canonicalization", "RFC_8785",
                                        "omit_top_level_fields",
                                                List.of("manifest_hash", "signature"))));
        assertThat(gateway.remove("verification_order"))
                .isEqualTo(
                        MAPPER.valueToTree(
                                List.of(
                                        "VERIFY_REFERENCED_PAYLOAD_HASH_AND_SIZE_BEFORE_PARSE",
                                        "PARSE_MANIFEST",
                                        "VERIFY_MANIFEST_SELF_HASH",
                                        "VERIFY_MANIFEST_SIGNATURE")));
        assertThat(gateway)
                .isEqualTo(
                        MAPPER.valueToTree(
                                Map.of(
                                        "source_schema_version", "room-graph-command.v1",
                                        "manifest_ref_field", "domain_snapshot_ref",
                                        "requires_verified_java_envelope", true,
                                        "command_binds_room_fencing_token", false,
                                        "room_fencing_token_authority", "JAVA_SIGNED_MANIFEST",
                                        "checkpoint_lease_fence_authority", "GRAPH_RUNTIME",
                                        "java_finalizer_revalidates_room_fencing_token", true,
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
                        "room type/epoch",
                        "invocation registry/profile",
                        "current Graph lease fence");
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
        JsonNode matrix;
        try (InputStream input =
                Files.newInputStream(CONTRACT_ROOT.resolve("compatibility-matrix.yaml"))) {
            matrix = MAPPER.valueToTree(new Yaml().load(input));
        }
        assertThat(matrix
                        .required("authorization")
                        .required("manifest")
                        .required("detached_authorization_proof_ref")
                        .asText())
                .isEqualTo("forbidden");
        assertThat(matrix.required("rules").required("authorization_proof_ref").asText())
                .isEqualTo("forbidden");
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
            if (Set.of("evidence-batch-manifest.v1", "evidence-asset-capability.v1")
                    .contains(fixture.path("schema_version").asText())) {
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
        JsonNode finalizationReceipt =
                readValidObject("evidence-finalization-receipt-valid.json");
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
        assertThat(finalizationReceipt.has("profile_versions")).isFalse();
        assertThat(terminal.required("assessment_refs").required(0).required("assessment_hash"))
                .isEqualTo(assessment.required("assessment_hash"));
        assertThat(finalizationReceipt.required("operation_binding").required("manifest_hash"))
                .isEqualTo(manifest.required("manifest_hash"));
        assertThat(finalizationReceipt.required("operation_binding").required("proposal_hash"))
                .isEqualTo(terminal.required("proposal_hash"));
        JsonNode receiptBinding = finalizationReceipt.required("operation_binding");
        assertEqualFields(
                command,
                receiptBinding,
                "command_id",
                "logical_run_id",
                "attempt_id");
        assertThat(receiptBinding.required("thread_id"))
                .isEqualTo(manifest.required("thread_id"));
        assertThat(receiptBinding.required("dossier_target_version"))
                .isEqualTo(manifest.required("dossier_target_version"));
        assertEqualFields(
                manifest,
                finalizationReceipt,
                "tenant_surrogate",
                "case_id",
                "room_epoch",
                "fencing_token");
        assertThat(finalizationReceipt.required("operation_key").asText())
                .isEqualTo(
                        "evidence.batch.merge:%s:%d:%s:%d"
                                .formatted(
                                        manifest.required("case_id").asText(),
                                        manifest.required("room_epoch").longValue(),
                                        manifest.required("manifest_hash").asText(),
                                        manifest.required("dossier_target_version").longValue()));

        JsonNode activeRun = projection.required("active_graph_run");
        assertEqualFields(command, activeRun, "command_id", "logical_run_id", "attempt_id");
        assertEqualFields(manifest, activeRun, "manifest_id", "manifest_hash");
        assertThat(projection.required("room_epoch")).isEqualTo(manifest.required("room_epoch"));
        assertThat(projection.required("fencing_token"))
                .isEqualTo(manifest.required("fencing_token"));
        assertThat(projection.required("pending_operation_key").asText())
                .isEqualTo(
                        "evidence.graph.request:%s:%d:%s:%s"
                                .formatted(
                                        manifest.required("case_id").asText(),
                                        manifest.required("room_epoch").longValue(),
                                        manifest.required("manifest_hash").asText(),
                                        command.required("logical_run_id").asText()));
        for (String field : PROFILE_PIN_FIELDS) {
            assertThat(projection.required("version_pins").required(field))
                    .as("projection version pin %s", field)
                    .isEqualTo(manifest.required("profile_versions").required(field));
        }

        ObjectNode verifiedRoomGraphCommand = readVerifiedEvidenceRoomGraphCommand();
        assertVerifiedRoomGraphCommandBinding(manifest, verifiedRoomGraphCommand);
        ObjectNode mismatchedRoomEpoch = verifiedRoomGraphCommand.deepCopy();
        mismatchedRoomEpoch.put("room_epoch", manifest.required("room_epoch").longValue() + 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                assertVerifiedRoomGraphCommandBinding(
                                        manifest, mismatchedRoomEpoch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("room_graph_command_binding_mismatch:room_epoch");
        ObjectNode mismatchedActorScope = verifiedRoomGraphCommand.deepCopy();
        ((ArrayNode) mismatchedActorScope.required("actor_scope").required("capabilities"))
                .add("evidence_parser.write");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                assertVerifiedRoomGraphCommandBinding(
                                        manifest, mismatchedActorScope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("room_graph_command_binding_mismatch:actor_scope_hash");

        long currentRoomFence = manifest.required("fencing_token").longValue();
        long currentGraphLeaseFence = 7001L;
        assertThat(currentGraphLeaseFence).isNotEqualTo(currentRoomFence);
        assertThat(verifiedRoomGraphCommand.has("fencing_token")).isFalse();
        assertThat(verifiedRoomGraphCommand.has("graph_lease_fencing_token")).isFalse();
        assertCurrentRoomFence(manifest, currentRoomFence);
        assertCurrentGraphLeaseFence(7001L, currentGraphLeaseFence);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> assertCurrentRoomFence(manifest, currentRoomFence + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("room_fence_mismatch");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> assertCurrentGraphLeaseFence(7002L, currentGraphLeaseFence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("graph_lease_fence_mismatch");
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

    private static void assertSignatureDeclaration(JsonNode schema, String hashField) {
        assertThat(schema.required("x-signature"))
                .isEqualTo(
                        MAPPER.valueToTree(
                                Map.of(
                                        "algorithm", "ES256",
                                        "covers", hashField,
                                        "input_encoding", "ASCII_LOWERCASE_HEX_TEXT",
                                        "encoding", "JOSE_P1363_BASE64URL")));
        JsonNode signature = schema.required("properties").required("signature");
        assertThat(signature.required("minLength").intValue()).isEqualTo(86);
        assertThat(signature.required("maxLength").intValue()).isEqualTo(86);
        assertThat(signature.required("pattern").asText()).isEqualTo("^[A-Za-z0-9_-]{86}$");
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

    private static ObjectNode readVerifiedEvidenceRoomGraphCommand() throws IOException {
        JsonNode document = MAPPER.readTree(ROOM_GRAPH_COMMAND_FIXTURE.toFile());
        assertThat(document.required("schema").asText())
                .isEqualTo("room-graph-command.schema.json");
        ObjectNode command = (ObjectNode) document.required("instance").deepCopy();
        JsonNode schemaDocument = MAPPER.readTree(ROOM_GRAPH_COMMAND_SCHEMA.toFile());
        JsonSchema schema =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                        .getSchema(schemaDocument);
        assertThat(schema.validate(command)).as("canonical Evidence RoomGraphCommand").isEmpty();
        ObjectNode requestPreimage = command.deepCopy();
        requestPreimage.remove("request_hash");
        assertThat(command.required("request_hash").asText())
                .isEqualTo(ContractJson.sha256Hex(requestPreimage));
        return command;
    }

    private static void assertVerifiedRoomGraphCommandBinding(
            JsonNode manifest, JsonNode verifiedCommand) {
        JsonNode manifestCommand = manifest.required("command_binding");
        for (String field : List.of("command_id", "logical_run_id", "attempt_id")) {
            requireBinding(
                    verifiedCommand.required(field), manifestCommand.required(field), field);
        }
        for (String field :
                List.of("tenant_surrogate", "case_id", "room_type", "room_epoch", "thread_id")) {
            requireBinding(verifiedCommand.required(field), manifest.required(field), field);
        }
        requireText(verifiedCommand, "schema_version", "room-graph-command.v1");
        requireText(verifiedCommand, "graph_key", "evidence.v2");
        requireBinding(
                verifiedCommand.required("graph_version"),
                manifest.required("profile_versions").required("graph_version"),
                "graph_version");
        requireBinding(
                verifiedCommand.required("checkpoint_schema_version"),
                manifest.required("profile_versions").required("checkpoint_schema_version"),
                "checkpoint_schema_version");
        requireBinding(
                verifiedCommand.required("deadline_at"),
                manifestCommand.required("deadline_at"),
                "deadline_at");

        JsonNode actorScope = verifiedCommand.required("actor_scope");
        requireBinding(actorScope.required("actor_id"), manifest.required("actor_id"), "actor_id");
        requireBinding(
                actorScope.required("actor_role"), manifest.required("actor_role"), "actor_role");
        requireBinding(
                actorScope.required("audience"), manifest.required("actor_role"), "audience");
        if (!ContractJson.sha256Hex(actorScope)
                .equals(manifest.required("actor_scope_hash").asText())) {
            throw new IllegalArgumentException(
                    "room_graph_command_binding_mismatch:actor_scope_hash");
        }

        JsonNode invocation = verifiedCommand.required("invocation_context");
        JsonNode profile = manifest.required("profile_versions");
        requireBinding(
                invocation.required("prompt_profile_id"),
                profile.required("prompt_version"),
                "prompt_version");
        requireBinding(
                invocation.required("model_profile_id"),
                profile.required("model_profile_id"),
                "model_profile_id");
        requireBinding(
                invocation.required("output_schema_version"),
                profile.required("terminal_output_schema_version"),
                "terminal_output_schema_version");
        requireBinding(
                invocation.required("policy_version"),
                profile.required("policy_version"),
                "policy_version");
        requireBinding(
                invocation.required("guardrail_version"),
                profile.required("guardrail_version"),
                "guardrail_version");
        requireBinding(
                invocation.required("tool_capabilities"),
                actorScope.required("capabilities"),
                "tool_capabilities");
        if (invocation
                .required("output_schema_version")
                .equals(profile.required("assessment_output_schema_version"))) {
            throw new IllegalArgumentException(
                    "room_graph_command_binding_mismatch:outer_output_uses_assessment_pin");
        }

        JsonNode snapshot = verifiedCommand.required("domain_snapshot_ref");
        requireBinding(
                snapshot.required("artifact_id"), manifest.required("manifest_id"), "manifest_id");
        requireText(snapshot, "schema_version", "evidence-batch-manifest.v1");
        String payloadHash = ContractJson.sha256Hex(manifest);
        requireText(snapshot, "sha256", payloadHash);
        if (snapshot.required("size_bytes").longValue()
                != ContractJson.canonicalize(manifest).length) {
            throw new IllegalArgumentException(
                    "room_graph_command_binding_mismatch:domain_snapshot_ref.size_bytes");
        }
        if (!snapshot.required("uri").asText().endsWith("/" + payloadHash + ".json")) {
            throw new IllegalArgumentException(
                    "room_graph_command_binding_mismatch:domain_snapshot_ref.uri");
        }
        if (payloadHash.equals(manifest.required("manifest_hash").asText())) {
            throw new IllegalArgumentException("manifest_payload_hash_must_differ_from_self_hash");
        }
        for (String nonCommandField :
                List.of(
                        "fencing_token",
                        "graph_lease_fencing_token",
                        "registration_id",
                        "participant_id",
                        "agent_session_id")) {
            if (verifiedCommand.has(nonCommandField)) {
                throw new IllegalArgumentException(
                        "room_graph_command_binding_mismatch:" + nonCommandField);
            }
        }
    }

    private static void requireBinding(JsonNode actual, JsonNode expected, String reason) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("room_graph_command_binding_mismatch:" + reason);
        }
    }

    private static void requireText(JsonNode actual, String field, String expected) {
        if (!expected.equals(actual.path(field).asText())) {
            throw new IllegalArgumentException("room_graph_command_binding_mismatch:" + field);
        }
    }

    private static void assertCurrentRoomFence(JsonNode manifest, long currentRoomFence) {
        if (manifest.required("fencing_token").longValue() != currentRoomFence) {
            throw new IllegalArgumentException("room_fence_mismatch");
        }
    }

    private static void assertCurrentGraphLeaseFence(
            long expectedGraphLeaseFence, long currentGraphLeaseFence) {
        if (expectedGraphLeaseFence != currentGraphLeaseFence) {
            throw new IllegalArgumentException("graph_lease_fence_mismatch");
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
                    .filter(
                            path ->
                                    PRODUCT_CONTRACT_FILES.contains(
                                            path.getFileName().toString()))
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

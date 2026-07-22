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

        assertThat(schema.validator().validate(fixture)).as("schema errors for %s", path).isEmpty();
        assertSelfHash(fixture, schema.document(), path);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFixtures")
    void invalidFixtureIsRejectedByDraft202012(Path path) throws IOException {
        JsonNode fixture = MAPPER.readTree(path.toFile());
        ContractSchema schema = schemaFor(fixture, path);
        Set<ValidationMessage> errors = schema.validator().validate(fixture);

        assertThat(errors).as("invalid fixture must fail closed: %s", path).isNotEmpty();
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
}

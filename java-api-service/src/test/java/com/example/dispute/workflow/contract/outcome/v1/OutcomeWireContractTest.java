package com.example.dispute.workflow.contract.outcome.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

class OutcomeWireContractTest {

    private static final Path CONTRACT_ROOT =
            Path.of("..", "contracts", "agent-platform", "outcome", "v1").normalize();
    private static final Path FIXTURES = CONTRACT_ROOT.resolve("fixtures");
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private static final Map<String, ContractCase> VALID_CASES = Map.ofEntries(
            entry("outcome-workflow-start-valid.json", "outcome-workflow-start.schema.json", OutcomeWorkflowStart.class),
            entry("outcome-reviewer-decision-receipt-valid.json", "outcome-reviewer-decision-receipt.schema.json", OutcomeReviewDecisionReceipt.class),
            entry("outcome-sla-escalation-receipt-valid.json", "outcome-sla-escalation-receipt.schema.json", OutcomeSlaEscalationReceipt.class),
            entry("outcome-operation-command-valid.json", "outcome-operation-command.schema.json", OutcomeOperationCommand.class),
            entry("outcome-operation-receipt-valid.json", "outcome-operation-receipt.schema.json", OutcomeOperationReceipt.class),
            entry("outcome-execution-attempt-observation-valid.json", "outcome-execution-attempt-observation.schema.json", OutcomeExecutionAttemptObservation.class),
            entry("outcome-attempt-reconciliation-receipt-valid.json", "outcome-attempt-reconciliation-receipt.schema.json", OutcomeAttemptReconciliationReceipt.class),
            entry("outcome-compensation-receipt-valid.json", "outcome-compensation-receipt.schema.json", OutcomeCompensationReceipt.class),
            entry("outcome-closure-receipt-valid.json", "outcome-closure-receipt.schema.json", OutcomeClosureReceipt.class),
            entry("outcome-evaluation-receipt-valid.json", "outcome-evaluation-receipt.schema.json", OutcomeEvaluationReceipt.class),
            entry("outcome-process-projection-valid.json", "outcome-process-projection.schema.json", OutcomeProjection.class),
            entry("outcome-process-projection-closed-valid.json", "outcome-process-projection.schema.json", OutcomeProjection.class),
            entry("outcome-process-projection-evaluated-valid.json", "outcome-process-projection.schema.json", OutcomeProjection.class),
            entry("outcome-synthetic-noop-receipt-valid.json", "outcome-synthetic-noop-receipt.schema.json", OutcomeSyntheticNoopReceipt.class));

    private static final Map<String, String> INVALID_CASES = Map.ofEntries(
            Map.entry("outcome-workflow-start-packet-body.json", "outcome-workflow-start.schema.json"),
            Map.entry("outcome-reviewer-decision-unknown.json", "outcome-reviewer-decision-receipt.schema.json"),
            Map.entry("outcome-sla-escalation-human-decision.json", "outcome-sla-escalation-receipt.schema.json"),
            Map.entry("outcome-operation-command-prompt.json", "outcome-operation-command.schema.json"),
            Map.entry("outcome-operation-receipt-ambiguous.json", "outcome-operation-receipt.schema.json"),
            Map.entry("outcome-execution-attempt-observation-unblocked.json", "outcome-execution-attempt-observation.schema.json"),
            Map.entry("outcome-attempt-reconciliation-blind-retry.json", "outcome-attempt-reconciliation-receipt.schema.json"),
            Map.entry("outcome-compensation-credential.json", "outcome-compensation-receipt.schema.json"),
            Map.entry("outcome-closure-ambiguous.json", "outcome-closure-receipt.schema.json"),
            Map.entry("outcome-evaluation-reopens-case.json", "outcome-evaluation-receipt.schema.json"),
            Map.entry("outcome-process-projection-external-payload.json", "outcome-process-projection.schema.json"),
            Map.entry("outcome-process-projection-closed-missing-closure.json", "outcome-process-projection.schema.json"),
            Map.entry("outcome-process-projection-closed-success-count-mismatch.json", "outcome-process-projection.schema.json"),
            Map.entry("outcome-process-projection-synthetic-evaluated.json", "outcome-process-projection.schema.json"),
            Map.entry("outcome-synthetic-noop-receipt-real-effect.json", "outcome-synthetic-noop-receipt.schema.json"),
            Map.entry("outcome-synthetic-noop-receipt-raw-url.json", "outcome-synthetic-noop-receipt.schema.json"));

    private static OutcomeContractCodec codec;

    @BeforeAll
    static void createCodec() {
        codec = new OutcomeContractCodec(CONTRACT_ROOT);
    }

    static Stream<String> validFixtureNames() {
        return VALID_CASES.keySet().stream().sorted();
    }

    static Stream<String> invalidFixtureNames() {
        return INVALID_CASES.keySet().stream().sorted();
    }

    @ParameterizedTest
    @MethodSource("validFixtureNames")
    void validFixturesRoundTripThroughSchemaAndClosedJavaRecord(String fixtureName) throws IOException {
        ContractCase contract = VALID_CASES.get(fixtureName);
        JsonNode fixture = MAPPER.readTree(FIXTURES.resolve("valid").resolve(fixtureName).toFile());

        Object decoded = codec.decode(contract.schemaFile(), fixture, contract.javaType());

        assertThat(codec.encode(contract.schemaFile(), decoded).toString())
                .isEqualTo(fixture.toString());
    }

    @ParameterizedTest
    @MethodSource("invalidFixtureNames")
    void invalidFixturesFailClosed(String fixtureName) throws IOException {
        String schemaFile = INVALID_CASES.get(fixtureName);
        JsonNode fixture = MAPPER.readTree(FIXTURES.resolve("invalid").resolve(fixtureName).toFile());

        assertThatThrownBy(() -> codec.validate(schemaFile, fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is invalid");
    }

    @Test
    void contractPackAndFixtureDirectoriesAreClosed() throws IOException {
        TreeSet<String> schemaFiles;
        try (Stream<Path> paths = Files.list(CONTRACT_ROOT)) {
            schemaFiles = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".schema.json"))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
        assertThat(schemaFiles).containsExactlyElementsOf(new TreeSet<>(codec.schemaFiles()));
        assertThat(fileNames(FIXTURES.resolve("valid"))).containsAll(VALID_CASES.keySet());
        assertThat(fileNames(FIXTURES.resolve("invalid"))).containsExactlyElementsOf(new TreeSet<>(INVALID_CASES.keySet()));

        for (String schemaFile : schemaFiles) {
            JsonNode schema = MAPPER.readTree(CONTRACT_ROOT.resolve(schemaFile).toFile());
            assertThat(schema.required("additionalProperties").booleanValue()).isFalse();
            assertThat(schema.required("x-max-encoded-bytes").intValue())
                    .isEqualTo(OutcomeContractCodec.MAX_ENCODED_BYTES);
        }
    }

    @Test
    void allFiveAndOnlyFiveReviewerDecisionsAreFrozen() {
        assertThat(OutcomeWireTypes.ReviewDecision.values())
                .containsExactly(
                        OutcomeWireTypes.ReviewDecision.APPROVE,
                        OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE,
                        OutcomeWireTypes.ReviewDecision.REJECT,
                        OutcomeWireTypes.ReviewDecision.REQUEST_MORE_EVIDENCE,
                        OutcomeWireTypes.ReviewDecision.ESCALATE_MANUAL);
        assertThat(OutcomeWireTypes.SlaFactType.values())
                .containsExactly(OutcomeWireTypes.SlaFactType.SYSTEM_SLA_ESCALATION);
    }

    @Test
    void unknownVersionModeDecisionAndFieldAreRejected() throws IOException {
        assertRejectedMutation("outcome-workflow-start-valid.json", "outcome-workflow-start.schema.json", value -> value.put("schema_version", "outcome-workflow-start.v2"));
        assertRejectedMutation("outcome-workflow-start-valid.json", "outcome-workflow-start.schema.json", value -> value.put("runtime_mode", "REAL_CASE_SHADOW"));
        assertRejectedMutation("outcome-reviewer-decision-receipt-valid.json", "outcome-reviewer-decision-receipt.schema.json", value -> value.put("decision", "AUTO_APPROVE"));
        assertRejectedMutation("outcome-operation-command-valid.json", "outcome-operation-command.schema.json", value -> value.put("credentials", "secret"));
    }

    @Test
    void ambiguousIsObservationOnlyAndNeverAnAuthoritativeTerminalStatus() {
        assertThat(OutcomeWireTypes.TerminalStatus.values())
                .containsExactly(OutcomeWireTypes.TerminalStatus.SUCCEEDED, OutcomeWireTypes.TerminalStatus.FAILED);
        assertThat(OutcomeWireTypes.AttemptObservationStatus.values())
                .containsExactly(OutcomeWireTypes.AttemptObservationStatus.AMBIGUOUS);
        assertThat(OutcomeWireTypes.OperationStatus.RECONCILING).isNotNull();
    }

    @Test
    void terminalProjectionRequiresAuthoritativeClosureCompleteReceiptsAndNonSyntheticMode()
            throws IOException {
        codec.validate(
                "outcome-process-projection.schema.json",
                MAPPER.readTree(FIXTURES.resolve("valid").resolve(
                        "outcome-process-projection-closed-valid.json").toFile()));
        codec.validate(
                "outcome-process-projection.schema.json",
                MAPPER.readTree(FIXTURES.resolve("valid").resolve(
                        "outcome-process-projection-evaluated-valid.json").toFile()));

        for (String fixture : List.of(
                "outcome-process-projection-closed-missing-closure.json",
                "outcome-process-projection-closed-success-count-mismatch.json",
                "outcome-process-projection-synthetic-evaluated.json")) {
            JsonNode invalid = MAPPER.readTree(FIXTURES.resolve("invalid").resolve(fixture).toFile());
            assertThatThrownBy(() -> codec.validate("outcome-process-projection.schema.json", invalid))
                    .as(fixture)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void schemasExposeNoSensitiveOrEffectPayloadFields() throws IOException {
        Set<String> forbidden = Set.of(
                "packet_body", "prompt", "hidden_reasoning", "credential", "credentials",
                "token", "secret", "private_key", "tool_parameters", "external_payload",
                "raw_external_response", "url", "signed_url");
        for (String schemaFile : codec.schemaFiles()) {
            JsonNode properties = MAPPER.readTree(CONTRACT_ROOT.resolve(schemaFile).toFile()).required("properties");
            properties.fieldNames().forEachRemaining(name -> assertThat(forbidden).doesNotContain(name));
        }
    }

    private static void assertRejectedMutation(
            String fixtureName, String schemaFile, java.util.function.Consumer<ObjectNode> mutation)
            throws IOException {
        ObjectNode value = (ObjectNode) MAPPER.readTree(FIXTURES.resolve("valid").resolve(fixtureName).toFile());
        mutation.accept(value);
        assertThatThrownBy(() -> codec.validate(schemaFile, value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TreeSet<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    private static Map.Entry<String, ContractCase> entry(
            String fixtureName, String schemaFile, Class<?> javaType) {
        return Map.entry(fixtureName, new ContractCase(schemaFile, javaType));
    }

    private record ContractCase(String schemaFile, Class<?> javaType) {}
}

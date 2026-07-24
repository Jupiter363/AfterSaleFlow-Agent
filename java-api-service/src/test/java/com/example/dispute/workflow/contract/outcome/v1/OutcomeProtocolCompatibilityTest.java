package com.example.dispute.workflow.contract.outcome.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OutcomeProtocolCompatibilityTest {

    private static final Path CONTRACT_ROOT =
            Path.of("..", "contracts", "agent-platform", "outcome", "v1").normalize();
    private static final Path VALID = CONTRACT_ROOT.resolve("fixtures").resolve("valid");
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Test
    void compatibilityMatrixPublishesEverySchemaVersionAndJavaType() throws IOException {
        Map<String, Object> matrix = new Yaml().load(java.nio.file.Files.readString(CONTRACT_ROOT.resolve("compatibility-matrix.yaml")));
        Map<String, Object> wire = map(matrix.get("wire_protocol"));
        assertThat(wire.get("schema_version")).isEqualTo("outcome-wire-protocol.v1");
        assertThat(wire.get("maximum_encoded_bytes")).isEqualTo(32768);
        assertThat(wire.get("unknown_fields")).isEqualTo("reject");

        Map<String, String> published = new LinkedHashMap<>();
        for (Object raw : list(wire.get("contracts"))) {
            Map<String, Object> row = map(raw);
            String schemaFile = row.get("schema_file").toString();
            JsonNode schema = MAPPER.readTree(CONTRACT_ROOT.resolve(schemaFile).toFile());
            assertThat(schema.required("properties").required("schema_version").required("const").asText())
                    .isEqualTo(row.get("schema_version"));
            published.put(schemaFile, row.get("java_type").toString());
        }

        assertThat(published).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("outcome-workflow-start.schema.json", "OutcomeWorkflowStart"),
                Map.entry("outcome-reviewer-decision-receipt.schema.json", "OutcomeReviewDecisionReceipt"),
                Map.entry("outcome-sla-escalation-receipt.schema.json", "OutcomeSlaEscalationReceipt"),
                Map.entry("outcome-operation-command.schema.json", "OutcomeOperationCommand"),
                Map.entry("outcome-operation-receipt.schema.json", "OutcomeOperationReceipt"),
                Map.entry("outcome-execution-attempt-observation.schema.json", "OutcomeExecutionAttemptObservation"),
                Map.entry("outcome-attempt-reconciliation-receipt.schema.json", "OutcomeAttemptReconciliationReceipt"),
                Map.entry("outcome-compensation-receipt.schema.json", "OutcomeCompensationReceipt"),
                Map.entry("outcome-closure-receipt.schema.json", "OutcomeClosureReceipt"),
                Map.entry("outcome-evaluation-receipt.schema.json", "OutcomeEvaluationReceipt"),
                Map.entry("outcome-process-projection.schema.json", "OutcomeProjection"),
                Map.entry("outcome-synthetic-noop-receipt.schema.json", "OutcomeSyntheticNoopReceipt")));
    }

    @Test
    void workflowStartDecisionCommandAndProjectionKeepIdentityBindings() throws IOException {
        JsonNode start = read("outcome-workflow-start-valid.json");
        JsonNode decision = read("outcome-reviewer-decision-receipt-valid.json");
        JsonNode command = read("outcome-operation-command-valid.json");
        JsonNode projection = read("outcome-process-projection-valid.json");

        assertEqual(start, decision, "workflow_id", "case_id", "frozen_review_packet_ref", "frozen_review_packet_hash", "required_operation_set_ref", "required_operation_set_hash", "required_operation_count", "epoch", "fence");
        assertEqual(start, command, "workflow_id", "case_id", "epoch", "fence");
        assertEqual(decision, command, "workflow_id", "case_id", "operation_key_hash", "epoch", "fence");
        assertEqual(start, projection, "workflow_id", "case_id", "required_operation_set_ref", "required_operation_set_hash", "required_operation_count", "epoch", "fence");
        assertThat(command.required("approval_receipt_hash")).isEqualTo(decision.required("receipt_hash"));
        assertThat(command.required("approved_action_snapshot_ref")).isEqualTo(decision.required("approved_action_snapshot_ref"));
        assertThat(command.required("approved_action_snapshot_hash")).isEqualTo(decision.required("approved_action_snapshot_hash"));
    }

    @Test
    void publicProtocolNamesAreStableAndDoNotRegisterRuntime() {
        assertThat(OutcomeRoomProtocol.WORKFLOW_TYPE).isEqualTo("OutcomeRoomWorkflow");
        assertThat(OutcomeRoomProtocol.OPERATION_COMMAND_SIGNAL).isEqualTo("operationCommandCommitted");
        assertThat(OutcomeRoomProtocol.ATTEMPT_OBSERVATION_SIGNAL).isEqualTo("attemptObservationCommitted");
        assertThat(OutcomeRoomProtocol.PROJECTION_QUERY).isEqualTo("outcomeProjection");
        assertThat(OutcomeRoomProtocol.class.getDeclaredFields())
                .allMatch(field -> field.getType().equals(String.class));
    }

    @Test
    void legacyAuthoritativeTerminalProjectionsRetainClosureAndEvaluationBindings()
            throws IOException {
        JsonNode closed = read("outcome-process-projection-closed-valid.json");
        JsonNode evaluated = read("outcome-process-projection-evaluated-valid.json");

        for (JsonNode terminal : List.of(closed, evaluated)) {
            assertThat(terminal.required("writer_mode").asText()).isEqualTo("LEGACY");
            assertThat(terminal.required("runtime_mode").asText()).isEqualTo("DISABLED");
            assertThat(terminal.required("projection_only").booleanValue()).isFalse();
            assertThat(terminal.required("closure_receipt_ref").asText()).isNotBlank();
            assertThat(terminal.required("closure_receipt_hash").asText()).hasSize(64);
            assertThat(terminal.required("terminal_success_receipt_count").longValue())
                    .isEqualTo(terminal.required("required_operation_count").longValue());
        }
        assertThat(evaluated.required("evaluation_receipt_ref").asText()).isNotBlank();
        assertThat(evaluated.required("evaluation_receipt_hash").asText()).hasSize(64);
    }

    @Test
    void recordsCarryOnlyClosedScalarEnumTimestampAndReferenceMaterial() {
        for (Class<?> type : List.of(
                OutcomeWorkflowStart.class,
                OutcomeReviewDecisionReceipt.class,
                OutcomeSlaEscalationReceipt.class,
                OutcomeOperationCommand.class,
                OutcomeOperationReceipt.class,
                OutcomeExecutionAttemptObservation.class,
                OutcomeAttemptReconciliationReceipt.class,
                OutcomeCompensationReceipt.class,
                OutcomeClosureReceipt.class,
                OutcomeEvaluationReceipt.class,
                OutcomeProjection.class,
                OutcomeSyntheticNoopReceipt.class)) {
            assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
            assertThat(type.getRecordComponents())
                    .allMatch(component -> Set.of(String.class, long.class, boolean.class, java.time.Instant.class).contains(component.getType()) || component.getType().isEnum());
        }
    }

    private static JsonNode read(String fixture) throws IOException {
        return MAPPER.readTree(VALID.resolve(fixture).toFile());
    }

    private static void assertEqual(JsonNode left, JsonNode right, String... fields) {
        for (String field : fields) {
            assertThat(right.required(field)).as(field).isEqualTo(left.required(field));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}

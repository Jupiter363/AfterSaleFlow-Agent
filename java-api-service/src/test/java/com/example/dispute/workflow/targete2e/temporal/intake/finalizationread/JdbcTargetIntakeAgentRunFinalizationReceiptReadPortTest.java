package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReceiptReadPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

class JdbcTargetIntakeAgentRunFinalizationReceiptReadPortTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path COMMAND_FIXTURE = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-command-valid.json");

    private static final String TARGET_RECEIPT_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "target-e2e-finalization-receipt.v1",
                    "receipt_id", "target-receipt-1")));
    private static final String FORMAL_RECEIPT_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "intake-finalization-receipt.v1",
                    "operation_key", "intake-operation-1")));
    private static final String OUTER_PROPOSAL_DESCRIPTOR_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "target-e2e-proposal-descriptor.v1",
                    "proposal_uri", "minio://target/proposal.json")));
    private static final String FORMAL_PROPOSAL_PAYLOAD_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "intake-turn-proposal.v2",
                    "room_utterance", "Additional details are required.")));

    @Test
    void isAnExplicitFrameworkFreeReadPort() {
        assertThat(IntakeAgentRunFinalizationReceiptReadPort.class)
                .isAssignableFrom(JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.class);
    }

    @Test
    void rejectsMissingPersistenceDependenciesAtAssembly() {
        assertThatThrownBy(() -> new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort(
                (NamedParameterJdbcOperations) null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("jdbc");
    }

    @Test
    void acceptsDistinctCanonicalTargetCompletionAndFormalOperationHashes() {
        assertThat(TARGET_RECEIPT_HASH).isNotEqualTo(FORMAL_RECEIPT_HASH);
        assertThatCode(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalCompletionHash(TARGET_RECEIPT_HASH, TARGET_RECEIPT_HASH))
                .doesNotThrowAnyException();
        assertThatCode(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalFormalOperationHash(FORMAL_RECEIPT_HASH, FORMAL_RECEIPT_HASH))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsDistinctOuterDescriptorAndFormalPayloadProposalHashes() {
        assertThat(OUTER_PROPOSAL_DESCRIPTOR_HASH).isNotEqualTo(FORMAL_PROPOSAL_PAYLOAD_HASH);

        assertThatCode(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireFormalEventProposalHash(
                        FORMAL_PROPOSAL_PAYLOAD_HASH, FORMAL_PROPOSAL_PAYLOAD_HASH))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTamperedFormalEventProposalHash() {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireFormalEventProposalHash(FORMAL_PROPOSAL_PAYLOAD_HASH, "ab".repeat(32)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("formal Intake event conflicts with its receipt")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_EVENT_MISMATCH");
    }

    @Test
    void rejectsLegacyIdentityOnlyCompletionHash() {
        String identityOnlyHash = ContractJson.sha256Hex(JsonMapper.builder().build().valueToTree(Map.of(
                "activation_id", "activation-1",
                "command_id", "command-1",
                "command_hash", "11".repeat(32),
                "command_envelope_hash", "22".repeat(32))));

        assertThat(identityOnlyHash).isNotEqualTo(TARGET_RECEIPT_HASH);
        assertCompletionHashRejected(identityOnlyHash);
    }

    @Test
    void rejectsTamperedReceiptCompletionHash() {
        assertCompletionHashRejected("cd".repeat(32));
    }

    @Test
    void rejectsTargetReceiptHashUsedAsFormalOperationHash() {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalFormalOperationHash(TARGET_RECEIPT_HASH, FORMAL_RECEIPT_HASH))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("formal Intake operation hash is not canonical")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_OPERATION_HASH_MISMATCH");
    }

    @Test
    void rejectsTamperedFormalOperationHash() {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalFormalOperationHash("ef".repeat(32), FORMAL_RECEIPT_HASH))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("formal Intake operation hash is not canonical")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_OPERATION_HASH_MISMATCH");
    }

    @Test
    void acceptsJsonbStyleWinningAttemptTextWhenItsCanonicalHashMatches() throws Exception {
        ObjectNode commandJson = commandJson();
        String jsonbStyleText =
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(commandJson);

        RoomGraphCommand command =
                JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.decodeAttemptCommand(
                        MAPPER,
                        jsonbStyleText,
                        commandJson.required("request_hash").textValue());

        assertThat(command).isEqualTo(MAPPER.treeToValue(commandJson, RoomGraphCommand.class));
    }

    @Test
    void rejectsJsonbStyleWinningAttemptTextWhenItsBodyWasTampered() throws Exception {
        ObjectNode commandJson = commandJson();
        String requestHash = commandJson.required("request_hash").textValue();
        commandJson.put("stage_sequence", commandJson.required("stage_sequence").longValue() + 1);

        assertThatThrownBy(() ->
                        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.decodeAttemptCommand(
                                MAPPER,
                                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(commandJson),
                                requestHash))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("winning attempt command self-hash is invalid")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_ATTEMPT_COMMAND_INVALID");
    }

    @Test
    void rejectsWinningAttemptWhenItsPersistedRequestHashWasTampered() throws Exception {
        ObjectNode commandJson = commandJson();

        assertThatThrownBy(() ->
                        JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.decodeAttemptCommand(
                                MAPPER,
                                MAPPER.writeValueAsString(commandJson),
                                "f".repeat(64)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("winning attempt command self-hash is invalid")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_ATTEMPT_COMMAND_INVALID");
    }

    @Test
    void decodesCamelCaseIntakeMaterialWithAnInjectedSnakeCaseMapper() throws Exception {
        IntakeCommandExecutionContext expected = new IntakeCommandExecutionContext(
                "intake-command-execution-context.v1",
                "grt.v1." + "1".repeat(32),
                "agent-session-1",
                1_800_000_000_000L,
                new RetryBudget("intake-retry-budget.v1", 2, 3, 1),
                null,
                null);
        ObjectMapper camelCaseMapper = MAPPER.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        String canonicalMaterial =
                ContractJson.canonicalString(camelCaseMapper.valueToTree(expected));
        ObjectMapper injectedSnakeCaseMapper = MAPPER.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        IntakeCommandExecutionContext decoded =
                JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                        .targetMaterialObjectMapper(injectedSnakeCaseMapper)
                        .readValue(canonicalMaterial, IntakeCommandExecutionContext.class);

        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    void hashesTheFormalEventDocumentInsteadOfItsJsonStringEncoding() throws Exception {
        JsonNode event = MAPPER.readTree("""
                {
                  "schema_version": "intake-turn-committed-event.v1",
                  "event_type": "TURN_NEEDS_INPUT",
                  "receipt": {"domain_event_ids": ["event-1"]}
                }
                """);

        assertThat(JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.canonicalEventHash(event))
                .isEqualTo(ContractJson.sha256Hex(event));
    }

    private static ObjectNode commandJson() throws Exception {
        JsonNode wrapper = MAPPER.readTree(COMMAND_FIXTURE.toFile());
        return (ObjectNode) wrapper.required("instance").deepCopy();
    }

    private static void assertCompletionHashRejected(String completionHash) {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalCompletionHash(completionHash, TARGET_RECEIPT_HASH))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("target command completion hash is not canonical")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_COMPLETION_HASH_MISMATCH");
    }
}

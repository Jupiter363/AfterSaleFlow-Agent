package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReceiptReadPort;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

class JdbcTargetIntakeAgentRunFinalizationReceiptReadPortTest {

    private static final String TARGET_RECEIPT_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "target-e2e-finalization-receipt.v1",
                    "receipt_id", "target-receipt-1")));
    private static final String FORMAL_RECEIPT_HASH = ContractJson.sha256Hex(
            JsonMapper.builder().build().valueToTree(Map.of(
                    "schema_version", "intake-finalization-receipt.v1",
                    "operation_key", "intake-operation-1")));

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

    private static void assertCompletionHashRejected(String completionHash) {
        assertThatThrownBy(() -> JdbcTargetIntakeAgentRunFinalizationReceiptReadPort
                .requireCanonicalCompletionHash(completionHash, TARGET_RECEIPT_HASH))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessage("target command completion hash is not canonical")
                .extracting(failure -> ((TargetE2eFinalizationRejectedException) failure).code())
                .isEqualTo("TARGET_E2E_FINALIZATION_COMPLETION_HASH_MISMATCH");
    }
}

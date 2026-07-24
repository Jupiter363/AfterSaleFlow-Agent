package com.example.dispute.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.executor.application.SyntheticNoopExecutionAssembly;
import com.example.dispute.outcome.application.SyntheticOutcomeProjection;
import com.example.dispute.workflow.activity.tool.SyntheticNoopExecutionCommand;
import com.example.dispute.workflow.activity.tool.SyntheticNoopToolActivity;
import com.example.dispute.workflow.activity.tool.SyntheticNoopToolActivityImpl;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OutcomeSyntheticNoopAssemblyTest {

    @Test
    void assemblyPublishesExplicitSyntheticProjectionWithoutFormalReachability() {
        SyntheticNoopExecutionAssembly assembly =
                new SyntheticNoopExecutionAssembly(activity());

        SyntheticNoopExecutionAssembly.Result result = assembly.observe(command());

        SyntheticOutcomeProjection.Execution execution = result.projection().execution();
        assertThat(execution.mode()).isEqualTo("SIMULATED");
        assertThat(execution.status()).isEqualTo("OBSERVED_NO_EFFECT");
        assertThat(execution.actions()).isEmpty();
        assertThat(execution.receipts()).hasSize(1);
        assertThat(execution.syntheticOnly()).isTrue();
        assertThat(execution.formalReceiptPresent()).isFalse();
        assertThat(result.projection().closure().status())
                .isEqualTo("NOT_CLOSURE_ELIGIBLE");
        assertThat(result.projection().closure().closedAt()).isNull();
        assertThat(result.projection().projectionOnly()).isTrue();
    }

    @Test
    void implementationHasNoRegistryRepositoryNetworkOrEvaluationClientDependency() {
        assertNoForbiddenFieldType(SyntheticNoopToolActivityImpl.class);
        assertNoForbiddenFieldType(SyntheticNoopExecutionAssembly.class);
        assertThat(SyntheticNoopToolActivity.class.getAnnotations()).isEmpty();
    }

    private static void assertNoForbiddenFieldType(Class<?> type) {
        assertThat(
                        Arrays.stream(type.getDeclaredFields())
                                .map(Field::getType)
                                .map(Class::getName))
                .noneMatch(
                        name ->
                                name.contains("ToolRegistry")
                                        || name.contains("Repository")
                                        || name.contains("RestClient")
                                        || name.contains("EvaluationAgentClient")
                                        || name.startsWith("java.net"));
    }

    private static SyntheticNoopToolActivity activity() {
        return new SyntheticNoopToolActivityImpl(
                ignored -> true,
                new SyntheticNoopToolActivity.ReceiptSigner() {
                    @Override
                    public String signingKeyId() {
                        return "outcome-synthetic-receipt-key-1";
                    }

                    @Override
                    public String sign(String lowercaseReceiptHash) {
                        return "B".repeat(86);
                    }
                });
    }

    private static SyntheticNoopExecutionCommand command() {
        return new SyntheticNoopExecutionCommand(
                SyntheticNoopExecutionCommand.SCHEMA_VERSION,
                SyntheticNoopExecutionCommand.MARKER,
                SyntheticNoopExecutionCommand.RUNTIME_MODE,
                SyntheticNoopExecutionCommand.TRAFFIC_SOURCE,
                "OUTCOME_SYNTHETIC_ASSEMBLY",
                "outcome-synthetic/assembly",
                "operation.assembly",
                "synthetic/packet/assembly",
                "a".repeat(64),
                "b".repeat(64),
                3,
                5,
                7,
                false,
                Instant.parse("2026-07-24T04:00:00Z"),
                SyntheticNoopExecutionCommand.SIGNER,
                SyntheticNoopExecutionCommand.SIGNATURE_ALGORITHM,
                "outcome-synthetic-input-key-1",
                "A".repeat(86));
    }
}

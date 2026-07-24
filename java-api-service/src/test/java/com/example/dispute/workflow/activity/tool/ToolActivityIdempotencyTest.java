package com.example.dispute.workflow.activity.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolActivityIdempotencyTest {

    @Test
    void repeatedSignedFixtureProducesTheSameZeroEffectReceipt() {
        AtomicInteger verifications = new AtomicInteger();
        SyntheticNoopToolActivity activity =
                SyntheticNoopFixtures.activity(
                        command -> {
                            verifications.incrementAndGet();
                            return true;
                        });

        SyntheticNoopExecutionReceipt first =
                activity.execute(SyntheticNoopFixtures.command());
        SyntheticNoopExecutionReceipt second =
                activity.execute(SyntheticNoopFixtures.command());

        assertThat(first).isEqualTo(second);
        assertThat(verifications).hasValue(2);
        assertThat(first.syntheticOnly()).isTrue();
        assertThat(first.containsRealCaseOrPartyData()).isFalse();
        assertThat(first.toolInvoked()).isFalse();
        assertThat(first.externalEffectCreated()).isFalse();
        assertThat(first.formalBusinessWriteCreated()).isFalse();
        assertThat(first.projectionOnly()).isTrue();
        assertThat(first.effectMode()).isEqualTo("NOOP");
        assertThat(first.signer()).isEqualTo(SyntheticNoopExecutionCommand.SIGNER);
        assertThat(first.externalAdapter()).isEqualTo("SYNTHETIC_NOOP_ONLY");
        assertThat(first.externalEffectPerformed()).isFalse();
        assertThat(first.formalFactWritten()).isFalse();
        assertThat(first.closureRelevant()).isFalse();
    }

    @Test
    void rejectsUnknownVersionMissingFenceRealReferencesAndInvalidSignature() {
        assertThatThrownBy(
                        () ->
                                SyntheticNoopFixtures.command(
                                        "outcome-synthetic-noop-command.v2",
                                        "synthetic/packet/P7E1",
                                        7,
                                        false,
                                        SyntheticNoopFixtures.SIGNATURE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(
                        () ->
                                SyntheticNoopFixtures.command(
                                        SyntheticNoopExecutionCommand.SCHEMA_VERSION,
                                        "synthetic/packet/P7E1",
                                        0,
                                        false,
                                        SyntheticNoopFixtures.SIGNATURE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fence");
        assertThatThrownBy(
                        () ->
                                SyntheticNoopFixtures.command(
                                        SyntheticNoopExecutionCommand.SCHEMA_VERSION,
                                        "case/CASE_REAL_1",
                                        7,
                                        false,
                                        SyntheticNoopFixtures.SIGNATURE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("synthetic reference");
        assertThatThrownBy(
                        () ->
                                SyntheticNoopFixtures.command(
                                        SyntheticNoopExecutionCommand.SCHEMA_VERSION,
                                        "synthetic/packet/P7E1",
                                        7,
                                        true,
                                        SyntheticNoopFixtures.SIGNATURE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("real case or party");
        assertThatThrownBy(
                        () ->
                                SyntheticNoopFixtures.command(
                                        SyntheticNoopExecutionCommand.SCHEMA_VERSION,
                                        "synthetic/packet/P7E1",
                                        7,
                                        false,
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void failedJavaSignatureIsNonRetryableAndClosureBlocking() {
        SyntheticNoopToolActivity activity = SyntheticNoopFixtures.activity(command -> false);

        assertThatThrownBy(() -> activity.execute(SyntheticNoopFixtures.command()))
                .isInstanceOfSatisfying(
                        SyntheticNoopToolActivity.ExecutionException.class,
                        error -> {
                            assertThat(error.failureClass())
                                    .isEqualTo(
                                            SyntheticNoopToolActivity.FailureClass.CONTRACT_INVALID);
                            assertThat(error.failureClass().retryAllowed()).isFalse();
                            assertThat(error.failureClass().closureBlocking()).isTrue();
                        });
    }
}

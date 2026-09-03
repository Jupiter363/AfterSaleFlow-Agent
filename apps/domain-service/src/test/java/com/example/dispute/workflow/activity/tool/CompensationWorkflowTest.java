package com.example.dispute.workflow.activity.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompensationWorkflowTest {

    @Test
    void noOpCompensationObservationIsParentBoundAndEffectFree() {
        SyntheticNoopExecutionReceipt receipt =
                SyntheticNoopFixtures.activity().execute(SyntheticNoopFixtures.command());

        SyntheticNoopCompensationObservation observation =
                SyntheticNoopCompensationObservation.from(receipt);

        assertThat(observation.parentOperationId()).isEqualTo(receipt.operationId());
        assertThat(observation.parentReceiptHash()).isEqualTo(receipt.receiptHash());
        assertThat(observation.status()).isEqualTo("NOT_REQUIRED_NO_EFFECT");
        assertThat(observation.syntheticOnly()).isTrue();
        assertThat(observation.toolInvoked()).isFalse();
        assertThat(observation.externalEffectCreated()).isFalse();
        assertThat(observation.formalBusinessWriteCreated()).isFalse();
        assertThat(observation.projectionOnly()).isTrue();
    }

    @Test
    void ambiguousClassificationForbidsBlindRetryAndBlocksClosure() {
        SyntheticNoopToolActivity.FailureClass ambiguous =
                SyntheticNoopToolActivity.FailureClass.TOOL_AMBIGUOUS;

        assertThat(ambiguous.retryAllowed()).isFalse();
        assertThat(ambiguous.closureBlocking()).isTrue();
        assertThat(SyntheticNoopToolActivity.FailureClass.TOOL_TRANSIENT_SAFE.retryAllowed())
                .isTrue();
    }
}

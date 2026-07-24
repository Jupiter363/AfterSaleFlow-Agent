package com.example.dispute.workflow.activity.tool;

import java.time.Instant;

final class SyntheticNoopFixtures {

    static final String PACKET_HASH = "a".repeat(64);
    static final String REQUEST_HASH = "b".repeat(64);
    static final String SIGNATURE = "A".repeat(86);

    private SyntheticNoopFixtures() {}

    static SyntheticNoopExecutionCommand command() {
        return command(
                SyntheticNoopExecutionCommand.SCHEMA_VERSION,
                "synthetic/packet/P7E1",
                7,
                false,
                SIGNATURE);
    }

    static SyntheticNoopExecutionCommand command(
            String schemaVersion,
            String packetRef,
            long fence,
            boolean containsRealData,
            String signature) {
        return new SyntheticNoopExecutionCommand(
                schemaVersion,
                SyntheticNoopExecutionCommand.MARKER,
                SyntheticNoopExecutionCommand.RUNTIME_MODE,
                SyntheticNoopExecutionCommand.TRAFFIC_SOURCE,
                "OUTCOME_SYNTHETIC_P7E1",
                "outcome-synthetic/P7E1",
                "operation.P7E1",
                packetRef,
                PACKET_HASH,
                REQUEST_HASH,
                3,
                5,
                fence,
                containsRealData,
                Instant.parse("2026-07-24T04:00:00Z"),
                SyntheticNoopExecutionCommand.SIGNER,
                SyntheticNoopExecutionCommand.SIGNATURE_ALGORITHM,
                "outcome-synthetic-input-key-1",
                signature);
    }

    static SyntheticNoopToolActivity activity() {
        return activity(command -> true);
    }

    static SyntheticNoopToolActivity activity(
            SyntheticNoopToolActivity.SignatureVerifier verifier) {
        return new SyntheticNoopToolActivityImpl(
                verifier,
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
}

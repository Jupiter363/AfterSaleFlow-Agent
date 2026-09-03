package com.example.dispute.workflow.activity.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeExecutionAttemptObservation;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeOperationCommand;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeSyntheticNoopReceipt;

/** Pure adapter: validates a Java signature and returns a signed, deterministic zero-effect receipt. */
public final class SyntheticNoopToolActivityImpl implements SyntheticNoopToolActivity {

    private final SignatureVerifier signatureVerifier;
    private final ReceiptSigner receiptSigner;

    public SyntheticNoopToolActivityImpl(
            SignatureVerifier signatureVerifier, ReceiptSigner receiptSigner) {
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier);
        this.receiptSigner = Objects.requireNonNull(receiptSigner);
    }

    @Override
    public SyntheticNoopExecutionReceipt execute(SyntheticNoopExecutionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        requireVerifiedSignature(command);
        return executeVerified(command);
    }

    private SyntheticNoopExecutionReceipt executeVerified(
            SyntheticNoopExecutionCommand command) {
        String signingKeyId = receiptSigner.signingKeyId();
        if (signingKeyId == null || !signingKeyId.startsWith("outcome-synthetic-")) {
            throw new ExecutionException(
                    FailureClass.CONTRACT_INVALID,
                    "receipt signer must use a synthetic-only key identifier");
        }
        String receiptHash = sha256(canonicalPreimage(command, signingKeyId));
        String signature = receiptSigner.sign(receiptHash);
        return new SyntheticNoopExecutionReceipt(
                SyntheticNoopExecutionReceipt.SCHEMA_VERSION,
                SyntheticNoopExecutionCommand.MARKER,
                SyntheticNoopExecutionCommand.RUNTIME_MODE,
                SyntheticNoopExecutionCommand.TRAFFIC_SOURCE,
                SyntheticNoopExecutionReceipt.OUTPUT_SINK,
                command.fixtureId(),
                command.workflowId(),
                command.operationId(),
                command.packetRef(),
                command.packetHash(),
                command.requestHash(),
                command.epoch(),
                command.revision(),
                command.fence(),
                true,
                false,
                false,
                false,
                false,
                true,
                command.issuedAt(),
                SyntheticNoopExecutionCommand.SIGNER,
                SyntheticNoopExecutionCommand.SIGNATURE_ALGORITHM,
                signingKeyId,
                receiptHash,
                signature);
    }

    public VerifiedExecution verifyAndExecute(
            OutcomeOperationCommand command, SyntheticNoopExecutionCommand signedFixture) {
        VerifiedInvocation invocation = verifyInvocation(command, signedFixture);
        SyntheticNoopExecutionReceipt internalReceipt =
                executeVerified(invocation.signedFixture());
        OutcomeSyntheticNoopReceipt wireReceipt =
                SyntheticOutcomeProtocolAdapter.toWire(invocation.command(), internalReceipt);
        return new VerifiedExecution(
                invocation,
                internalReceipt,
                wireReceipt,
                sha256(
                        invocation.capabilityHash()
                                + "\n"
                                + internalReceipt.receiptHash()));
    }

    public VerifiedAmbiguousAttempt verifyAmbiguousAttempt(
            OutcomeOperationCommand command,
            SyntheticNoopExecutionCommand signedFixture,
            OutcomeExecutionAttemptObservation observation) {
        VerifiedInvocation invocation = verifyInvocation(command, signedFixture);
        SyntheticOutcomeProtocolAdapter.requireAmbiguousMatch(invocation.command(), observation);
        return new VerifiedAmbiguousAttempt(
                invocation,
                observation,
                sha256(
                        invocation.capabilityHash()
                                + "\n"
                                + observation.observationHash()));
    }

    private VerifiedInvocation verifyInvocation(
            OutcomeOperationCommand command, SyntheticNoopExecutionCommand signedFixture) {
        SyntheticNoopExecutionCommand bound =
                SyntheticOutcomeProtocolAdapter.bind(command, signedFixture);
        requireVerifiedSignature(bound);
        return new VerifiedInvocation(
                command,
                bound,
                sha256(
                        command.commandId()
                                + "\n"
                                + command.requestHash()
                                + "\n"
                                + command.epoch()
                                + "\n"
                                + command.revision()
                                + "\n"
                                + command.fence()
                                + "\n"
                                + bound.signature()));
    }

    private void requireVerifiedSignature(SyntheticNoopExecutionCommand command) {
        if (!signatureVerifier.verify(command)) {
            throw new ExecutionException(
                    FailureClass.CONTRACT_INVALID,
                    "synthetic fixture signature verification failed");
        }
    }

    private static String canonicalPreimage(
            SyntheticNoopExecutionCommand command, String signingKeyId) {
        // Input alphabets exclude JSON escaping characters, so this fixed lexical order is RFC 8785 canonical.
        return "{"
                + "\"contains_real_case_or_party_data\":false,"
                + "\"epoch\":" + command.epoch() + ","
                + "\"external_effect_created\":false,"
                + "\"fence\":" + command.fence() + ","
                + "\"fixture_id\":\"" + command.fixtureId() + "\","
                + "\"formal_business_write_created\":false,"
                + "\"issued_at\":\"" + command.issuedAt() + "\","
                + "\"marker\":\"" + SyntheticNoopExecutionCommand.MARKER + "\","
                + "\"operation_id\":\"" + command.operationId() + "\","
                + "\"output_sink\":\"" + SyntheticNoopExecutionReceipt.OUTPUT_SINK + "\","
                + "\"packet_hash\":\"" + command.packetHash() + "\","
                + "\"packet_ref\":\"" + command.packetRef() + "\","
                + "\"projection_only\":true,"
                + "\"request_hash\":\"" + command.requestHash() + "\","
                + "\"revision\":" + command.revision() + ","
                + "\"runtime_mode\":\"" + SyntheticNoopExecutionCommand.RUNTIME_MODE + "\","
                + "\"schema_version\":\"" + SyntheticNoopExecutionReceipt.SCHEMA_VERSION + "\","
                + "\"signature_algorithm\":\"" + SyntheticNoopExecutionCommand.SIGNATURE_ALGORITHM + "\","
                + "\"signer\":\"" + SyntheticNoopExecutionCommand.SIGNER + "\","
                + "\"signing_key_id\":\"" + signingKeyId + "\","
                + "\"synthetic_only\":true,"
                + "\"tool_invoked\":false,"
                + "\"traffic_source\":\"" + SyntheticNoopExecutionCommand.TRAFFIC_SOURCE + "\","
                + "\"workflow_id\":\"" + command.workflowId() + "\""
                + "}";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public static final class VerifiedExecution {
        private final VerifiedInvocation invocation;
        private final SyntheticNoopExecutionReceipt internalReceipt;
        private final OutcomeSyntheticNoopReceipt wireReceipt;
        private final String capabilityHash;

        private VerifiedExecution(
                VerifiedInvocation invocation,
                SyntheticNoopExecutionReceipt internalReceipt,
                OutcomeSyntheticNoopReceipt wireReceipt,
                String capabilityHash) {
            this.invocation = invocation;
            this.internalReceipt = internalReceipt;
            this.wireReceipt = wireReceipt;
            this.capabilityHash = capabilityHash;
        }

        public OutcomeOperationCommand command() {
            return invocation.command();
        }

        public SyntheticNoopExecutionCommand signedFixture() {
            return invocation.signedFixture();
        }

        public SyntheticNoopExecutionReceipt internalReceipt() {
            return internalReceipt;
        }

        public OutcomeSyntheticNoopReceipt wireReceipt() {
            return wireReceipt;
        }

        public String capabilityHash() {
            return capabilityHash;
        }
    }

    public static final class VerifiedAmbiguousAttempt {
        private final VerifiedInvocation invocation;
        private final OutcomeExecutionAttemptObservation observation;
        private final String capabilityHash;

        private VerifiedAmbiguousAttempt(
                VerifiedInvocation invocation,
                OutcomeExecutionAttemptObservation observation,
                String capabilityHash) {
            this.invocation = invocation;
            this.observation = observation;
            this.capabilityHash = capabilityHash;
        }

        public OutcomeOperationCommand command() {
            return invocation.command();
        }

        public SyntheticNoopExecutionCommand signedFixture() {
            return invocation.signedFixture();
        }

        public OutcomeExecutionAttemptObservation observation() {
            return observation;
        }

        public String capabilityHash() {
            return capabilityHash;
        }
    }

    private record VerifiedInvocation(
            OutcomeOperationCommand command,
            SyntheticNoopExecutionCommand signedFixture,
            String capabilityHash) {}
}

package com.example.dispute.workflow.activity.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

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
        if (!signatureVerifier.verify(command)) {
            throw new ExecutionException(
                    FailureClass.CONTRACT_INVALID,
                    "synthetic fixture signature verification failed");
        }
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
}

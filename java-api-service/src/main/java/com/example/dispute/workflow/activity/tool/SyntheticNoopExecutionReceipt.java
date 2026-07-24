package com.example.dispute.workflow.activity.tool;

import java.time.Instant;
import java.util.Objects;

/** Signed zero-effect observation. It is never an operation, closure, or business receipt. */
public record SyntheticNoopExecutionReceipt(
        String schemaVersion,
        String marker,
        String runtimeMode,
        String trafficSource,
        String outputSink,
        String fixtureId,
        String workflowId,
        String operationId,
        String packetRef,
        String packetHash,
        String requestHash,
        long epoch,
        long revision,
        long fence,
        boolean syntheticOnly,
        boolean containsRealCaseOrPartyData,
        boolean toolInvoked,
        boolean externalEffectCreated,
        boolean formalBusinessWriteCreated,
        boolean projectionOnly,
        Instant issuedAt,
        String signer,
        String signatureAlgorithm,
        String signingKeyId,
        String receiptHash,
        String signature) {

    public static final String SCHEMA_VERSION = "outcome-synthetic-noop-receipt.v1";
    public static final String OUTPUT_SINK = "ISOLATED_COMPARISON_LEDGER";

    public SyntheticNoopExecutionReceipt {
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !SyntheticNoopExecutionCommand.MARKER.equals(marker)
                || !SyntheticNoopExecutionCommand.RUNTIME_MODE.equals(runtimeMode)
                || !SyntheticNoopExecutionCommand.TRAFFIC_SOURCE.equals(trafficSource)
                || !OUTPUT_SINK.equals(outputSink)) {
            throw new IllegalArgumentException("synthetic receipt protocol markers are invalid");
        }
        if (!syntheticOnly
                || containsRealCaseOrPartyData
                || toolInvoked
                || externalEffectCreated
                || formalBusinessWriteCreated
                || !projectionOnly) {
            throw new IllegalArgumentException("synthetic receipt must prove zero effect");
        }
        if (fixtureId == null
                || !fixtureId.matches("OUTCOME_SYNTHETIC_[A-Z0-9._:-]{1,110}")
                || workflowId == null
                || workflowId.length() > 128
                || !workflowId.matches(
                        "outcome-synthetic/[A-Za-z0-9._:-]+(?:/[A-Za-z0-9._:-]+)*")
                || operationId == null
                || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || packetRef == null
                || !packetRef.startsWith("synthetic/")
                || packetRef.contains("://")
                || packetRef.length() > 256
                || packetHash == null
                || !packetHash.matches("[0-9a-f]{64}")
                || requestHash == null
                || !requestHash.matches("[0-9a-f]{64}")
                || epoch < 1
                || revision < 0
                || fence < 1) {
            throw new IllegalArgumentException("synthetic receipt binding is invalid");
        }
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        if (!SyntheticNoopExecutionCommand.SIGNER.equals(signer)
                || !SyntheticNoopExecutionCommand.SIGNATURE_ALGORITHM.equals(signatureAlgorithm)
                || signingKeyId == null
                || !signingKeyId.startsWith("outcome-synthetic-")
                || receiptHash == null
                || !receiptHash.matches("[0-9a-f]{64}")
                || signature == null
                || !signature.matches("[A-Za-z0-9_-]{86}")) {
            throw new IllegalArgumentException("synthetic receipt signature is invalid");
        }
    }

    public String effectMode() {
        return "NOOP";
    }

    public String externalAdapter() {
        return "SYNTHETIC_NOOP_ONLY";
    }

    public boolean externalEffectPerformed() {
        return false;
    }

    public boolean formalFactWritten() {
        return false;
    }

    public boolean closureRelevant() {
        return false;
    }
}

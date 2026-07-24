package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeSyntheticNoopReceipt(
        String schemaVersion,
        OutcomeWireTypes.SyntheticNoopMarker marker,
        OutcomeWireTypes.RuntimeMode runtimeMode,
        TrafficSource trafficSource,
        OutputSink outputSink,
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
        Signer signer,
        SignatureAlgorithm signatureAlgorithm,
        String signingKeyId,
        String receiptHash,
        String signature) {

    public static final String SCHEMA_VERSION = "outcome-synthetic-noop-receipt.v1";

    public enum TrafficSource { SIGNED_SYNTHETIC }
    public enum OutputSink { ISOLATED_COMPARISON_LEDGER }
    public enum Signer { JAVA_CONTROL_PLANE }
    public enum SignatureAlgorithm { ES256 }

    public OutcomeSyntheticNoopReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        if (marker != OutcomeWireTypes.SyntheticNoopMarker.JAVA_SIGNED_SYNTHETIC_NOOP_V1
                || runtimeMode != OutcomeWireTypes.RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW) {
            throw new IllegalArgumentException("synthetic no-op marker or mode is invalid");
        }
        required(trafficSource, "trafficSource");
        required(outputSink, "outputSink");
        identifier(fixtureId, "fixtureId");
        if (!fixtureId.startsWith("OUTCOME_SYNTHETIC_")) {
            throw new IllegalArgumentException("fixtureId must identify an Outcome synthetic fixture");
        }
        if (workflowId == null || workflowId.length() > 128 || !workflowId.startsWith("outcome-synthetic/")) {
            throw new IllegalArgumentException("workflowId must be a synthetic Outcome workflow id");
        }
        identifier(operationId, "operationId");
        opaqueRef(packetRef, "packetRef");
        sha256(packetHash, "packetHash");
        sha256(requestHash, "requestHash");
        coordinates(epoch, revision, fence);
        if (!syntheticOnly
                || containsRealCaseOrPartyData
                || toolInvoked
                || externalEffectCreated
                || formalBusinessWriteCreated
                || !projectionOnly) {
            throw new IllegalArgumentException("synthetic no-op effect markers are invalid");
        }
        instant(issuedAt, "issuedAt");
        required(signer, "signer");
        required(signatureAlgorithm, "signatureAlgorithm");
        identifier(signingKeyId, "signingKeyId");
        sha256(receiptHash, "receiptHash");
        if (signature == null || !signature.matches("[A-Za-z0-9_-]{86}")) {
            throw new IllegalArgumentException("signature must be an ES256 JOSE P1363 value");
        }
    }
}

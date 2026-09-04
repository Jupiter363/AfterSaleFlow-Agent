package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Exact immutable production-runtime-finalization-receipt.v1 contract. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionFinalizationReceipt(
        String schemaVersion,
        String executionLane,
        String activationId,
        String tenantSurrogate,
        String caseId,
        RoomType roomType,
        long roomEpoch,
        long roomFencingToken,
        long processRevision,
        long stageSequence,
        String logicalRunId,
        String attemptId,
        String commandHash,
        String commandEnvelopeHash,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String checkpointId,
        String resultHash,
        String proposalHash,
        String resultEnvelopeHash,
        String agentRunManifestId,
        String agentRunManifestHash,
        String isolatedDomainDbBindingHash,
        Instant committedAt,
        String receiptHash,
        FormalWriter formalWriter,
        DomainCommitStatus domainCommitStatus) {

    public static final String SCHEMA_VERSION = "production-runtime-finalization-receipt.v1";
    private static final String ZERO_HASH = "0".repeat(64);
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public ProductionFinalizationReceipt {
        exact(schemaVersion, SCHEMA_VERSION, "schemaVersion");
        exact(
                executionLane,
                ProductionExecutionLaneVerifier.EXECUTION_LANE,
                "executionLane");
        if (activationId == null || !activationId.matches("p9act[.]v1[.][0-9a-f]{32}")) {
            throw new IllegalArgumentException("activationId is invalid");
        }
        identifier(tenantSurrogate, "tenantSurrogate");
        identifier(caseId, "caseId");
        roomType = Objects.requireNonNull(roomType, "roomType");
        if (roomEpoch < 0
                || roomEpoch > MAX_SAFE_INTEGER
                || roomFencingToken < 1
                || roomFencingToken > MAX_SAFE_INTEGER
                || processRevision < 0
                || processRevision > MAX_SAFE_INTEGER
                || stageSequence < 0
                || stageSequence > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("receipt revisions or fence are invalid");
        }
        identifier(logicalRunId, "logicalRunId");
        identifier(attemptId, "attemptId");
        sha256(commandHash, "commandHash");
        sha256(commandEnvelopeHash, "commandEnvelopeHash");
        identifier(graphKey, "graphKey");
        identifier(graphVersion, "graphVersion");
        identifier(checkpointSchemaVersion, "checkpointSchemaVersion");
        identifier(checkpointId, "checkpointId");
        sha256(resultHash, "resultHash");
        sha256(proposalHash, "proposalHash");
        sha256(resultEnvelopeHash, "resultEnvelopeHash");
        identifier(agentRunManifestId, "agentRunManifestId");
        sha256(agentRunManifestHash, "agentRunManifestHash");
        sha256(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
        committedAt = Objects.requireNonNull(committedAt, "committedAt");
        sha256(receiptHash, "receiptHash");
        if (formalWriter != FormalWriter.JAVA_FINALIZER_ONLY) {
            throw new IllegalArgumentException("formalWriter must be JAVA_FINALIZER_ONLY");
        }
        if (domainCommitStatus != DomainCommitStatus.COMMITTED) {
            throw new IllegalArgumentException("domainCommitStatus must be COMMITTED");
        }
    }

    public static ProductionFinalizationReceipt committed(CommitFacts facts) {
        Objects.requireNonNull(facts, "facts");
        ProductionFinalizationReceipt unsigned = facts.toReceipt(ZERO_HASH);
        return facts.toReceipt(ProductionFinalizationReceiptCodec.receiptHash(unsigned));
    }

    public void requireCanonicalHash() {
        String canonical = ProductionFinalizationReceiptCodec.receiptHash(this);
        if (!receiptHash.equals(canonical)) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_RECEIPT_HASH_MISMATCH", "receipt hash is not canonical");
        }
    }

    private static void exact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static void identifier(String value, String field) {
        if (value == null
                || value.length() > 128
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }

    public enum FormalWriter {
        JAVA_FINALIZER_ONLY
    }

    public enum DomainCommitStatus {
        COMMITTED
    }

    public record CommitFacts(
            String activationId,
            String tenantSurrogate,
            String caseId,
            RoomType roomType,
            long roomEpoch,
            long roomFencingToken,
            long processRevision,
            long stageSequence,
            String logicalRunId,
            String attemptId,
            String commandHash,
            String commandEnvelopeHash,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String checkpointId,
            String resultHash,
            String proposalHash,
            String resultEnvelopeHash,
            String agentRunManifestId,
            String agentRunManifestHash,
            String isolatedDomainDbBindingHash,
            Instant committedAt) {

        public CommitFacts {
            committedAt = Objects.requireNonNull(committedAt, "committedAt")
                    .truncatedTo(ChronoUnit.MICROS);
        }

        private ProductionFinalizationReceipt toReceipt(String receiptHash) {
            return new ProductionFinalizationReceipt(
                    SCHEMA_VERSION,
                    ProductionExecutionLaneVerifier.EXECUTION_LANE,
                    activationId,
                    tenantSurrogate,
                    caseId,
                    roomType,
                    roomEpoch,
                    roomFencingToken,
                    processRevision,
                    stageSequence,
                    logicalRunId,
                    attemptId,
                    commandHash,
                    commandEnvelopeHash,
                    graphKey,
                    graphVersion,
                    checkpointSchemaVersion,
                    checkpointId,
                    resultHash,
                    proposalHash,
                    resultEnvelopeHash,
                    agentRunManifestId,
                    agentRunManifestHash,
                    isolatedDomainDbBindingHash,
                    committedAt,
                    receiptHash,
                    FormalWriter.JAVA_FINALIZER_ONLY,
                    DomainCommitStatus.COMMITTED);
        }
    }
}

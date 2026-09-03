package com.example.dispute.workflow.application.intake;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact intake-finalization-receipt.v1 returned by the atomic Domain commit. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntakeFinalizationReceipt(
        String schemaVersion,
        String operationKey,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        String threadId,
        String actorScopeHash,
        String agentSessionId,
        String commandId,
        String logicalRunId,
        String attemptId,
        String resultHash,
        String proposalHash,
        long processRevision,
        long roomRevision,
        long fencingToken,
        String formalMessageId,
        Long dossierVersion,
        Long matrixVersion,
        List<String> domainEventIds,
        List<String> outboxIds,
        Status status,
        Instant committedAt,
        String receiptHash) {

    private static final Pattern IDENTIFIER_256 =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
    private static final Pattern OPERATION_KEY_512 =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final String ZERO_HASH = "0".repeat(64);

    public IntakeFinalizationReceipt {
        if (!"intake-finalization-receipt.v1".equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "schemaVersion must be intake-finalization-receipt.v1");
        }
        operationKey = operationKey(operationKey);
        tenantSurrogate = identifier256(tenantSurrogate, "tenantSurrogate");
        caseId = identifier256(caseId, "caseId");
        IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
        threadId = IntakeContractSupport.threadId(threadId);
        actorScopeHash = IntakeContractSupport.sha256(actorScopeHash, "actorScopeHash");
        agentSessionId = identifier256(agentSessionId, "agentSessionId");
        commandId = identifier256(commandId, "commandId");
        logicalRunId = identifier256(logicalRunId, "logicalRunId");
        attemptId = identifier256(attemptId, "attemptId");
        resultHash = IntakeContractSupport.sha256(resultHash, "resultHash");
        proposalHash = IntakeContractSupport.sha256(proposalHash, "proposalHash");
        IntakeContractSupport.nonNegative(processRevision, "processRevision");
        IntakeContractSupport.nonNegative(roomRevision, "roomRevision");
        IntakeContractSupport.positive(fencingToken, "fencingToken");
        formalMessageId = identifier256(formalMessageId, "formalMessageId");
        if (dossierVersion != null && dossierVersion < 1) {
            throw new IllegalArgumentException("dossierVersion must be positive");
        }
        if (matrixVersion != null && matrixVersion < 1) {
            throw new IllegalArgumentException("matrixVersion must be positive");
        }
        domainEventIds = identifiers256(domainEventIds, 1, 16, "domainEventIds");
        outboxIds = identifiers256(outboxIds, 0, 16, "outboxIds");
        if (status != Status.COMMITTED) {
            throw new IllegalArgumentException("status must be COMMITTED");
        }
        committedAt = Objects.requireNonNull(committedAt, "committedAt");
        receiptHash = IntakeContractSupport.sha256(receiptHash, "receiptHash");
    }

    public static IntakeFinalizationReceipt committed(CommitFacts facts) {
        Objects.requireNonNull(facts, "facts");
        IntakeFinalizationReceipt unsigned = facts.toReceipt(ZERO_HASH);
        return facts.toReceipt(IntakeFinalizationReceiptCodec.receiptHash(unsigned));
    }

    public void requireCanonicalHash() {
        String canonical = IntakeFinalizationReceiptCodec.receiptHash(this);
        if (!receiptHash.equals(canonical)) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_RECEIPT_HASH_MISMATCH", "receipt hash is not canonical");
        }
    }

    private static String identifier256(String value, String field) {
        if (value == null || !IDENTIFIER_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return value;
    }

    private static String operationKey(String value) {
        if (value == null || !OPERATION_KEY_512.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "operationKey must be a bounded 512-character identifier");
        }
        return value;
    }

    private static List<String> identifiers256(
            List<String> values, int minimum, int maximum, String field) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field));
        if (copy.size() < minimum || copy.size() > maximum) {
            throw new IllegalArgumentException(field + " has an invalid item count");
        }
        copy.forEach(value -> identifier256(value, field));
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(field + " must contain unique identifiers");
        }
        return copy;
    }

    public enum Status {
        COMMITTED
    }

    public record CommitFacts(
            String operationKey,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            String threadId,
            String actorScopeHash,
            String agentSessionId,
            String commandId,
            String logicalRunId,
            String attemptId,
            String resultHash,
            String proposalHash,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String formalMessageId,
            Long dossierVersion,
            Long matrixVersion,
            List<String> domainEventIds,
            List<String> outboxIds,
            Instant committedAt) {

        private IntakeFinalizationReceipt toReceipt(String receiptHash) {
            return new IntakeFinalizationReceipt(
                    "intake-finalization-receipt.v1",
                    operationKey,
                    tenantSurrogate,
                    caseId,
                    roomEpoch,
                    threadId,
                    actorScopeHash,
                    agentSessionId,
                    commandId,
                    logicalRunId,
                    attemptId,
                    resultHash,
                    proposalHash,
                    processRevision,
                    roomRevision,
                    fencingToken,
                    formalMessageId,
                    dossierVersion,
                    matrixVersion,
                    domainEventIds,
                    outboxIds,
                    Status.COMMITTED,
                    committedAt,
                    receiptHash);
        }
    }
}

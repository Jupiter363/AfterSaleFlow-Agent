package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.FormalFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Creates reference-only workflow evidence for a committed comparison ledger row. */
public final class IntakeSyntheticComparisonReceiptFactory {

    private IntakeSyntheticComparisonReceiptFactory() {}

    public static String comparisonKey(TurnFinalizationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("operation_key", request.operationKey());
        binding.put("request_hash", request.requestHash());
        binding.put("result_hash", request.graphExecution().operation().resultHash());
        return ContractJson.sha256Hex(binding);
    }

    public static String comparisonHash(
            IntakeShadowComparison comparison, ObjectMapper objectMapper) {
        Objects.requireNonNull(comparison, "comparison must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        JsonNode payload = objectMapper.valueToTree(comparison);
        return ContractJson.sha256Hex(payload);
    }

    public static TurnFinalizationReceipt create(
            TurnFinalizationRequest request,
            IntakeShadowComparison comparison,
            IntakeDomainEventType eventType,
            OffsetDateTime recordedAt) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(comparison, "comparison must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        if (eventType != IntakeDomainEventType.TURN_NEEDS_INPUT
                && eventType != IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
            throw new IllegalArgumentException("comparison receipt requires a turn event");
        }

        var envelope = request.envelope();
        var graph = request.graphExecution();
        String key = comparisonKey(request);
        String suffix = key.substring(0, 24).toUpperCase(Locale.ROOT);
        String eventId = "SHADOW_EVENT_" + suffix;
        String eventRef = "urn:after-sale-flow:intake-shadow-comparison:" + key;
        long processRevision = Math.max(envelope.processRevision(), envelope.commandSequence());
        long roomRevision = Math.max(envelope.roomRevision(), envelope.commandSequence());

        ObjectNode eventBinding = JsonNodeFactory.instance.objectNode();
        eventBinding.put("comparison_key_hash", key);
        eventBinding.put("comparison_verdict", comparison.verdict().name());
        eventBinding.put("event_id", eventId);
        eventBinding.put("operation_key", request.operationKey());
        eventBinding.put("request_hash", request.requestHash());
        eventBinding.put("result_hash", graph.operation().resultHash());
        String eventHash = ContractJson.sha256Hex(eventBinding);

        IntakeDomainEventRef event =
                new IntakeDomainEventRef(
                        "intake-domain-event-ref.v1",
                        eventId,
                        eventRef,
                        eventHash,
                        envelope.commandSequence(),
                        eventType,
                        envelope.party(),
                        envelope.commandId(),
                        envelope.tenantSurrogate(),
                        envelope.caseId(),
                        envelope.roomEpoch(),
                        envelope.fencingToken(),
                        envelope.actorScopeHash(),
                        request.operationKey(),
                        request.requestHash(),
                        graph.operation().resultHash(),
                        processRevision,
                        roomRevision,
                        graph.agentRunRef(),
                        graph.graphExecutionRef());

        OperationReceipt operation =
                new OperationReceipt(
                        "intake-operation-receipt.v1",
                        request.operationKey(),
                        request.requestHash(),
                        graph.operation().resultHash(),
                        processRevision,
                        roomRevision);
        String receiptHash = receiptHash(request, comparison, event, recordedAt);
        FormalFinalizationReceipt shadowEvidence =
                new FormalFinalizationReceipt(
                        "intake-finalization-receipt.v1",
                        request.operationKey(),
                        envelope.tenantSurrogate(),
                        envelope.caseId(),
                        envelope.roomEpoch(),
                        request.threadId(),
                        envelope.actorScopeHash(),
                        request.agentSessionId(),
                        envelope.commandId(),
                        graph.agentRunRef().logicalRunId(),
                        graph.agentRunRef().attemptId(),
                        graph.operation().resultHash(),
                        graph.graphExecutionRef().proposalHash(),
                        processRevision,
                        roomRevision,
                        envelope.fencingToken(),
                        "SHADOW_ONLY_" + suffix,
                        null,
                        null,
                        List.of(eventId),
                        List.of(),
                        "COMMITTED",
                        recordedAt.toString(),
                        receiptHash);
        TurnFinalizationReceipt receipt =
                new TurnFinalizationReceipt(
                        "intake-turn-finalization-activity-receipt.v1",
                        operation,
                        shadowEvidence,
                        event);
        receipt.requireMatches(request);
        return receipt;
    }

    private static String receiptHash(
            TurnFinalizationRequest request,
            IntakeShadowComparison comparison,
            IntakeDomainEventRef event,
            OffsetDateTime recordedAt) {
        ObjectNode binding = JsonNodeFactory.instance.objectNode();
        binding.put("comparison_key_hash", comparison.comparisonKeyHash());
        binding.put("event_hash", event.eventHash());
        binding.put("operation_key", request.operationKey());
        binding.put("recorded_at", recordedAt.toString());
        binding.put("request_hash", request.requestHash());
        binding.put("result_hash", request.graphExecution().operation().resultHash());
        return ContractJson.sha256Hex(binding);
    }
}

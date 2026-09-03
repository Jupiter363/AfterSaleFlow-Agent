package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Strict public multiplex stream contract for the parallel Intake execution profile.
 *
 * <p>This is deliberately separate from {@link AgentStreamEvent}. V3 remains a single-frame
 * protocol and must not be widened with V4 payloads or reset semantics.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentStreamEventV4(
        String schemaVersion,
        String runId,
        String attemptId,
        long sequenceNo,
        EventType eventType,
        Audience audience,
        Instant occurredAt,
        Payload payload) {

    public AgentStreamEventV4 {
        schemaVersion = version(schemaVersion, "agent-stream.v4");
        required(runId, "runId");
        required(attemptId, "attemptId");
        required(eventType, "eventType");
        required(audience, "audience");
        required(occurredAt, "occurredAt");
        required(payload, "payload").validateFor(eventType);
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("sequenceNo must not be negative");
        }
    }

    public enum EventType {
        PUBLIC_FRAME_START("public_frame_start"),
        PUBLIC_FRAME_PROJECTION_ITEM("public_frame_projection_item"),
        ACTIVE_FRAME_SNAPSHOT("active_frame_snapshot"),
        FRAME_GENERATION_RESET("frame_generation_reset"),
        PUBLIC_FRAME_SEALED("public_frame_sealed"),
        PUBLIC_FRAME_INTERRUPTED("public_frame_interrupted"),
        USAGE("usage"),
        FINAL("final"),
        ERROR("error");

        private final String wireValue;

        EventType(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }

        @JsonCreator
        public static EventType fromWire(String value) {
            for (EventType candidate : values()) {
                if (candidate.wireValue.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown v4 stream event type: " + value);
        }
    }

    public enum FrameType {
        DIALOGUE_FRAME,
        DOSSIER_FRAME,
        QUALITY_FRAME
    }

    public enum DeliveryClass {
        DURABLE_CONTROL,
        DURABLE_PREVIEW,
        DURABLE_STAGING,
        DURABLE_TERMINAL
    }

    public enum ValueKind {
        TEXT,
        JSON_VALUE
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Payload(
            String frameId,
            FrameType frameType,
            Integer generation,
            String frameSetReceiptId,
            String projectionRegistryVersion,
            DeliveryClass deliveryClass,
            Integer localIndex,
            Integer nextLocalIndex,
            String canonicalItemId,
            String projectionKind,
            String projectionPathId,
            ValueKind valueKind,
            String canonicalValueJson,
            String publicText,
            String itemSha256,
            Integer frameRevision,
            String projectionSha256,
            String oldFrameId,
            String newFrameId,
            Integer oldGeneration,
            Integer newGeneration,
            String reasonCode,
            String frameReceiptId,
            String resultSha256,
            String publicProjectionSha256,
            Boolean retryable,
            Usage usage,
            String finalReceiptId,
            String finalResultHash,
            String errorCode) {

        public static Payload frameStartPayload(
                String frameId,
                FrameType frameType,
                int generation,
                String frameSetReceiptId,
                String projectionRegistryVersion) {
            return new Payload(
                    frameId, frameType, generation, frameSetReceiptId,
                    projectionRegistryVersion, DeliveryClass.DURABLE_CONTROL,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }

        public static Payload projectionItemPayload(
                String frameId,
                FrameType frameType,
                int generation,
                int localIndex,
                int nextLocalIndex,
                String canonicalItemId,
                String projectionKind,
                String projectionPathId,
                ValueKind valueKind,
                String canonicalValueJson,
                String publicText,
                String itemSha256) {
            return new Payload(
                    frameId, frameType, generation, null, null,
                    DeliveryClass.DURABLE_PREVIEW, localIndex, nextLocalIndex,
                    canonicalItemId, projectionKind, projectionPathId, valueKind,
                    canonicalValueJson, publicText, itemSha256, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null);
        }

        public static Payload generationResetPayload(
                String oldFrameId,
                String newFrameId,
                FrameType frameType,
                int oldGeneration,
                int newGeneration,
                String reasonCode) {
            return new Payload(
                    null, // frameId
                    frameType,
                    null, // generation
                    null, // frameSetReceiptId
                    null, // projectionRegistryVersion
                    DeliveryClass.DURABLE_CONTROL,
                    null, // localIndex
                    null, // nextLocalIndex
                    null, // canonicalItemId
                    null, // projectionKind
                    null, // projectionPathId
                    null, // valueKind
                    null, // canonicalValueJson
                    null, // publicText
                    null, // itemSha256
                    null, // frameRevision
                    null, // projectionSha256
                    oldFrameId,
                    newFrameId,
                    oldGeneration,
                    newGeneration,
                    reasonCode,
                    null, // frameReceiptId
                    null, // resultSha256
                    null, // publicProjectionSha256
                    null, // retryable
                    null, // usage
                    null, // finalReceiptId
                    null, // finalResultHash
                    null); // errorCode
        }

        public static Payload interruptedPayload(
                String frameId,
                FrameType frameType,
                int generation,
                int nextLocalIndex,
                String reasonCode,
                boolean retryable) {
            return new Payload(
                    frameId,
                    frameType,
                    generation,
                    null, // frameSetReceiptId
                    null, // projectionRegistryVersion
                    DeliveryClass.DURABLE_CONTROL,
                    null, // localIndex
                    nextLocalIndex,
                    null, // canonicalItemId
                    null, // projectionKind
                    null, // projectionPathId
                    null, // valueKind
                    null, // canonicalValueJson
                    null, // publicText
                    null, // itemSha256
                    null, // frameRevision
                    null, // projectionSha256
                    null, // oldFrameId
                    null, // newFrameId
                    null, // oldGeneration
                    null, // newGeneration
                    reasonCode,
                    null, // frameReceiptId
                    null, // resultSha256
                    null, // publicProjectionSha256
                    retryable,
                    null, // usage
                    null, // finalReceiptId
                    null, // finalResultHash
                    null); // errorCode
        }

        public static Payload usagePayload(
                FrameType frameType, int generation, Usage usage) {
            return new Payload(
                    null, frameType, generation, null, null,
                    DeliveryClass.DURABLE_STAGING, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, required(usage, "usage"), null,
                    null, null);
        }

        public static Payload finalPayload(String receiptId, String resultHash) {
            return new Payload(
                    null, // frameId
                    null, // frameType
                    null, // generation
                    null, // frameSetReceiptId
                    null, // projectionRegistryVersion
                    DeliveryClass.DURABLE_TERMINAL,
                    null, // localIndex
                    null, // nextLocalIndex
                    null, // canonicalItemId
                    null, // projectionKind
                    null, // projectionPathId
                    null, // valueKind
                    null, // canonicalValueJson
                    null, // publicText
                    null, // itemSha256
                    null, // frameRevision
                    null, // projectionSha256
                    null, // oldFrameId
                    null, // newFrameId
                    null, // oldGeneration
                    null, // newGeneration
                    null, // reasonCode
                    null, // frameReceiptId
                    null, // resultSha256
                    null, // publicProjectionSha256
                    null, // retryable
                    null, // usage
                    required(receiptId, "finalReceiptId"),
                    required(resultHash, "finalResultHash"),
                    null); // errorCode
        }

        public static Payload errorPayload(String errorCode, boolean retryable) {
            return new Payload(
                    null, // frameId
                    null, // frameType
                    null, // generation
                    null, // frameSetReceiptId
                    null, // projectionRegistryVersion
                    DeliveryClass.DURABLE_TERMINAL,
                    null, // localIndex
                    null, // nextLocalIndex
                    null, // canonicalItemId
                    null, // projectionKind
                    null, // projectionPathId
                    null, // valueKind
                    null, // canonicalValueJson
                    null, // publicText
                    null, // itemSha256
                    null, // frameRevision
                    null, // projectionSha256
                    null, // oldFrameId
                    null, // newFrameId
                    null, // oldGeneration
                    null, // newGeneration
                    null, // reasonCode
                    null, // frameReceiptId
                    null, // resultSha256
                    null, // publicProjectionSha256
                    retryable,
                    null, // usage
                    null, // finalReceiptId
                    null, // finalResultHash
                    required(errorCode, "errorCode"));
        }

        private void validateFor(EventType eventType) {
            Set<String> expected = switch (eventType) {
                case PUBLIC_FRAME_START -> Set.of(
                        "frameId",
                        "frameType",
                        "generation",
                        "frameSetReceiptId",
                        "projectionRegistryVersion",
                        "deliveryClass");
                case PUBLIC_FRAME_PROJECTION_ITEM -> projectionItemFields();
                case ACTIVE_FRAME_SNAPSHOT -> Set.of(
                        "frameId",
                        "frameType",
                        "generation",
                        "frameRevision",
                        "nextLocalIndex",
                        "projectionSha256",
                        "deliveryClass");
                case FRAME_GENERATION_RESET -> Set.of(
                        "oldFrameId",
                        "newFrameId",
                        "frameType",
                        "oldGeneration",
                        "newGeneration",
                        "reasonCode",
                        "deliveryClass");
                case PUBLIC_FRAME_SEALED -> Set.of(
                        "frameId",
                        "frameType",
                        "generation",
                        "frameReceiptId",
                        "nextLocalIndex",
                        "resultSha256",
                        "publicProjectionSha256",
                        "deliveryClass");
                case PUBLIC_FRAME_INTERRUPTED -> Set.of(
                        "frameId",
                        "frameType",
                        "generation",
                        "nextLocalIndex",
                        "reasonCode",
                        "retryable",
                        "deliveryClass");
                case USAGE -> Set.of("frameType", "generation", "usage", "deliveryClass");
                case FINAL -> Set.of("finalReceiptId", "finalResultHash", "deliveryClass");
                case ERROR -> Set.of("errorCode", "retryable", "deliveryClass");
            };
            Set<String> actual = actualFields();
            if (!actual.equals(expected)) {
                Set<String> missing = new HashSet<>(expected);
                missing.removeAll(actual);
                Set<String> unexpected = new HashSet<>(actual);
                unexpected.removeAll(expected);
                throw new IllegalArgumentException(
                        eventType.wireValue() + " payload fields mismatch; missing=" + missing
                                + ", unexpected=" + unexpected);
            }

            DeliveryClass expectedDelivery = switch (eventType) {
                case PUBLIC_FRAME_START, FRAME_GENERATION_RESET, PUBLIC_FRAME_INTERRUPTED ->
                        DeliveryClass.DURABLE_CONTROL;
                case PUBLIC_FRAME_PROJECTION_ITEM, ACTIVE_FRAME_SNAPSHOT ->
                        DeliveryClass.DURABLE_PREVIEW;
                case PUBLIC_FRAME_SEALED, USAGE -> DeliveryClass.DURABLE_STAGING;
                case FINAL, ERROR -> DeliveryClass.DURABLE_TERMINAL;
            };
            if (deliveryClass != expectedDelivery) {
                throw new IllegalArgumentException(
                        eventType.wireValue() + " deliveryClass must be " + expectedDelivery);
            }
            if (eventType == EventType.PUBLIC_FRAME_PROJECTION_ITEM
                    && nextLocalIndex != localIndex + 1) {
                throw new IllegalArgumentException(
                        "nextLocalIndex must equal localIndex + 1");
            }
            if (eventType == EventType.FRAME_GENERATION_RESET
                    && newGeneration != oldGeneration + 1) {
                throw new IllegalArgumentException(
                        "newGeneration must equal oldGeneration + 1");
            }
            if (eventType == EventType.USAGE
                    && usage.totalTokens() != usage.inputTokens() + usage.outputTokens()) {
                throw new IllegalArgumentException(
                        "usage totalTokens must equal inputTokens + outputTokens");
            }
        }

        private Set<String> projectionItemFields() {
            Set<String> fields = new HashSet<>(Set.of(
                    "frameId",
                    "frameType",
                    "generation",
                    "localIndex",
                    "nextLocalIndex",
                    "canonicalItemId",
                    "projectionKind",
                    "projectionPathId",
                    "valueKind",
                    "itemSha256",
                    "deliveryClass"));
            if (valueKind == ValueKind.TEXT) {
                fields.add("publicText");
            } else if (valueKind == ValueKind.JSON_VALUE) {
                fields.add("canonicalValueJson");
            }
            return Set.copyOf(fields);
        }

        private Set<String> actualFields() {
            Set<String> fields = new HashSet<>();
            add(fields, "frameId", frameId);
            add(fields, "frameType", frameType);
            add(fields, "generation", generation);
            add(fields, "frameSetReceiptId", frameSetReceiptId);
            add(fields, "projectionRegistryVersion", projectionRegistryVersion);
            add(fields, "deliveryClass", deliveryClass);
            add(fields, "localIndex", localIndex);
            add(fields, "nextLocalIndex", nextLocalIndex);
            add(fields, "canonicalItemId", canonicalItemId);
            add(fields, "projectionKind", projectionKind);
            add(fields, "projectionPathId", projectionPathId);
            add(fields, "valueKind", valueKind);
            add(fields, "canonicalValueJson", canonicalValueJson);
            add(fields, "publicText", publicText);
            add(fields, "itemSha256", itemSha256);
            add(fields, "frameRevision", frameRevision);
            add(fields, "projectionSha256", projectionSha256);
            add(fields, "oldFrameId", oldFrameId);
            add(fields, "newFrameId", newFrameId);
            add(fields, "oldGeneration", oldGeneration);
            add(fields, "newGeneration", newGeneration);
            add(fields, "reasonCode", reasonCode);
            add(fields, "frameReceiptId", frameReceiptId);
            add(fields, "resultSha256", resultSha256);
            add(fields, "publicProjectionSha256", publicProjectionSha256);
            add(fields, "retryable", retryable);
            add(fields, "usage", usage);
            add(fields, "finalReceiptId", finalReceiptId);
            add(fields, "finalResultHash", finalResultHash);
            add(fields, "errorCode", errorCode);
            return Set.copyOf(fields);
        }

        private static void add(Set<String> fields, String name, Object value) {
            if (value != null) {
                fields.add(name);
            }
        }
    }
}

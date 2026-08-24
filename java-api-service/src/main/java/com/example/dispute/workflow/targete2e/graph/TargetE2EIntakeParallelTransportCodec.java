package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict decoder for Python-private exact-three technical transport documents. */
final class TargetE2EIntakeParallelTransportCodec {

    static final String AUTHORITY_SCHEMA = "intake.parallel-frame-stream-authority.v1";
    static final String EVENT_SCHEMA = "intake.parallel-frame-technical-event.v1";
    private static final int MAXIMUM_AUTHORITY_HEADER_BYTES = 8 * 1024;
    private static final int MAXIMUM_EVENT_LINE_BYTES =
            GraphCommandHttpTransport.MAXIMUM_PARALLEL_LINE_BYTES;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Set<String> AUTHORITY_FIELDS = Set.of(
            "schema_version", "frame_set_id", "run_id", "attempt_id", "frames",
            "authority_sha256");
    private static final Set<String> FRAME_AUTHORITY_FIELDS = Set.of(
            "frame_type", "generation", "frame_id", "frame_model_input_sha256",
            "frame_prompt_sha256", "context_envelope_sha256",
            "model_context_view_sha256");
    private static final Set<String> COMMON_EVENT_FIELDS = Set.of(
            "schema_version", "frame_set_id", "run_id", "attempt_id", "frame_type",
            "occurred_at", "event_kind");

    private final ObjectMapper mapper;

    TargetE2EIntakeParallelTransportCodec(ObjectMapper objectMapper) {
        mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    StreamAuthority decodeAuthority(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 16 * 1024) {
            throw invalid("parallel Frame authority header is absent or oversized");
        }
        byte[] bytes;
        try {
            int padding = (4 - encoded.length() % 4) % 4;
            bytes = Base64.getUrlDecoder().decode(encoded + "=".repeat(padding));
        } catch (IllegalArgumentException failure) {
            throw invalid("parallel Frame authority header is not base64url", failure);
        }
        if (bytes.length == 0 || bytes.length > MAXIMUM_AUTHORITY_HEADER_BYTES) {
            throw invalid("parallel Frame authority document is oversized");
        }
        JsonNode root = read(bytes, "parallel Frame authority");
        requireObjectFields(root, AUTHORITY_FIELDS, "parallel Frame authority");
        requireText(root, "schema_version", AUTHORITY_SCHEMA);
        String authorityHash = sha256(root, "authority_sha256");
        ObjectNode unsigned = root.deepCopy();
        unsigned.remove("authority_sha256");
        if (!authorityHash.equals(ContractJson.sha256Hex(unsigned))) {
            throw invalid("parallel Frame authority self-hash drifted");
        }
        JsonNode rawFrames = root.required("frames");
        if (!rawFrames.isArray() || rawFrames.size() != FrameType.values().length) {
            throw invalid("parallel Frame authority is not exact-three");
        }
        List<FrameAuthority> frames = new ArrayList<>();
        Set<FrameType> observed = new HashSet<>();
        for (int index = 0; index < rawFrames.size(); index++) {
            JsonNode raw = rawFrames.get(index);
            requireObjectFields(raw, FRAME_AUTHORITY_FIELDS, "parallel Frame authority item");
            FrameType type = frameType(raw);
            if (type != FrameType.values()[index] || !observed.add(type)) {
                throw invalid("parallel Frame authority order or uniqueness drifted");
            }
            frames.add(new FrameAuthority(
                    type,
                    positiveInt(raw, "generation"),
                    identifier(raw, "frame_id"),
                    sha256(raw, "frame_model_input_sha256"),
                    sha256(raw, "frame_prompt_sha256"),
                    sha256(raw, "context_envelope_sha256"),
                    sha256(raw, "model_context_view_sha256")));
        }
        String contextHash = frames.getFirst().contextEnvelopeSha256();
        String modelContextHash = frames.getFirst().modelContextViewSha256();
        if (frames.stream().anyMatch(frame ->
                !contextHash.equals(frame.contextEnvelopeSha256())
                        || !modelContextHash.equals(frame.modelContextViewSha256()))) {
            throw invalid("parallel Frame authority does not share one frozen context");
        }
        return new StreamAuthority(
                identifier(root, "frame_set_id"),
                identifier(root, "run_id"),
                identifier(root, "attempt_id"),
                List.copyOf(frames),
                authorityHash);
    }

    EncodedAdmissionReceipt encodeAdmissionReceipt(
            String requestHash,
            String javaReceiptId,
            StreamAuthority authority) {
        if (!SHA256.matcher(Objects.requireNonNull(requestHash, "requestHash")).matches()
                || !IDENTIFIER.matcher(Objects.requireNonNull(javaReceiptId, "javaReceiptId")).matches()
                || authority == null
                || authority.frames().size() != FrameType.values().length) {
            throw invalid("parallel admission receipt input is invalid");
        }
        ObjectNode document = mapper.createObjectNode();
        document.put("schema_version", "intake.parallel-admission-receipt.v1");
        document.put("request_hash", requestHash);
        document.put("frame_set_id", authority.frameSetId());
        document.put("run_id", authority.runId());
        document.put("attempt_id", authority.attemptId());
        document.put("java_receipt_id", javaReceiptId);
        document.put("authority_sha256", authority.authoritySha256());
        ArrayNode lanes = document.putArray("lanes");
        for (int index = 0; index < authority.frames().size(); index++) {
            FrameAuthority frame = authority.frames().get(index);
            if (frame.frameType() != FrameType.values()[index]) {
                throw invalid("parallel admission receipt lane order drifted");
            }
            ObjectNode lane = lanes.addObject();
            lane.put("frame_type", frame.frameType().name());
            lane.put("generation", frame.generation());
            lane.put("frame_id", frame.frameId());
            lane.put("action", "RUN");
            lane.put("next_local_index", 0);
        }
        String receiptHash = ContractJson.sha256Hex(document);
        document.put("receipt_sha256", receiptHash);
        byte[] canonical = ContractJson.canonicalize(document);
        if (canonical.length > 12 * 1024) {
            throw invalid("parallel admission receipt document is oversized");
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(canonical);
        return new EncodedAdmissionReceipt(encoded, receiptHash);
    }

    TechnicalEvent decodeEvent(String line) {
        if (line == null || line.isBlank()
                || line.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_EVENT_LINE_BYTES) {
            throw invalid("parallel Frame event line is absent or oversized");
        }
        JsonNode root = read(line.getBytes(StandardCharsets.UTF_8), "parallel Frame event");
        String kind = text(root, "event_kind");
        Set<String> expected = new HashSet<>(COMMON_EVENT_FIELDS);
        switch (kind) {
            case "FRAME_STARTED" -> expected.addAll(Set.of(
                    "generation", "frame_id", "frame_model_input_sha256",
                    "frame_prompt_sha256", "context_envelope_sha256",
                    "model_context_view_sha256"));
            case "FRAME_PROJECTION_ITEM" -> expected.addAll(Set.of(
                    "generation", "frame_id", "local_index", "next_local_index",
                    "item", "item_sha256"));
            case "FRAME_GENERATION_RESET" -> expected.addAll(Set.of(
                    "old_generation", "new_generation", "old_frame_id", "new_frame_id",
                    "reason_code"));
            case "FRAME_INTERRUPTED" -> expected.addAll(Set.of(
                    "generation", "frame_id", "error_code", "retryable"));
            case "FRAME_SEALED" -> expected.addAll(Set.of(
                    "generation", "frame_id", "child_checkpoint_ref",
                    "child_checkpoint_sha256", "context_envelope_sha256",
                    "model_context_view_sha256", "canonical_result_json", "result_sha256",
                    "public_projection_sha256", "next_local_index", "usage",
                    "completed_at"));
            default -> throw invalid("unsupported parallel Frame event kind: " + kind);
        }
        requireObjectFields(root, Set.copyOf(expected), "parallel Frame event");
        requireText(root, "schema_version", EVENT_SCHEMA);
        Common common = new Common(
                identifier(root, "frame_set_id"),
                identifier(root, "run_id"),
                identifier(root, "attempt_id"),
                frameType(root),
                instant(root, "occurred_at"));
        return switch (kind) {
            case "FRAME_STARTED" -> new Started(
                    common,
                    positiveInt(root, "generation"),
                    identifier(root, "frame_id"),
                    sha256(root, "frame_model_input_sha256"),
                    sha256(root, "frame_prompt_sha256"),
                    sha256(root, "context_envelope_sha256"),
                    sha256(root, "model_context_view_sha256"));
            case "FRAME_PROJECTION_ITEM" -> projection(common, root);
            case "FRAME_GENERATION_RESET" -> new GenerationReset(
                    common,
                    positiveInt(root, "old_generation"),
                    positiveInt(root, "new_generation"),
                    identifier(root, "old_frame_id"),
                    identifier(root, "new_frame_id"),
                    identifier(root, "reason_code"));
            case "FRAME_INTERRUPTED" -> new Interrupted(
                    common,
                    positiveInt(root, "generation"),
                    identifier(root, "frame_id"),
                    identifier(root, "error_code"),
                    bool(root, "retryable"));
            case "FRAME_SEALED" -> sealed(common, root);
            default -> throw new IllegalStateException("unreachable parallel Frame event kind");
        };
    }

    private ProjectionItem projection(Common common, JsonNode root) {
        int localIndex = nonNegativeInt(root, "local_index");
        int nextLocalIndex = positiveInt(root, "next_local_index");
        if (nextLocalIndex != localIndex + 1) {
            throw invalid("parallel Frame projection local index is not contiguous");
        }
        JsonNode item = root.required("item");
        String valueKind = text(item, "value_kind");
        Set<String> itemFields = new HashSet<>(Set.of(
                "canonical_item_id", "projection_kind", "projection_path_id", "value_kind"));
        if ("TEXT".equals(valueKind)) {
            itemFields.add("public_text");
        } else if ("JSON_VALUE".equals(valueKind)) {
            itemFields.add("canonical_value");
        } else {
            throw invalid("parallel Frame projection value kind is invalid");
        }
        requireObjectFields(item, Set.copyOf(itemFields), "parallel Frame projection item");
        String itemHash = sha256(root, "item_sha256");
        if (!itemHash.equals(ContractJson.sha256Hex(item))) {
            throw invalid("parallel Frame projection item hash drifted");
        }
        return new ProjectionItem(
                common,
                positiveInt(root, "generation"),
                identifier(root, "frame_id"),
                localIndex,
                nextLocalIndex,
                identifier(item, "canonical_item_id"),
                identifier(item, "projection_kind"),
                identifier(item, "projection_path_id"),
                valueKind,
                "JSON_VALUE".equals(valueKind)
                        ? ContractJson.canonicalString(item.required("canonical_value"))
                        : null,
                "TEXT".equals(valueKind) ? text(item, "public_text") : null,
                itemHash);
    }

    private Sealed sealed(Common common, JsonNode root) {
        JsonNode usage = root.required("usage");
        requireObjectFields(
                usage,
                Set.of("input_tokens", "output_tokens", "total_tokens", "latency_ms",
                        "provider_call_count", "model"),
                "parallel Frame usage");
        long inputTokens = nonNegativeLong(usage, "input_tokens");
        long outputTokens = nonNegativeLong(usage, "output_tokens");
        long totalTokens = nonNegativeLong(usage, "total_tokens");
        if (totalTokens != inputTokens + outputTokens) {
            throw invalid("parallel Frame usage total drifted");
        }
        return new Sealed(
                common,
                positiveInt(root, "generation"),
                identifier(root, "frame_id"),
                boundedText(root, "child_checkpoint_ref", 1024),
                sha256(root, "child_checkpoint_sha256"),
                sha256(root, "context_envelope_sha256"),
                sha256(root, "model_context_view_sha256"),
                boundedText(root, "canonical_result_json", 262_144),
                sha256(root, "result_sha256"),
                sha256(root, "public_projection_sha256"),
                nonNegativeInt(root, "next_local_index"),
                new Usage(
                        inputTokens,
                        outputTokens,
                        totalTokens,
                        nonNegativeLong(usage, "latency_ms"),
                        boundedProviderCount(usage),
                        identifier(usage, "model")),
                instant(root, "completed_at"));
    }

    private JsonNode read(byte[] bytes, String description) {
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw invalid(description + " must be a JSON object");
            }
            return root;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid(description + " is not strict JSON", failure);
        } catch (java.io.IOException failure) {
            throw invalid(description + " could not be decoded", failure);
        }
    }

    private static void requireObjectFields(JsonNode node, Set<String> expected, String name) {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be an object");
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid(name + " fields differ; expected=" + expected + ", actual=" + actual);
        }
    }

    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(text(node, field))) {
            throw invalid(field + " differs from the required contract");
        }
    }

    private static String identifier(JsonNode node, String field) {
        String value = text(node, field);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw invalid(field + " is not a bounded identifier");
        }
        return value;
    }

    private static String sha256(JsonNode node, String field) {
        String value = text(node, field);
        if (!SHA256.matcher(value).matches()) {
            throw invalid(field + " is not lowercase SHA-256");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw invalid(field + " must be non-empty text");
        }
        return value.textValue();
    }

    private static String boundedText(JsonNode node, String field, int maximum) {
        String value = text(node, field);
        if (value.length() > maximum) {
            throw invalid(field + " exceeds its bound");
        }
        return value;
    }

    private static FrameType frameType(JsonNode node) {
        try {
            return FrameType.valueOf(text(node, "frame_type"));
        } catch (IllegalArgumentException failure) {
            throw invalid("frame_type is not registered", failure);
        }
    }

    private static int positiveInt(JsonNode node, String field) {
        int value = nonNegativeInt(node, field);
        if (value < 1) {
            throw invalid(field + " must be positive");
        }
        return value;
    }

    private static int nonNegativeInt(JsonNode node, String field) {
        long value = nonNegativeLong(node, field);
        if (value > Integer.MAX_VALUE) {
            throw invalid(field + " exceeds the V4 wire integer range");
        }
        return (int) value;
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static int boundedProviderCount(JsonNode usage) {
        int count = positiveInt(usage, "provider_call_count");
        if (count > 2) {
            throw invalid("provider_call_count exceeds the bounded retry policy");
        }
        return count;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException failure) {
            throw invalid(field + " must be RFC3339 UTC time", failure);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }

    record StreamAuthority(
            String frameSetId,
            String runId,
            String attemptId,
            List<FrameAuthority> frames,
            String authoritySha256) {
        StreamAuthority {
            frames = List.copyOf(frames);
        }

        Map<FrameType, FrameAuthority> framesByType() {
            EnumMap<FrameType, FrameAuthority> indexed = new EnumMap<>(FrameType.class);
            frames.forEach(frame -> indexed.put(frame.frameType(), frame));
            return Map.copyOf(indexed);
        }
    }

    record FrameAuthority(
            FrameType frameType,
            int generation,
            String frameId,
            String frameModelInputSha256,
            String framePromptSha256,
            String contextEnvelopeSha256,
            String modelContextViewSha256) {}

    record EncodedAdmissionReceipt(String headerValue, String receiptSha256) {
        EncodedAdmissionReceipt {
            Objects.requireNonNull(headerValue, "headerValue");
            Objects.requireNonNull(receiptSha256, "receiptSha256");
        }
    }

    sealed interface TechnicalEvent
            permits Started, ProjectionItem, GenerationReset, Interrupted, Sealed {
        Common common();
    }

    record Common(
            String frameSetId,
            String runId,
            String attemptId,
            FrameType frameType,
            Instant occurredAt) {}

    record Started(
            Common common,
            int generation,
            String frameId,
            String frameModelInputSha256,
            String framePromptSha256,
            String contextEnvelopeSha256,
            String modelContextViewSha256) implements TechnicalEvent {}

    record ProjectionItem(
            Common common,
            int generation,
            String frameId,
            int localIndex,
            int nextLocalIndex,
            String canonicalItemId,
            String projectionKind,
            String projectionPathId,
            String valueKind,
            String canonicalValueJson,
            String publicText,
            String itemSha256) implements TechnicalEvent {}

    record GenerationReset(
            Common common,
            int oldGeneration,
            int newGeneration,
            String oldFrameId,
            String newFrameId,
            String reasonCode) implements TechnicalEvent {}

    record Interrupted(
            Common common,
            int generation,
            String frameId,
            String errorCode,
            boolean retryable) implements TechnicalEvent {}

    record Sealed(
            Common common,
            int generation,
            String frameId,
            String childCheckpointRef,
            String childCheckpointSha256,
            String contextEnvelopeSha256,
            String modelContextViewSha256,
            String canonicalResultJson,
            String resultSha256,
            String publicProjectionSha256,
            int nextLocalIndex,
            Usage usage,
            Instant completedAt) implements TechnicalEvent {}

    record Usage(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long latencyMs,
            int providerCallCount,
            String model) {}
}

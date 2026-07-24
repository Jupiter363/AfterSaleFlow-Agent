package com.example.dispute.review.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Produces a canonical content hash over all immutable packet identity, pins, and bodies. */
public final class ReviewPacketContentHasher {

    private ReviewPacketContentHasher() {}

    public static String hash(ObjectMapper mapper, Map<String, ?> packet) {
        if (mapper == null || packet == null) {
            throw new IllegalArgumentException("mapper and packet are required");
        }
        JsonNode normalized = normalize(mapper, mapper.valueToTree(packet));
        try {
            byte[] bytes = mapper.writeValueAsString(normalized).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot hash frozen review packet", exception);
        }
    }

    public static JsonNode normalize(ObjectMapper mapper, JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value == null ? mapper.nullNode() : value.deepCopy();
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(normalize(mapper, item)));
            return result;
        }
        ObjectNode result = mapper.createObjectNode();
        TreeMap<String, JsonNode> ordered = new TreeMap<>();
        value.fields().forEachRemaining(entry -> ordered.put(entry.getKey(), entry.getValue()));
        ordered.forEach((key, item) -> result.set(key, normalize(mapper, item)));
        return result;
    }
}

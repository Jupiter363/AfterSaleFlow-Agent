package com.example.dispute.review.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class ActionSnapshotHasher {

    private ActionSnapshotHasher() {}

    public static String hash(ObjectMapper objectMapper, JsonNode plan) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("id", plan.path("id").asText());
        canonical.put("version", plan.path("version").asInt());
        canonical.set("actions", canonicalize(objectMapper, plan.path("actions")));
        canonical.set("preconditions", canonicalize(objectMapper, plan.path("preconditions")));
        canonical.set("notifications", canonicalize(objectMapper, plan.path("notifications")));
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "cannot hash approved action snapshot", exception);
        }
    }

    private static JsonNode canonicalize(ObjectMapper objectMapper, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream()
                    .sorted()
                    .forEach(name -> result.set(name, canonicalize(objectMapper, node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(canonicalize(objectMapper, item)));
            return result;
        }
        return node.deepCopy();
    }
}

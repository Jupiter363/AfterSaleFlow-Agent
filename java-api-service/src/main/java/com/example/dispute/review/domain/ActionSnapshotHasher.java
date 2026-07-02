package com.example.dispute.review.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class ActionSnapshotHasher {

    private ActionSnapshotHasher() {}

    public static String hash(ObjectMapper objectMapper, JsonNode plan) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("id", plan.path("id").asText());
        canonical.put("version", plan.path("version").asInt());
        canonical.set("actions", plan.path("actions"));
        canonical.set("preconditions", plan.path("preconditions"));
        canonical.set("notifications", plan.path("notifications"));
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
}

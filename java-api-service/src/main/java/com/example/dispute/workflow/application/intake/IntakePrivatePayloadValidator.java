package com.example.dispute.workflow.application.intake;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

final class IntakePrivatePayloadValidator {

    private static final Set<String> FORBIDDEN_KEYS =
            Set.of(
                    "memory_frame",
                    "internal_handoff",
                    "hidden_reasoning",
                    "chain_of_thought",
                    "tool_calls",
                    "tool_parameters",
                    "open_evidence",
                    "complete_party",
                    "send_summons",
                    "execute_tool",
                    "trusted_model_profile",
                    "prompt_version",
                    "model_profile_id",
                    "policy_version",
                    "guardrail_version",
                    "tool_policy_version",
                    "writer_mode",
                    "room_transition",
                    "evidence_deadline",
                    "reviewer_notes",
                    "internal_notes",
                    "credentials",
                    "audit_records",
                    "raw_audit_records",
                    "private_conversation",
                    "opposing_party_messages",
                    "opposing_party_private",
                    "other_party_messages",
                    "other_party_private");

    private IntakePrivatePayloadValidator() {}

    static void requireSafeObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        visit(value, field, 0);
    }

    private static void visit(JsonNode value, String path, int depth) {
        if (depth > 16) {
            throw new IllegalArgumentException(path + " exceeds the maximum nesting depth");
        }
        if (value.isObject()) {
            if (value.size() > 64) {
                throw new IllegalArgumentException(path + " has too many properties");
            }
            value.fields()
                    .forEachRemaining(
                            entry -> {
                                if (FORBIDDEN_KEYS.contains(entry.getKey())) {
                                    throw new IllegalArgumentException(
                                            path + " contains forbidden key " + entry.getKey());
                                }
                                visit(entry.getValue(), path + "." + entry.getKey(), depth + 1);
                            });
            return;
        }
        if (value.isArray()) {
            if (value.size() > 128) {
                throw new IllegalArgumentException(path + " has too many array items");
            }
            for (int index = 0; index < value.size(); index++) {
                visit(value.get(index), path + "[" + index + "]", depth + 1);
            }
            return;
        }
        if (value.isTextual() && value.textValue().length() > 20_000) {
            throw new IllegalArgumentException(path + " contains an oversized string");
        }
        if (!(value.isTextual()
                || value.isNumber()
                || value.isBoolean()
                || value.isNull())) {
            throw new IllegalArgumentException(path + " contains an unsupported JSON value");
        }
    }
}

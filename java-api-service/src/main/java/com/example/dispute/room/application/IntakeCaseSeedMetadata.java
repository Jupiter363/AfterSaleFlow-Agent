package com.example.dispute.room.application;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Optional;

/** Persists the trusted initial Intake form facts across asynchronous target execution. */
public final class IntakeCaseSeedMetadata {

    private static final String SCHEMA_VERSION = "fulfillment-case-metadata.v1";
    private static final String FACTS_FIELD = "intake_initial_case_facts";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IntakeCaseSeedMetadata() {}

    public static String encode(IntakeLobbySeed seed, String formSource) {
        Objects.requireNonNull(seed, "seed");
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("schema_version", SCHEMA_VERSION);
        metadata.set(FACTS_FIELD, JSON.valueToTree(IntakeInitialCaseFacts.from(seed, formSource)));
        return ContractJson.canonicalString(metadata);
    }

    public static Optional<IntakeInitialCaseFacts> decode(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode metadata = JSON.readTree(metadataJson);
            if (metadata == null || !metadata.isObject()) {
                throw new IllegalStateException("case metadata must be a JSON object");
            }
            JsonNode facts = metadata.get(FACTS_FIELD);
            if (facts == null || facts.isNull()) {
                return Optional.empty();
            }
            if (!SCHEMA_VERSION.equals(metadata.path("schema_version").asText())
                    || !facts.isObject()) {
                throw new IllegalStateException("case Intake seed metadata is invalid");
            }
            return Optional.of(JSON.treeToValue(facts, IntakeInitialCaseFacts.class));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("case Intake seed metadata is invalid", error);
        }
    }
}

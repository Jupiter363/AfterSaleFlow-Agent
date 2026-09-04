package com.example.dispute.workflow.runtime.finalization;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Frozen target-lane envelopes and hash-source documents loaded from authoritative storage. */
public record ProductionFinalizationEvidence(
        String activationManifestHash,
        JsonNode commandEnvelope,
        JsonNode resultEnvelope,
        JsonNode proposalSource,
        JsonNode isolatedDomainDbBinding) {

    public ProductionFinalizationEvidence {
        sha256(activationManifestHash, "activationManifestHash");
        commandEnvelope = copy(commandEnvelope, "commandEnvelope");
        resultEnvelope = copy(resultEnvelope, "resultEnvelope");
        proposalSource = copy(proposalSource, "proposalSource");
        isolatedDomainDbBinding = copy(isolatedDomainDbBinding, "isolatedDomainDbBinding");
    }

    @Override
    public JsonNode commandEnvelope() {
        return commandEnvelope.deepCopy();
    }

    @Override
    public JsonNode resultEnvelope() {
        return resultEnvelope.deepCopy();
    }

    @Override
    public JsonNode proposalSource() {
        return proposalSource.deepCopy();
    }

    @Override
    public JsonNode isolatedDomainDbBinding() {
        return isolatedDomainDbBinding.deepCopy();
    }

    private static JsonNode copy(JsonNode value, String field) {
        return Objects.requireNonNull(value, field).deepCopy();
    }

    private static void sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }
}
